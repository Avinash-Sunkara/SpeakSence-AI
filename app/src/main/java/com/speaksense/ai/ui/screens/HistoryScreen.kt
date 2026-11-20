package com.speaksense.ai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.speaksense.ai.data.TranscriptItem
import com.speaksense.ai.ui.components.BigButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val history by vm.items.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf(-1L) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Caption history", style = MaterialTheme.typography.headlineSmall)
        if (!settings.saveTranscripts) {
            Text(
                "Saving is OFF (the default), so new calls are not stored. Turn it on in Settings > Privacy if you want transcripts. Audio is never stored.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { vm.setQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search transcripts") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        )
        if (history.isEmpty()) {
            Text(
                if (query.isBlank()) "No saved transcripts yet." else "Nothing matches your search.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BigButton("Clear all history", onClick = { confirmClear = true }, primary = false)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(history, key = { it.id }) { item ->
                TranscriptCard(
                    item = item,
                    expanded = expandedId == item.id,
                    onToggle = { expandedId = if (expandedId == item.id) -1L else item.id },
                    onDelete = { vm.delete(item.id) },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all transcripts?") },
            text = { Text("This permanently removes every saved transcript from this phone.") },
            confirmButton = {
                TextButton(onClick = { vm.clearAll(); confirmClear = false }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TranscriptCard(item: TranscriptItem, expanded: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(dateFormat.format(Date(item.startedAt)), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${item.direction.lowercase().replaceFirstChar { it.uppercase() }} call, ${item.lines.size} lines",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete this transcript")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (expanded) {
                item.lines.forEach { line ->
                    Text(line.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
                }
            } else {
                Text(
                    item.lines.firstOrNull()?.text.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
