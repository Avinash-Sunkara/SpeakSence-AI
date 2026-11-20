package com.speaksense.ai.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

data class PermissionSnapshot(
    val microphone: Boolean,
    val phoneState: Boolean,
    val overlay: Boolean,
    val notifications: Boolean,
) {
    /** Minimum set for automatic call captions. */
    val coreReady: Boolean get() = microphone && phoneState && overlay
}

class PermissionManager(private val context: Context) {
    fun hasMic(): Boolean = granted(Manifest.permission.RECORD_AUDIO)
    fun hasPhoneState(): Boolean = granted(Manifest.permission.READ_PHONE_STATE)
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)
    fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun snapshot() = PermissionSnapshot(
        microphone = hasMic(),
        phoneState = hasPhoneState(),
        overlay = canDrawOverlays(),
        notifications = notificationsAllowed(),
    )

    fun overlaySettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
