package com.speaksense.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.speaksense.ai.data.EngineChoice
import com.speaksense.ai.data.PreferredLanguage
import com.speaksense.ai.ui.components.BigButton
import com.speaksense.ai.ui.components.RadioOption
import com.speaksense.ai.ui.components.SectionCard
import com.speaksense.ai.ui.components.SettingSlider
import com.speaksense.ai.ui.components.SettingSwitch
import com.speaksense.ai.ui.components.StatusLevel
import com.speaksense.ai.ui.components.StatusRow

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val powerSave by vm.powerSave.collectAsStateWithLifecycle()
    val exempt by vm.batteryExempt.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SectionCard("Call detection") {
            SettingSwitch(
                "Automatic call detection",
                "Open captions when an incoming or outgoing call becomes active.",
                s.autoCallDetection,
                { v -> vm.update { it.copy(autoCallDetection = v) } },
            )
            SettingSwitch(
                "Try to start the microphone automatically",
                "If Android refuses, the caption window shows a Start button and one tap is enough.",
                s.autoStartListening,
                { v -> vm.update { it.copy(autoStartListening = v) } },
            )
        }

        SectionCard("Floating captions") {
            SettingSwitch(
                "Show floating captions",
                "A caption window over the call screen. Needs 'Display over other apps'.",
                s.floatingCaptions,
                { v -> vm.update { it.copy(floatingCaptions = v) } },
            )
            SettingSlider("Font size", "${s.fontSizeSp} sp", s.fontSizeSp, 14, 48) { v -> vm.update { it.copy(fontSizeSp = v) } }
            SettingSlider("Window width", "${s.captionWidthPercent}%", s.captionWidthPercent, 50, 100) { v -> vm.update { it.copy(captionWidthPercent = v) } }
            SettingSlider("Background opacity", "${s.captionOpacityPercent}%", s.captionOpacityPercent, 30, 100) { v -> vm.update { it.copy(captionOpacityPercent = v) } }
            Text("Preview", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Color(0xFF0A100E).copy(alpha = s.captionOpacityPercent / 100f),
                        RoundedCornerShape(16.dp),
                    )
                    .padding(14.dp),
            ) {
                Column {
                    Text("\u0C2E\u0C40\u0C30\u0C41 \u0C30\u0C47\u0C2A\u0C41 \u0C15\u0C3E\u0C32\u0C47\u0C1C\u0C40\u0C15\u0C3F \u0C35\u0C38\u0C4D\u0C24\u0C3E\u0C30\u0C3E?", fontSize = s.fontSizeSp.sp)
                    Text("I will call you tomorrow.", fontSize = s.fontSizeSp.sp)
                }
            }
        }

        SectionCard("Language") {
            RadioOption("Telugu", "te-IN", s.language == PreferredLanguage.TELUGU) { vm.update { it.copy(language = PreferredLanguage.TELUGU) } }
            RadioOption("English", "en-IN (falls back to other English packs)", s.language == PreferredLanguage.ENGLISH) { vm.update { it.copy(language = PreferredLanguage.ENGLISH) } }
            RadioOption(
                "Telugu + English mixed",
                "Telugu first; English words are written as the recogniser hears them. Never translated.",
                s.language == PreferredLanguage.TELUGU_ENGLISH_MIXED,
            ) { vm.update { it.copy(language = PreferredLanguage.TELUGU_ENGLISH_MIXED) } }
            SettingSwitch(
                "Automatic language detection",
                "Uses the phone's own detection on Android 13+ / 14+ where available, otherwise switches language after repeated misses. Always on for mixed mode.",
                s.autoLanguageDetection,
                { v -> vm.update { it.copy(autoLanguageDetection = v) } },
            )
            Text(
                "The recogniser can only run one language at a time, so words of the other language may be missed or written in the wrong script. Captions are never translated.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Recognition engine") {
            RadioOption(
                "Google Cloud streaming (recommended for Telugu)",
                "Continuous live captions in te-IN through your own server (see backend-streaming/). Needs internet.",
                s.engine == EngineChoice.STREAMING_CLOUD,
            ) {
                vm.update { it.copy(engine = EngineChoice.STREAMING_CLOUD) }
            }
            RadioOption("Android recogniser (online)", "Uses your phone's speech service. Works without a server but restarts after every sentence and is weaker for fast speech.", s.engine == EngineChoice.ANDROID_RECOGNIZER) {
                vm.update { it.copy(engine = EngineChoice.ANDROID_RECOGNIZER) }
            }
            RadioOption("My own backend", "You run a server. Short phrases of speech are sent to it over HTTPS.", s.engine == EngineChoice.REMOTE_BACKEND) {
                vm.update { it.copy(engine = EngineChoice.REMOTE_BACKEND) }
            }
            RadioOption("Whisper.cpp (offline)", "Not bundled in this build. See the README.", s.engine == EngineChoice.WHISPER_CPP) {
                vm.update { it.copy(engine = EngineChoice.WHISPER_CPP) }
            }
            if (s.engine == EngineChoice.STREAMING_CLOUD) {
                OutlinedTextField(
                    value = s.streamingUrl,
                    onValueChange = { v -> vm.update { it.copy(streamingUrl = v.trim()) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Streaming server (wss://...)") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = s.backendToken,
                    onValueChange = { v -> vm.update { it.copy(backendToken = v.trim()) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Access token (stored encrypted)") },
                )
                Text(
                    if (s.streamingUrl.isBlank())
                        "No server set yet, so captions use the Android recogniser (online). Audio is sent only to the server you enter. No Google key is stored in this app."
                    else
                        "Audio is streamed to the server you entered, which forwards it to Google Cloud Speech-to-Text. No Google key is stored in this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.engine == EngineChoice.ANDROID_RECOGNIZER) {
                SettingSwitch(
                    "Allow online recognition",
                    "ON by default: Telugu usually has no on-device pack. Your phone's speech service then processes the audio and may send it to a cloud service. Turn OFF to use only an installed on-device recogniser.",
                    s.allowOnlineRecognition,
                    { v -> vm.update { it.copy(allowOnlineRecognition = v) } },
                )
            }
            if (s.engine == EngineChoice.REMOTE_BACKEND) {
                OutlinedTextField(
                    value = s.backendUrl,
                    onValueChange = { v -> vm.update { it.copy(backendUrl = v.trim()) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Backend address (https://...)") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = s.backendToken,
                    onValueChange = { v -> vm.update { it.copy(backendToken = v.trim()) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Access token (optional, stored encrypted)") },
                )
                Text(
                    "Audio goes to the server you entered. No keys are built into the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard("Audio (backend engine and microphone test)") {
            SettingSwitch("Noise suppression", "Recommended.", s.noiseSuppression, { v -> vm.update { it.copy(noiseSuppression = v) } })
            SettingSwitch(
                "Echo cancellation",
                "Usually leave OFF. It exists to remove the speaker's sound from the microphone, which is exactly the caller's voice we want.",
                s.echoCancellation,
                { v -> vm.update { it.copy(echoCancellation = v) } },
            )
        }

        SectionCard("Privacy") {
            SettingSwitch(
                "Save transcripts on this phone",
                "OFF by default. Only recognised text is saved, encrypted. Call audio is never stored.",
                s.saveTranscripts,
                { v -> vm.update { it.copy(saveTranscripts = v) } },
            )
            BigButton("Delete all saved transcripts", onClick = { confirmClear = true }, primary = false)
            Spacer(Modifier.height(8.dp))
            Text(
                "No ads, no analytics, no contact, SMS or call-log access. Depending on local law you may need to tell the other person that captions are being made.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Battery and background") {
            StatusRow(
                "Battery saver",
                if (powerSave) "ON. Android may interrupt captions." else "Off.",
                if (powerSave) StatusLevel.WARN else StatusLevel.OK,
            )
            StatusRow(
                "Battery optimisation",
                if (exempt) "SpeakSense AI is exempt." else "Not exempt. Recommended on phones that close background apps.",
                if (exempt) StatusLevel.OK else StatusLevel.INFO,
            )
            vm.guidance().forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            BigButton("Battery optimisation settings", onClick = { vm.openBatterySettings() }, primary = false)
            Spacer(Modifier.height(8.dp))
            BigButton("Auto-start / background settings", onClick = { vm.openAutoStartSettings() }, primary = false)
            Spacer(Modifier.height(8.dp))
            BigButton("App info", onClick = { vm.openAppDetails() }, primary = false)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all transcripts?") },
            text = { Text("This permanently removes every saved transcript from this phone.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
