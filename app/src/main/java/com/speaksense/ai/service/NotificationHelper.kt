package com.speaksense.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.speaksense.ai.MainActivity
import com.speaksense.ai.R
import com.speaksense.ai.model.CallDirection
import com.speaksense.ai.model.SessionPhase
import com.speaksense.ai.model.SessionUiState

class NotificationHelper(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, "Live caption session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while SpeakSense AI is captioning a call. Android requires it while the microphone is in use."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROMPT, "Call detected", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Offers one-tap captions when Android did not allow SpeakSense AI to start automatically."
            },
        )
    }

    fun shortText(state: SessionUiState): String = when (state.phase) {
        SessionPhase.LISTENING -> "Microphone is ON. Listening to the speaker."
        SessionPhase.STARTING -> "Starting captions..."
        SessionPhase.PAUSED -> "Paused. Microphone is off."
        SessionPhase.AWAITING_START -> "Call detected. Tap Start for captions."
        SessionPhase.ERROR -> "Captions stopped. Tap for details."
        SessionPhase.IDLE -> "Idle"
    }

    fun buildSession(state: SessionUiState): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val begin = servicePendingIntent(1, CaptionForegroundService.ACTION_BEGIN_LISTENING)
        val stop = servicePendingIntent(2, CaptionForegroundService.ACTION_STOP)
        val detail = buildString {
            append(state.modeSummary())
            when (state.speakerphoneOn) {
                true -> append("\nSpeakerphone: on.")
                false -> append("\nSpeakerphone: off. Turn it on so the microphone can hear the caller.")
                null -> Unit
            }
            if (state.batterySaver) append("\nBattery saver is on and may interrupt captions.")
            if (!state.networkAvailable) append("\nNo network connection.")
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_caption)
            .setContentTitle("SpeakSense AI - live captions")
            .setContentText(shortText(state))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state.phase == SessionPhase.AWAITING_START || state.phase == SessionPhase.ERROR) {
            builder.addAction(0, "Start", begin)
        }
        builder.addAction(0, "Stop", stop)
        return builder.build()
    }

    /** Fallback when Android refused to start the service: one tap on this starts it (user interaction). */
    fun postStartPrompt(direction: CallDirection) {
        val start = PendingIntent.getForegroundService(
            context, 3,
            Intent(context, CaptionForegroundService::class.java)
                .setAction(CaptionForegroundService.ACTION_START_SESSION)
                .putExtra(CaptionForegroundService.EXTRA_DIRECTION, direction.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_stat_caption)
            .setContentTitle("Call detected")
            .setContentText("Tap to start SpeakSense AI captions.")
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(start)
            .build()
        manager.notify(PROMPT_ID, notification)
    }

    fun cancelPrompt() {
        manager.cancel(PROMPT_ID)
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            context, requestCode,
            Intent(context, CaptionForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val CHANNEL_SESSION = "speaksense_session"
        const val CHANNEL_PROMPT = "speaksense_prompt"
        const val PROMPT_ID = 1002
    }
}
