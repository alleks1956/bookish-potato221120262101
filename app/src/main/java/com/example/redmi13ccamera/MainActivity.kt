package com.example.redmi13ccamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var etShots: EditText
    private lateinit var etInterval: EditText
    private lateinit var etDelay: EditText
    private lateinit var etStartTime: EditText
    private lateinit var etStopTime: EditText
    private lateinit var sbEv: SeekBar
    private lateinit var tvEv: TextView
    private lateinit var cbZoom: CheckBox
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    // EV: ползунок 0..12 -> от -3.0 до +3.0 с шагом 0.5
    private val evSteps = 12
    private fun evValue(progress: Int) = (progress - evSteps / 2) * 0.5f

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCameraPermission()) launchService()
            else Toast.makeText(this, "Нужно разрешение на камеру", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BurstStateHolder.state.collect { render(it) }
            }
        }
    }

    // ---- Запуск / остановка ------------------------------------------------

    private fun onStartClicked() {
        // Проверяем таймер до запроса разрешений
        if (parseTimer() == null) return

        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) launchService() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun launchService() {
        val (startAt, stopAt) = parseTimer() ?: return
        val intent = Intent(this, BurstService::class.java)
            .setAction(BurstService.ACTION_START)
            .putExtra(BurstService.EXTRA_TOTAL, readInt(etShots, 10, 0, 99999))
            .putExtra(BurstService.EXTRA_INTERVAL, readInt(etInterval, 2, 1, 3600))
            .putExtra(BurstService.EXTRA_DELAY, readInt(etDelay, 3, 0, 600))
            .putExtra(BurstService.EXTRA_EV, evValue(sbEv.progress))
            .putExtra(BurstService.EXTRA_ZOOM, if (cbZoom.isChecked) 2f else 1f)
            .putExtra(BurstService.EXTRA_START_AT, startAt)
            .putExtra(BurstService.EXTRA_STOP_AT, stopAt)
        // Foreground-сервис с камерой можно запускать только пока приложение на экране
        ContextCompat.startForegroundService(this, intent)
    }

    /** Меняет EV и зум на лету, пока серия идёт. */
    private fun sendLiveUpdate() {
        if (!BurstStateHolder.state.value.isRunning) return
        startService(
            Intent(this, BurstService::class.java)
                .setAction(BurstService.ACTION_UPDATE)
                .putExtra(BurstService.EXTRA_EV, evValue(sbEv.progress))
                .putExtra(BurstService.EXTRA_ZOOM, if (cbZoom.isChecked) 2f else 1f)
        )
    }

    private fun onStopClicked() {
        startService(Intent(this, BurstService::class.java).setAction(BurstService.ACTION_STOP))
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun readInt(field: EditText, def: Int, min: Int, max: Int): Int =
        (field.text.toString().trim().toIntOrNull() ?: def).coerceIn(min, max)

    // ---- Таймер: время старта и остановки ЧЧ:ММ -------------------------------

    /** Возвращает (startAt, stopAt) в epoch ms; 0 = не задано. null = ошибка ввода. */
    private fun parseTimer(): Pair<Long, Long>? {
        val now = System.currentTimeMillis()

        val startAt = parseClock(etStartTime, now, allowRecent = true)
        if (startAt == null) { toastBadTime("старта"); return null }

        val base = if (startAt > 0) startAt else now
        val stopAt = parseClock(etStopTime, base, allowRecent = false)
        if (stopAt == null) { toastBadTime("остановки"); return null }

        return Pair(startAt, stopAt)
    }

    private fun toastBadTime(what: String) =
        Toast.makeText(this, "Время $what: формат ЧЧ:ММ, например 22:30", Toast.LENGTH_LONG).show()

    /**
     * Пусто -> 0. Иначе ближайшее наступление времени ЧЧ:ММ после [after].
     * Для старта ([allowRecent]) время в пределах последней минуты считается «сейчас».
     */
    private fun parseClock(field: EditText, after: Long, allowRecent: Boolean): Long? {
        val s = field.text.toString().trim()
        if (s.isEmpty()) return 0L
        val m = Regex("^(\\d{1,2})[:.](\\d{2})$").matchEntire(s) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val limit = if (allowRecent) after - 60_000L else after
        if (cal.timeInMillis <= limit) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    // ---- Отображение состояния ----------------------------------------------

    private fun fmtSeconds(sec: Int): String = when {
        sec >= 3600 -> String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
        sec >= 60 -> String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
        else -> "$sec с"
    }

    private fun render(s: BurstState) {
        btnStart.isEnabled = !s.isRunning
        btnStop.isEnabled = s.isRunning
        listOf(etShots, etInterval, etDelay, etStartTime, etStopTime).forEach { it.isEnabled = !s.isRunning }

        tvStatus.text = buildString {
            append(s.status)
            if (s.resolution.isNotEmpty()) append("\nРазрешение: ").append(s.resolution)
            if (s.controls.isNotEmpty()) append("\nПрименено: ").append(s.controls)
            if (s.isRunning) {
                val total = if (s.totalShots <= 0) "∞" else s.totalShots.toString()
                append("\nСнято: ").append(s.shotsTaken).append(" / ").append(total)
                if (s.shotsFailed > 0) append("  (ошибок: ").append(s.shotsFailed).append(")")
                if (s.countdownSec > 0 && s.phase.isNotEmpty()) {
                    append("\n").append(s.phase).append(": ").append(fmtSeconds(s.countdownSec))
                }
            }
        }
    }

    // ---- UI без XML ----------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun numberField(value: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value)
        setSingleLine()
    }

    private fun timeField() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        hint = "ЧЧ:ММ (пусто = нет)"
        setSingleLine()
    }

    private fun updateEvLabel() {
        tvEv.text = String.format(Locale.US, "Компенсация экспозиции: %+.1f EV", evValue(sbEv.progress))
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        col.addView(TextView(this).apply {
            text = "Серия с таймером"
            textSize = 24f
        })
        col.addView(TextView(this).apply {
            text = "Съёмка в 12,5 МП (биннинг 4 в 1), JPEG, работает в фоне.\n" +
                    "Фото: Pictures/Redmi13CCamera"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        })

        etShots = numberField("10")
        etInterval = numberField("2")
        etDelay = numberField("3")

        col.addView(label("Количество кадров (0 = без ограничения)"))
        col.addView(etShots)
        col.addView(label("Интервал, секунд (рекомендуется от 2)"))
        col.addView(etInterval)
        col.addView(label("Задержка перед съёмкой, секунд"))
        col.addView(etDelay)

        // --- Таймер пуск / стоп ---
        etStartTime = timeField()
        etStopTime = timeField()
        col.addView(label("Таймер: время пуска (ЧЧ:ММ)"))
        col.addView(etStartTime)
        col.addView(label("Таймер: время остановки (ЧЧ:ММ)"))
        col.addView(etStopTime)

        // --- Экспозиция ---
        tvEv = label("")
        sbEv = SeekBar(this).apply {
            max = evSteps
            progress = evSteps / 2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = updateEvLabel()
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) = sendLiveUpdate()
            })
        }
        updateEvLabel()
        col.addView(tvEv)
        col.addView(sbEv)
        col.addView(Button(this).apply {
            text = "Сбросить EV в 0"
            setOnClickListener { sbEv.progress = evSteps / 2; updateEvLabel(); sendLiveUpdate() }
        })

        // --- Зум ---
        cbZoom = CheckBox(this).apply {
            text = "Цифровое увеличение 2×"
            setOnCheckedChangeListener { _, _ -> sendLiveUpdate() }
        }
        col.addView(cbZoom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        btnStart = Button(this).apply {
            text = "Старт"
            setOnClickListener { onStartClicked() }
        }
        btnStop = Button(this).apply {
            text = "Стоп"
            isEnabled = false
            setOnClickListener { onStopClicked() }
        }
        col.addView(btnStart, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        col.addView(btnStop)

        tvStatus = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(16), 0, dp(16))
        }
        col.addView(tvStatus)

        col.addView(Button(this).apply {
            text = "Настройки батареи (без ограничений)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })

        val scroll = ScrollView(this).apply { addView(col) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return scroll
    }
}
