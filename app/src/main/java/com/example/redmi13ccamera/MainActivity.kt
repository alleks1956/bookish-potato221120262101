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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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

class MainActivity : ComponentActivity() {

    private lateinit var etShots: EditText
    private lateinit var etInterval: EditText
    private lateinit var etDelay: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

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
        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) launchService() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun launchService() {
        val intent = Intent(this, BurstService::class.java)
            .setAction(BurstService.ACTION_START)
            .putExtra(BurstService.EXTRA_TOTAL, readInt(etShots, 10, 0, 99999))
            .putExtra(BurstService.EXTRA_INTERVAL, readInt(etInterval, 2, 1, 3600))
            .putExtra(BurstService.EXTRA_DELAY, readInt(etDelay, 3, 0, 600))
        // Foreground-сервис с камерой можно запускать только пока приложение на экране
        ContextCompat.startForegroundService(this, intent)
    }

    private fun onStopClicked() {
        startService(Intent(this, BurstService::class.java).setAction(BurstService.ACTION_STOP))
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun readInt(field: EditText, def: Int, min: Int, max: Int): Int =
        (field.text.toString().trim().toIntOrNull() ?: def).coerceIn(min, max)

    // ---- Отображение состояния ----------------------------------------------

    private fun render(s: BurstState) {
        btnStart.isEnabled = !s.isRunning
        btnStop.isEnabled = s.isRunning
        listOf(etShots, etInterval, etDelay).forEach { it.isEnabled = !s.isRunning }

        tvStatus.text = buildString {
            append(s.status)
            if (s.resolution.isNotEmpty()) append("\nРазрешение: ").append(s.resolution)
            if (s.isRunning) {
                val total = if (s.totalShots <= 0) "∞" else s.totalShots.toString()
                append("\nСнято: ").append(s.shotsTaken).append(" / ").append(total)
                if (s.shotsFailed > 0) append("  (ошибок: ").append(s.shotsFailed).append(")")
                if (s.countdownSec > 0 && s.phase.isNotEmpty()) {
                    append("\n").append(s.phase).append(": ").append(s.countdownSec).append(" с")
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
        col.addView(label("Задержка перед стартом, секунд"))
        col.addView(etDelay)

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
