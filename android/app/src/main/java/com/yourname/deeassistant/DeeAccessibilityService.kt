package com.yourname.deeassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

class DeeAccessibilityService : AccessibilityService() {

    companion object {
        // Lets MainActivity reach the running service instance directly,
        // since they're in the same process. Null until the user has
        // enabled the service in Settings and Android has bound it.
        var instance: DeeAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for command execution -- actions are triggered
        // directly via executeAction() from MainActivity.
    }

    override fun onInterrupt() {}

    /**
     * Central dispatcher. MainActivity calls this with the JSON action
     * object returned by the Python backend's /command endpoint.
     * Returns a short human-readable result for logging / TTS confirmation.
     */
    fun executeAction(actionJson: JSONObject): String {
        return when (actionJson.optString("action")) {
            "open_app" -> openApp(actionJson.optString("package_name"))
            "toggle_wifi" -> toggleWifi(actionJson.optBoolean("enable", true))
            "set_volume" -> setVolume(actionJson.optInt("level", 5))
            "tap_coordinates" -> tapAt(
                actionJson.optDouble("x").toFloat(),
                actionJson.optDouble("y").toFloat()
            )
            "click_text" -> clickNodeByText(actionJson.optString("text"))
            "open_settings_panel" -> openSettingsPanel(actionJson.optString("panel"))
            else -> "Unknown action"
        }
    }

    private fun openApp(packageName: String): String {
        if (packageName.isBlank()) return "No package name given"
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            "Opened $packageName"
        } else {
            "App not installed: $packageName"
        }
    }

    // Android 10+ blocks silent Wi-Fi toggling; this opens the quick-settings
    // panel instead, which needs one tap from the user. That's an OS-level
    // limitation, not something the app can bypass without root.
    private fun toggleWifi(enable: Boolean): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
            panelIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(panelIntent)
            "Opened Wi-Fi panel"
        } else {
            @Suppress("DEPRECATION")
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = enable
            "Wi-Fi ${if (enable) "enabled" else "disabled"}"
        }
    }

    private fun setVolume(level: Int): String {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (level.coerceIn(0, 10) * max) / 10
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return "Volume set to $level/10"
    }

    private fun openSettingsPanel(panel: String): String {
        val action = when (panel) {
            "internet" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "nfc" -> Settings.Panel.ACTION_NFC
            "volume" -> Settings.Panel.ACTION_VOLUME
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        return "Opened $panel panel"
    }

    private fun tapAt(x: Float, y: Float): String {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
        return "Tapped ($x, $y)"
    }

    // Preferred over raw coordinate tapping -- finds a real UI element by
    // its visible text and performs an actual click action on it, which is
    // far more reliable across different screen sizes and app layouts.
    private fun clickNodeByText(text: String): String {
        if (text.isBlank()) return "No text given"
        val root = rootInActiveWindow ?: return "No active window"
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            var clickable: AccessibilityNodeInfo? = node
            while (clickable != null && !clickable.isClickable) {
                clickable = clickable.parent
            }
            clickable?.let {
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return "Clicked '$text'"
            }
        }
        return "Couldn't find clickable element with text '$text'"
    }
}
