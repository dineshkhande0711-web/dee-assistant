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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var statusText: TextView
    private lateinit var serverInfoText: TextView
    private lateinit var enableAccessibilityButton: Button

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Microphone permission is required for voice commands", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize network client with persisted settings
        NetworkClient.init(this)

        statusText = findViewById(R.id.statusText)
        serverInfoText = findViewById(R.id.serverInfoText)
        val micButton = findViewById<Button>(R.id.micButton)
        enableAccessibilityButton = findViewById(R.id.enableAccessibilityButton)
        val settingsButton = findViewById<Button>(R.id.settingsButton)

        tts = TextToSpeech(this, this)

        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)

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
        pushInstalledAppsToBackend()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun updateUiState() {
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val urlInput = EditText(this).apply {
            hint = "Server URL (e.g. https://... or http://192.168.1.5:8000)"
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
    }

    // -----------------------------------------------------------------
    // Accessibility check
    // -----------------------------------------------------------------
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, DeeAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentName = ComponentName.unflattenFromString(colonSplitter.next())
            if (componentName != null && componentName == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // -----------------------------------------------------------------
    // Voice capture
    // -----------------------------------------------------------------
    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = "Speech recognition is not available on this device"
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
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
                statusText.text = "$message -- tap to try again"
            }

            override fun onReadyForSpeech(params: Bundle?) {
                statusText.text = "Listening... Speak now!"
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                statusText.text = "Processing speech..."
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer.startListening(intent)
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
                    statusText.text = "Backend Error:\n$error\n\nCheck Server Settings or verify backend is running."
                }
            }
        })
    }

    private fun handleBackendResponse(response: JSONObject) {
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
                        val count = response.optInt("app_count", launchableApps.size)
                        serverInfoText.text = "Server: Connected ($count apps synced)"
                    }
                }
                override fun onError(error: String) {
                    // Silently wait or update subtitle
                }
            })
        } catch (e: Exception) {
            // Ignore background error
        }
    }

    // -----------------------------------------------------------------
    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance-${System.currentTimeMillis()}")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
        }
    }

    override fun onDestroy() {
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        if (::tts.isInitialized) {
            tts.shutdown()
        }
        super.onDestroy()
    }
}
