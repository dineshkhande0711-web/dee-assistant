package com.yourname.deeassistant

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var statusText: TextView
    private lateinit var serverInfoText: TextView
    private lateinit var enableAccessibilityButton: Button

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                setupSpeechRecognizer()
            } else {
                Toast.makeText(this, "Microphone permission is required for voice commands", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            NetworkClient.init(this)

            statusText = findViewById(R.id.statusText)
            serverInfoText = findViewById(R.id.serverInfoText)
            val micButton = findViewById<Button>(R.id.micButton)
            enableAccessibilityButton = findViewById(R.id.enableAccessibilityButton)
            val settingsButton = findViewById<Button>(R.id.settingsButton)

            try {
                tts = TextToSpeech(this, this)
            } catch (e: Exception) {
                Log.e("Dee", "TTS initialization failed", e)
            }

            enableAccessibilityButton.setOnClickListener { openAccessibilitySettings() }
            settingsButton.setOnClickListener { showSettingsDialog() }

            micButton.setOnClickListener {
                if (!isAccessibilityServiceEnabled()) {
                    Toast.makeText(
                        this,
                        "Please enable Accessibility Service first (Step 1)",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                startListening()
            }

            updateUiState()
            setupSpeechRecognizer()

            // Request microphone permission if not yet granted
            try {
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            } catch (e: Exception) {
                Log.e("Dee", "Permission request error", e)
            }

            pushInstalledAppsToBackend()
        } catch (t: Throwable) {
            Log.e("Dee", "Startup error in onCreate", t)
            val errorScroll = ScrollView(this)
            val errorView = TextView(this).apply {
                text = "Dee Startup Diagnostics:\n\n${t.javaClass.simpleName}: ${t.message}\n\n${t.stackTraceToString()}"
                setPadding(50, 80, 50, 50)
                textSize = 14f
                setTextColor(android.graphics.Color.RED)
            }
            errorScroll.addView(errorView)
            setContentView(errorScroll)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updateUiState()
        } catch (e: Exception) {
            Log.e("Dee", "Error in onResume", e)
        }
    }

    private fun updateUiState() {
        if (!::serverInfoText.isInitialized || !::enableAccessibilityButton.isInitialized) return
        serverInfoText.text = "Server: ${NetworkClient.baseUrl} (${NetworkClient.deviceId})"
        if (isAccessibilityServiceEnabled()) {
            enableAccessibilityButton.text = "✓ Accessibility Service Enabled"
        } else {
            enableAccessibilityButton.text = "1. Enable Accessibility Service"
        }
    }

    // -----------------------------------------------------------------
    // Settings Dialog: customize Server URL and Device Token on-device
    // -----------------------------------------------------------------
    private fun showSettingsDialog() {
        try {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 40, 50, 20)
            }

            val urlInput = EditText(this).apply {
                hint = "Server URL (e.g. http://10.99.185.82:8000)"
                setText(NetworkClient.baseUrl)
            }
            val deviceIdInput = EditText(this).apply {
                hint = "Device ID (e.g. my-phone)"
                setText(NetworkClient.deviceId)
            }
            val tokenInput = EditText(this).apply {
                hint = "Device Token (e.g. change-me-1)"
                setText(NetworkClient.deviceToken)
            }

            layout.addView(TextView(this).apply { text = "Server URL:" })
            layout.addView(urlInput)
            layout.addView(TextView(this).apply { text = "Device ID:" })
            layout.addView(deviceIdInput)
            layout.addView(TextView(this).apply { text = "Device Token:" })
            layout.addView(tokenInput)

            AlertDialog.Builder(this)
                .setTitle("Backend Connection Settings")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    NetworkClient.saveSettings(
                        this,
                        urlInput.text.toString(),
                        deviceIdInput.text.toString(),
                        tokenInput.text.toString()
                    )
                    updateUiState()
                    pushInstalledAppsToBackend()
                    Toast.makeText(this, "Settings updated!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            Log.e("Dee", "Settings dialog error", e)
        }
    }

    // -----------------------------------------------------------------
    // Accessibility check
    // -----------------------------------------------------------------
    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val expectedComponentName = ComponentName(this, DeeAccessibilityService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val componentName = ComponentName.unflattenFromString(componentNameString)
                if (componentName != null &&
                    (componentName == expectedComponentName ||
                     (componentName.packageName == packageName && componentName.className.endsWith("DeeAccessibilityService")))
                ) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show()
        }
    }

    // -----------------------------------------------------------------
    // Voice capture
    // -----------------------------------------------------------------
    private fun setupSpeechRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                if (::statusText.isInitialized) {
                    statusText.text = "System speech recognizer is not available on this device"
                }
                return
            }
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull()
                        if (!spokenText.isNullOrBlank()) {
                            statusText.text = "You said: \"$spokenText\"\n\nAsking Dee..."
                            sendToBackend(spokenText)
                        } else {
                            statusText.text = "Didn't catch any words. Tap to try again."
                        }
                    }

                    override fun onError(error: Int) {
                        val message = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition"
                            else -> "Speech error code: $error"
                        }
                        if (::statusText.isInitialized) {
                            statusText.text = "$message -- tap to try again"
                        }
                    }

                    override fun onReadyForSpeech(params: Bundle?) {
                        if (::statusText.isInitialized) {
                            statusText.text = "Listening... Speak now!"
                        }
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        if (::statusText.isInitialized) {
                            statusText.text = "Processing speech..."
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e("Dee", "Speech recognizer init failed", e)
        }
    }

    private fun startListening() {
        try {
            if (speechRecognizer == null) {
                setupSpeechRecognizer()
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            if (::statusText.isInitialized) {
                statusText.text = "Could not start voice recognition: ${e.message}"
            }
        }
    }

    // -----------------------------------------------------------------
    // Backend round-trip
    // -----------------------------------------------------------------
    private fun sendToBackend(text: String) {
        NetworkClient.sendCommand(text, object : NetworkClient.Callback {
            override fun onResult(response: JSONObject) {
                runOnUiThread { handleBackendResponse(response) }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    if (::statusText.isInitialized) {
                        statusText.text = "Backend Error:\n$error\n\nCheck Server Settings or verify backend is running."
                    }
                }
            }
        })
    }

    private fun handleBackendResponse(response: JSONObject) {
        try {
            when (response.optString("kind")) {
                "action" -> {
                    val service = DeeAccessibilityService.instance
                    if (service == null) {
                        statusText.text = "Accessibility service is not active. Please enable it in Settings."
                        return
                    }
                    val result = service.executeAction(response)
                    statusText.text = "Dee executed action:\n$result"
                    speak(result)
                }
                "answer" -> {
                    val speech = response.optString("speech", "I'm not sure.")
                    statusText.text = "Dee answered:\n$speech"
                    speak(speech)
                }
                else -> statusText.text = "Unrecognized response from Dee: $response"
            }
        } catch (e: Exception) {
            statusText.text = "Error handling response: ${e.message}"
        }
    }

    // -----------------------------------------------------------------
    // Installed-app registration
    // -----------------------------------------------------------------
    private fun pushInstalledAppsToBackend() {
        try {
            val pm = packageManager
            val launchableApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .associate { pm.getApplicationLabel(it).toString() to it.packageName }

            NetworkClient.registerInstalledApps(launchableApps, object : NetworkClient.Callback {
                override fun onResult(response: JSONObject) {
                    runOnUiThread {
                        if (::serverInfoText.isInitialized) {
                            val count = response.optInt("app_count", launchableApps.size)
                            serverInfoText.text = "Server: Connected ($count apps synced)"
                        }
                    }
                }
                override fun onError(error: String) {
                    // Silent retry on user action
                }
            })
        } catch (e: Exception) {
            Log.e("Dee", "Push installed apps error", e)
        }
    }

    // -----------------------------------------------------------------
    private fun speak(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance-${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e("Dee", "TTS speak error", e)
        }
    }

    override fun onInit(status: Int) {
        try {
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        } catch (e: Exception) {
            Log.e("Dee", "TTS onInit error", e)
        }
    }

    override fun onDestroy() {
        try {
            speechRecognizer?.destroy()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e("Dee", "onDestroy error", e)
        }
        super.onDestroy()
    }
}
