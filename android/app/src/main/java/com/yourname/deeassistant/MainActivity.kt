package com.yourname.deeassistant

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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var statusText: TextView

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val micButton = findViewById<Button>(R.id.micButton)
        val enableAccessibilityButton = findViewById<Button>(R.id.enableAccessibilityButton)

        tts = TextToSpeech(this, this)

        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)

        enableAccessibilityButton.setOnClickListener { openAccessibilitySettings() }

        micButton.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(
                    this,
                    "Please enable the Accessibility Service first (button above)",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            startListening()
        }

        setupSpeechRecognizer()
        pushInstalledAppsToBackend()
    }

    // -----------------------------------------------------------------
    // One-time-per-user setup check. This is the step a friend's phone
    // MUST go through manually -- Android does not allow skipping it.
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
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull()
                if (!spokenText.isNullOrBlank()) {
                    statusText.text = "You said: $spokenText"
                    sendToBackend(spokenText)
                }
            }

            override fun onError(error: Int) {
                statusText.text = "Didn't catch that -- try again"
            }

            override fun onReadyForSpeech(params: Bundle?) { statusText.text = "Listening..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
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
                    statusText.text = "Error: $error"
                }
            }
        })
    }

    private fun handleBackendResponse(response: JSONObject) {
        when (response.optString("kind")) {
            "action" -> {
                val service = DeeAccessibilityService.instance
                if (service == null) {
                    statusText.text = "Accessibility service not running"
                    return
                }
                val result = service.executeAction(response)
                statusText.text = result
                speak(result)
            }
            "answer" -> {
                val speech = response.optString("speech", "I'm not sure.")
                statusText.text = speech
                speak(speech)
            }
            else -> statusText.text = "Unrecognized response"
        }
    }

    // -----------------------------------------------------------------
    // Installed-app registration, so the backend can resolve app names
    // ("spotify") to real package names on THIS phone.
    // -----------------------------------------------------------------
    private fun pushInstalledAppsToBackend() {
        val pm = packageManager
        val launchableApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .associate { pm.getApplicationLabel(it).toString() to it.packageName }

        NetworkClient.registerInstalledApps(launchableApps, object : NetworkClient.Callback {
            override fun onResult(response: JSONObject) { /* no-op */ }
            override fun onError(error: String) { /* silent -- retried on next launch */ }
        })
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
        speechRecognizer.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
