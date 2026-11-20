package com.speaksense.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import com.speaksense.ai.model.CallDirection
import com.speaksense.ai.model.CallPhase
import com.speaksense.ai.model.SessionPhase
import com.speaksense.ai.ui.components.BigButton
import com.speaksense.ai.ui.components.SectionCard
import com.speaksense.ai.ui.components.SpeakSenseLogo
import com.speaksense.ai.ui.components.StatusLevel
import com.speaksense.ai.ui.components.StatusRow

@Composable
fun DashboardScreen(onOpenSetup: () -> Unit, vm: DashboardViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val call by vm.call.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val offline by vm.offlineStatus.collectAsStateWithLifecycle()
    val micTest by vm.micTest.collectAsStateWithLifecycle()
    val checking by vm.checkingModels.collectAsStateWithLifecycle()
    val batteryExempt by vm.batteryExempt.collectAsStateWithLifecycle()
    val powerSave by vm.powerSave.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeToast()
        }
    }

    val engine = vm.engineAvailability()
    val report = remember { vm.systemReport() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SpeakSenseLogo()

        // ---- service status -----------------------------------------------------------------
        SectionCard("Service status") {
            val callText = when (call.phase) {
                CallPhase.IDLE -> "No call right now"
                CallPhase.RINGING -> "Incoming call ringing"
                CallPhase.ACTIVE -> when (call.direction) {
                    CallDirection.INCOMING -> "Incoming call in progress"
                    CallDirection.OUTGOING -> "Outgoing call in progress"
                    CallDirection.UNKNOWN -> "Call in progress"
                }
            }
            when {
                session.phase == SessionPhase.LISTENING ->
                    StatusRow("Captions", "Listening during a call. Microphone is ON.", StatusLevel.OK)
                session.phase != SessionPhase.IDLE ->
                    StatusRow("Captions", session.modeSummary(), StatusLevel.WARN)
                !perms.phoneState ->
                    StatusRow("Captions", "Call detection is off. Allow phone-state permission in setup.", StatusLevel.ERROR)
                !settings.autoCallDetection ->
                    StatusRow("Captions", "Automatic call detection is turned off in Settings.", StatusLevel.WARN)
                else ->
                    StatusRow("Captions", "Ready. Captions will open when a call becomes active.", StatusLevel.OK)
            }
            StatusRow(
                "Incoming / outgoing call detection",
                if (perms.phoneState && settings.autoCallDetection) "Active. Current state: $callText." else "Not active.",
                if (perms.phoneState && settings.autoCallDetection) StatusLevel.OK else StatusLevel.ERROR,
            )
            StatusRow(
                "Active caption mode",
                if (session.isActive) session.modeSummary()
                else "None right now. During a call SpeakSense AI uses Mode B (speakerphone microphone). Mode A (Android Live Caption) is explained below.",
                when {
                    session.phase == SessionPhase.LISTENING && session.recognizedAnyText -> StatusLevel.OK
                    session.phase == SessionPhase.ERROR -> StatusLevel.ERROR
                    session.isActive -> StatusLevel.WARN
                    else -> StatusLevel.INFO
                },
            )
        }

        // ---- permissions ---------------------------------------------------------------------
        SectionCard("Permissions") {
            StatusRow(
                "Microphone",
                if (perms.microphone) "Allowed" else "Not allowed. Needed to hear the speakerphone.",
                if (perms.microphone) StatusLevel.OK else StatusLevel.ERROR,
            )
            StatusRow(
                "Display over other apps (floating captions)",
                if (perms.overlay) "Allowed" else "Not allowed. Captions cannot appear over the call screen.",
                if (perms.overlay) StatusLevel.OK else StatusLevel.ERROR,
            )
            StatusRow(
                "Call state",
                if (perms.phoneState) "Allowed" else "Not allowed. Calls cannot be detected automatically.",
                if (perms.phoneState) StatusLevel.OK else StatusLevel.ERROR,
            )
            StatusRow(
                "Notifications",
                if (perms.notifications) "Allowed" else "Off. The recording indicator notification will be hidden.",
                if (perms.notifications) StatusLevel.OK else StatusLevel.WARN,
            )
            StatusRow(
                "Battery optimisation",
                when {
                    powerSave -> "Battery saver is ON and may interrupt captions."
                    batteryExempt -> "SpeakSense AI is not restricted."
                    else -> "Not exempted. Some phones stop background work. See Settings > Battery."
                },
                when {
                    powerSave -> StatusLevel.WARN
                    batteryExempt -> StatusLevel.OK
                    else -> StatusLevel.INFO
                },
            )
        }

        // ---- engine + models -----------------------------------------------------------------
        SectionCard("Speech engine") {
            StatusRow(
                "Caption engine: ${engine.label}",
                engine.detail,
                if (engine.ready) StatusLevel.OK else StatusLevel.ERROR,
            )
            val model = offline
            when {
                checking && model == null -> StatusRow("Offline models", "Checking...", StatusLevel.INFO)
                model == null -> StatusRow("Offline models", "Not checked yet.", StatusLevel.INFO)
                !model.onDeviceRecognizerAvailable -> StatusRow(
                    "Offline models",
                    "This phone has no on-device recogniser, so offline Telugu/English recognition is not available.",
                    StatusLevel.WARN,
                )
                !model.supportQueryAvailable -> StatusRow(
                    "Offline models",
                    "On-device recogniser found. Installed languages cannot be listed on Android 12; they are tested when the first caption starts.",
                    StatusLevel.INFO,
                )
                else -> {
                    StatusRow(
                        "Offline Telugu (te-IN)",
                        if (model.teluguInstalled()) "Installed" else "Not installed",
                        if (model.teluguInstalled()) StatusLevel.OK else StatusLevel.WARN,
                    )
                    StatusRow(
                        "Offline English",
                        if (model.englishInstalled()) "Installed" else "Not installed",
                        if (model.englishInstalled()) StatusLevel.OK else StatusLevel.WARN,
                    )
                }
            }
            if (offline?.error != null) {
                Text("Note: ${offline?.error}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            BigButton(
                text = if (checking) "Checking..." else "Check again",
                onClick = { vm.checkModels() },
                enabled = !checking,
                primary = false,
            )
        }

        // ---- actions -------------------------------------------------------------------------
        BigButton(
            text = if (settings.setupCompleted && perms.coreReady) "Open accessibility setup again" else "Start accessibility setup",
            onClick = onOpenSetup,
        )

        SectionCard("Test microphone") {
            Text(
                "Records 4 seconds and shows how loud the microphone is. Best done with speakerphone on and a podcast or a friend talking, to mimic a call.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (micTest.running) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Listening for 4 seconds...", style = MaterialTheme.typography.bodyLarge)
            } else {
                BigButton(
                    text = "Test microphone",
                    onClick = { vm.runMicTest() },
                    enabled = perms.microphone,
                    primary = false,
                )
                if (!perms.microphone) {
                    Text("Allow the microphone permission first.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            val result = micTest.result
            if (result != null && !micTest.running) {
                Spacer(Modifier.height(8.dp))
                StatusRow(
                    "Result: peak ${result.peakPercent}%, average ${result.averagePercent}%",
                    result.message,
                    if (result.success) StatusLevel.OK else StatusLevel.ERROR,
                )
            }
        }

        SectionCard("Mode A and Mode C: Android's own captions") {
            Text(report.deviceLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
            StatusRow(
                "System captioning",
                if (report.systemCaptioningEnabled) "Captions are switched on in Android settings." else "Captions are not switched on in Android settings.",
                StatusLevel.INFO,
            )
            Text(report.modeAGuidance, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(report.modeCVerdict, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            BigButton("Open Android caption settings", onClick = { vm.openCaptionSettings() }, primary = false)
        }

        SectionCard("Privacy") {
            Text(
                "SpeakSense AI never stores call audio. Recognised text is kept only if you turn on saving, and then it is encrypted on this phone. " +
                    "The microphone is only used during a call, and a red dot and a notification show whenever it is on. " +
                    "Depending on where you live, you may need to tell the other person that captions are being made. That is your responsibility.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
