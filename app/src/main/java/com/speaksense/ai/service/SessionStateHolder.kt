package com.speaksense.ai.service

import com.speaksense.ai.model.SessionUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Single source of truth for the current caption session; read by the service, overlay, notification and UI. */
class SessionStateHolder {
    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    fun update(transform: (SessionUiState) -> SessionUiState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = SessionUiState()
    }
}
