package com.speaksense.ai.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Best-effort shortcuts to battery / auto-start screens. These settings can make background work more
 * reliable on aggressive Android skins (Funtouch OS, OriginOS, ColorOS...) but they can NOT override
 * Android's own security rules, and OEM screens move between versions.
 */
class BatteryOptimizationHelper(private val context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun isIgnoringBatteryOptimizations(): Boolean =
        powerManager.isIgnoringBatteryOptimizations(context.packageName)

    fun isPowerSaveMode(): Boolean = powerManager.isPowerSaveMode

    /** Opens the system list where the user can pick "Don't optimise" for SpeakSense AI. */
    fun openBatteryOptimizationSettings(): Boolean =
        tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    fun openAppDetails(): Boolean = tryStart(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )

    /** Tries known Vivo / iQOO / Oppo / ColorOS / Xiaomi auto-start screens, falls back to App info. */
    fun openAutoStartSettings(): Boolean {
        for ((pkg, cls) in OEM_AUTOSTART_SCREENS) {
            val intent = Intent().setComponent(ComponentName(pkg, cls))
            if (tryStart(intent)) return true
        }
        return openAppDetails()
    }

    fun manufacturerGuidance(): List<String> {
        val maker = android.os.Build.MANUFACTURER.lowercase()
        val specific = when {
            maker.contains("vivo") || maker.contains("iqoo") ->
                "Vivo / iQOO (Funtouch OS or OriginOS): i Manager > App manager > Autostart, allow SpeakSense AI. " +
                    "Settings > Battery > Background power consumption management > SpeakSense AI > Allow high background power consumption."
            maker.contains("oppo") || maker.contains("oneplus") || maker.contains("realme") ->
                "Oppo / ColorOS: Settings > Apps > Auto-launch (or Startup manager), allow SpeakSense AI. " +
                    "Settings > Battery > SpeakSense AI > Allow background activity."
            maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco") ->
                "Xiaomi / HyperOS: Security > Permissions > Autostart, allow SpeakSense AI; set Battery saver to No restrictions."
            maker.contains("samsung") ->
                "Samsung: Settings > Battery > Background usage limits > Never sleeping apps, add SpeakSense AI."
            else -> "Open App info > Battery and choose Unrestricted if your phone offers it."
        }
        return listOf(
            specific,
            "Also allow 'Display over other apps' so captions can appear over the call screen.",
            "These steps help, but Android and your phone maker may still limit background activity. They cannot override Android's microphone rules.",
        )
    }

    private fun tryStart(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Log.d(TAG, "Could not open ${intent.action ?: intent.component}: ${e.javaClass.simpleName}")
        false
    }

    private companion object {
        const val TAG = "BatteryHelper"
        val OEM_AUTOSTART_SCREENS = listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
    }
}
