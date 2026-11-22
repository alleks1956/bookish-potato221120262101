package com.example.redmi13ccamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Фоновая серия с таймером.
 *
 * Foreground service (тип camera) + PARTIAL_WAKE_LOCK: съёмка продолжается
 * при выключенном экране и после сворачивания приложения.
 * Без Preview: меньше нагрузка на ОЗУ и GPU Helio G85.
 * Разрешение: только биннинг 12,5 МП (4080x3060). 50 МП отсекается фильтром.
 */
class BurstService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.example.redmi13ccamera.START"
        const val ACTION_STOP = "com.example.redmi13ccamera.STOP"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_DELAY = "delay"

        private const val TAG = "Redmi13CBurst"
        private const val CHANNEL_ID = "burst_channel"
        private const val NOTIFICATION_ID = 1301

        // Биннинг 4-в-1: 50 МП -> 12,5 МП
        private val TARGET_SIZE = Size(4080, 3060)
        private const val MAX_PIXELS = 13_000_000L   // всё, что больше, не используем
        private const val JPEG_QUALITY = 90
        private const val MAX_FAIL_STREAK = 5
        private const val WAKELOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
        private const val SAVE_DIR = "Pictures/Redmi13CCamera"
    }

    private var burstJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "Серия с таймером", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                // Обязательно сразу: после startForegroundService есть всего 5 секунд
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification("Подготовка камеры…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
                if (burstJob?.isActive != true) {
                    startBurst(
                        total = intent.getIntExtra(EXTRA_TOTAL, 10),
                        intervalSec = intent.getIntExtra(EXTRA_INTERVAL, 2).coerceAtLeast(1),
                        delaySec = intent.getIntExtra(EXTRA_DELAY, 3).coerceAtLeast(0)
                    )
                }
            }
            ACTION_STOP -> {
                val job = burstJob
                if (job != null && job.isActive) job.cancel() else stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startBurst(total: Int, intervalSec: Int, delaySec: Int) {
        acquireWakeLock()
        BurstStateHolder.update {
            BurstState(isRunning = true, totalShots = total, status = "Подготовка камеры…")
        }

        burstJob = lifecycleScope.launch {
            var finalStatus = "Серия остановлена"
            try {
                finalStatus = runBurst(total, intervalSec, delaySec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка серии", e)
                finalStatus = "Ошибка камеры: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                runCatching { cameraProvider?.unbindAll() }
                releaseWakeLock()
                BurstStateHolder.update {
                    it.copy(isRunning = false, countdownSec = 0, phase = "", status = finalStatus)
                }
                ServiceCompat.stopForeground(this@BurstService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun runBurst(total: Int, intervalSec: Int, delaySec: Int): String {
        val provider = ProcessCameraProvider.getInstance(this).await()
        cameraProvider = provider

        val capture = buildImageCapture()
        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, capture)

        val res = capture.resolutionInfo?.resolution
        val resText = if (res != null) {
            String.format(Locale.US, "%.1f МП (%dx%d)", res.width * res.height / 1_000_000.0, res.width, res.height)
        } else "12,5 МП"
        BurstStateHolder.update { it.copy(resolution = resText, status = "Камера готова") }
        Log.i(TAG, "Разрешение снимка: $resText")

        // Пауза до старта: за это время успевают сойтись экспозиция и фокус
        waitUntil(SystemClock.elapsedRealtime() + delaySec * 1000L, "Старт через")

        val infinite = total <= 0
        val intervalMs = intervalSec * 1000L
        val t0 = SystemClock.elapsedRealtime()
        var n = 0
        var ok = 0
        var failStreak = 0

        while (infinite || n < total) {
            n++
            val saved = takePhoto(capture, n)
            if (saved) { ok++; failStreak = 0 } else failStreak++

            val label = if (infinite) "∞" else total.toString()
            val msg = "Снято: $ok / $label"
            BurstStateHolder.update {
                it.copy(shotsTaken = ok, shotsFailed = n - ok, status = msg)
            }
            updateNotification(msg)

            if (failStreak >= MAX_FAIL_STREAK) {
                return "Остановлено: $MAX_FAIL_STREAK ошибки подряд (сохранено $ok)"
            }
            if (!infinite && n >= total) break

            // Период считается от t0, а не «пауза после кадра»: время записи не накапливается
            waitUntil(t0 + n * intervalMs, "Следующий кадр через")
        }
        return "Серия завершена: сохранено $ok из $n"
    }

    private fun buildImageCapture(): ImageCapture {
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(TARGET_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            // Страховка: даже если камера предложит 50 МП, берём только размеры до ~13 МП
            .setResolutionFilter(ResolutionFilter { sizes, _ ->
                val limited = sizes.filter { it.width.toLong() * it.height <= MAX_PIXELS }
                if (limited.isEmpty()) sizes else limited
            })
            .build()

        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(JPEG_QUALITY)
            .setResolutionSelector(selector)
            .build()
    }

    /** Ждёт до момента target, обновляя обратный отсчёт. */
    private suspend fun waitUntil(target: Long, phase: String) {
        while (true) {
            val left = target - SystemClock.elapsedRealtime()
            if (left <= 0) break
            val sec = ((left + 999) / 1000).toInt()
            BurstStateHolder.update { it.copy(countdownSec = sec, phase = phase) }
            delay(minOf(left, 250L))
        }
        BurstStateHolder.update { it.copy(countdownSec = 0, phase = "") }
    }

    private suspend fun takePhoto(capture: ImageCapture, index: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "IMG_${stamp}_" + String.format(Locale.US, "%04d", index)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, SAVE_DIR)
            }
            val options = ImageCapture.OutputFileOptions.Builder(
                contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ).build()

            try {
                capture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Кадр #$index не сохранён", exception)
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takePicture упал", e)
                if (cont.isActive) cont.resume(false)
            }
        }

    // ---- WakeLock и уведомление -------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Redmi13CCamera::Burst")
            .apply { acquire(WAKELOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BurstService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Серия с таймером (12,5 МП)")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}
