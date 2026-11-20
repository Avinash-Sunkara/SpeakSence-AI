package com.speaksense.ai.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.speaksense.ai.ui.components.BigButton
import com.speaksense.ai.ui.components.SectionCard
import com.speaksense.ai.ui.components.StatusLevel
import com.speaksense.ai.ui.components.StatusRow

private data class WizardStep(
    val title: String,
    val body: String,
    val statusText: String?,
    val done: Boolean?,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val secondLabel: String? = null,
    val onSecond: (() -> Unit)? = null,
)

@Composable
fun SetupWizardScreen(onFinished: () -> Unit, vm: DashboardViewModel = viewModel()) {
    val context = LocalContext.current
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val offline by vm.offlineStatus.collectAsStateWithLifecycle()
    val exempt by vm.batteryExempt.collectAsStateWithLifecycle()
    var index by rememberSaveable { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    fun openAppSettings() {
        context.startActivity(vm.appDetailsIntent())
    }

    val steps = listOf(
        WizardStep(
            title = "Welcome to SpeakSense AI",
            body = "This short setup gives SpeakSense AI only what it needs to caption phone calls. Honest limits first: " +
                "Android does not let apps record the other person's voice directly. SpeakSense AI listens through the " +
                "microphone while your phone is on speakerphone, and it will tell you whether that is working. " +
                "Android's own Live Caption (Mode A) is the fallback. No hidden recording, no uploads, no call audio stored.",
            statusText = null,
            done = null,
        ),
        WizardStep(
            title = "Microphone",
            body = "Needed to hear the speakerphone during a call. The microphone is only used while a call is being captioned, and a notification and a red dot show when it is on.",
            statusText = if (perms.microphone) "Allowed" else "Not allowed yet",
            done = perms.microphone,
            actionLabel = if (perms.microphone) null else "Allow microphone",
            onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            secondLabel = if (perms.microphone) null else "Open app settings",
            onSecond = { openAppSettings() },
        ),
        WizardStep(
            title = "Call detection",
            body = "Lets SpeakSense AI notice when a call starts and ends. It reads only the call state (ringing, in call, idle). " +
                "It cannot see phone numbers, contacts, messages or the call log.",
            statusText = if (perms.phoneState) "Allowed" else "Not allowed yet",
            done = perms.phoneState,
            actionLabel = if (perms.phoneState) null else "Allow call detection",
            onAction = { phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
            secondLabel = if (perms.phoneState) null else "Open app settings",
            onSecond = { openAppSettings() },
        ),
        WizardStep(
            title = "Display over other apps",
            body = "Lets the caption window appear on top of the call screen. In the next screen, find SpeakSense AI and switch it on. " +
                "If the switch is greyed out, open App info, tap the three dots and choose 'Allow restricted settings'.",
            statusText = if (perms.overlay) "Allowed" else "Not allowed yet",
            done = perms.overlay,
            actionLabel = if (perms.overlay) null else "Open overlay settings",
            onAction = { context.startActivity(vm.overlayIntent()) },
            secondLabel = if (perms.overlay) null else "Open app settings",
            onSecond = { openAppSettings() },
        ),
        WizardStep(
            title = "Notifications",
            body = "Android requires a visible notification while the microphone is used. It also gives you a Start / Stop button during a call.",
            statusText = if (perms.notifications) "Allowed" else "Off",
            done = perms.notifications,
            actionLabel = if (perms.notifications) null else if (Build.VERSION.SDK_INT >= 33) "Allow notifications" else "Open notification settings",
            onAction = {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        ),
        WizardStep(
            title = "Background reliability",
            body = "Some phones (including Vivo, iQOO and Oppo) close background apps aggressively. These two shortcuts can help. " +
                "They cannot override Android's own rules, so keep the note below in mind: if Android blocks automatic start, " +
                "the caption window shows a Start button and one tap is enough.",
            statusText = if (exempt) "Battery optimisation: exempt" else "Battery optimisation: not exempt (optional)",
            done = exempt,
            actionLabel = "Battery optimisation settings",
            onAction = { vm.openBatterySettings() },
            secondLabel = "Auto-start settings",
            onSecond = { vm.openAutoStartSettings() },
        ),
        WizardStep(
            title = "Speech models",
            body = "Offline recognition needs a language pack on your phone. Request Telugu and English below (Android 13+). " +
                "If your phone has no on-device recogniser you can allow online recognition or use your own backend in Settings. " +
                "Nothing is sent to a cloud service unless you choose that.",
            statusText = offline?.let {
                when {
                    !it.onDeviceRecognizerAvailable -> "No on-device recogniser on this phone"
                    !it.supportQueryAvailable -> "On-device recogniser found (installed languages cannot be listed on Android 12)"
                    else -> "Telugu: ${if (it.teluguInstalled()) "installed" else "not installed"}, English: ${if (it.englishInstalled()) "installed" else "not installed"}"
                }
            } ?: "Checking...",
            done = offline?.let { it.onDeviceRecognizerAvailable && (!it.supportQueryAvailable || (it.teluguInstalled() && it.englishInstalled())) },
            actionLabel = "Download Telugu model",
            onAction = { vm.downloadModel("te-IN") },
            secondLabel = "Download English model",
            onSecond = { vm.downloadModel("en-IN") },
        ),
        WizardStep(
            title = "Finish: speakerphone and Live Caption",
            body = "During a call, switch on SPEAKERPHONE so the microphone can hear the caller. Your own voice will also be captioned; " +
                "one microphone cannot separate the two. Open SpeakSense AI's dashboard any time to see which mode is working. " +
                "If the microphone stays silent during calls on your phone, use Android's Live Caption (Mode A) instead.",
            statusText = null,
            done = null,
            actionLabel = "Open Android caption settings",
            onAction = { vm.openCaptionSettings() },
        ),
    )
    val last = steps.lastIndex
    val step = steps[index.coerceIn(0, last)]

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Accessibility setup", style = MaterialTheme.typography.headlineSmall)
        Text("Step ${index + 1} of ${steps.size}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
        LinearProgressIndicator(progress = { (index + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())

        SectionCard(step.title) {
            Text(step.body, style = MaterialTheme.typography.bodyLarge)
            if (step.statusText != null) {
                Spacer(Modifier.height(8.dp))
                StatusRow(
                    "Status",
                    step.statusText,
                    when (step.done) {
                        true -> StatusLevel.OK
                        false -> StatusLevel.WARN
                        null -> StatusLevel.INFO
                    },
                )
            }
            if (step.actionLabel != null && step.onAction != null) {
                Spacer(Modifier.height(12.dp))
                BigButton(step.actionLabel, onClick = step.onAction)
            }
            if (step.secondLabel != null && step.onSecond != null) {
                Spacer(Modifier.height(8.dp))
                BigButton(step.secondLabel, onClick = step.onSecond, primary = false)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (index > 0) {
                TextButton(onClick = { index -= 1 }) { Text("Back", style = MaterialTheme.typography.labelLarge) }
            }
            Spacer(Modifier.weight(1f))
            if (index < last) {
                BigButton("Next", onClick = { index += 1 }, modifier = Modifier.weight(2f))
            } else {
                BigButton(
                    "Finish setup",
                    onClick = {
                        vm.markSetupCompleted()
                        onFinished()
                    },
                    modifier = Modifier.weight(2f),
                )
            }
        }
    }
}
