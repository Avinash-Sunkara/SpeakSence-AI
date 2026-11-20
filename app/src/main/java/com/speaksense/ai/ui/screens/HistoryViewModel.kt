package com.speaksense.ai.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.speaksense.ai.SpeakSenseApp
import com.speaksense.ai.data.TranscriptItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as SpeakSenseApp).container

    val settings = container.settings.settings

    private val all = MutableStateFlow<List<TranscriptItem>>(emptyList())
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val items: StateFlow<List<TranscriptItem>> = combine(all, _query) { list, q ->
        if (q.isBlank()) list else list.filter { it.fullText.contains(q.trim(), ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun refresh() {
        viewModelScope.launch {
            all.value = withContext(Dispatchers.IO) { container.captions.loadHistory() }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.captions.delete(id) }
            refresh()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.captions.clearAll() }
            refresh()
        }
    }
}
