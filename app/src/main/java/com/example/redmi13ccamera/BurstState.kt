package com.example.redmi13ccamera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Состояние серии: сервис пишет, Activity читает. */
data class BurstState(
    val isRunning: Boolean = false,
    val countdownSec: Int = 0,
    val shotsTaken: Int = 0,
    val shotsFailed: Int = 0,
    val totalShots: Int = 0,          // 0 = бесконечно
    val phase: String = "",           // "Старт через" / "Следующий кадр через"
    val resolution: String = "",
    val status: String = "Готово к съёмке"
)

object BurstStateHolder {
    private val _state = MutableStateFlow(BurstState())
    val state: StateFlow<BurstState> = _state.asStateFlow()

    fun update(transform: (BurstState) -> BurstState) {
        _state.update(transform)
    }
}
