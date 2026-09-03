package com.yourname.deeassistant

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the Dee Assistant backend.
 * Base URL, device ID, and token can be configured at runtime via SharedPreferences
 * or defaults to the values below.
 */
object NetworkClient {

    private const val PREFS_NAME = "DeeAssistantPrefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_TOKEN = "device_token"

    private const val DEFAULT_BASE_URL = "https://dee-assistant-backend.onrender.com"
    private const val DEFAULT_DEVICE_ID = "my-phone"
    private const val DEFAULT_DEVICE_TOKEN = "change-me-1"

    var baseUrl: String = DEFAULT_BASE_URL
        private set
    var deviceId: String = DEFAULT_DEVICE_ID
        private set
    var deviceToken: String = DEFAULT_DEVICE_TOKEN
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    interface Callback {
        fun onResult(response: JSONObject)
        fun onError(error: String)
    }

    /**
     * Initializes configuration from SharedPreferences.
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        deviceId = prefs.getString(KEY_DEVICE_ID, DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID
        deviceToken = prefs.getString(KEY_DEVICE_TOKEN, DEFAULT_DEVICE_TOKEN) ?: DEFAULT_DEVICE_TOKEN
    }

    /**
     * Updates and persists backend connection settings.
     */
    fun saveSettings(context: Context, url: String, id: String, token: String) {
        val cleanUrl = url.trim().trimEnd('/')
        baseUrl = if (cleanUrl.isNotBlank()) cleanUrl else DEFAULT_BASE_URL
        deviceId = if (id.isNotBlank()) id.trim() else DEFAULT_DEVICE_ID
        deviceToken = if (token.isNotBlank()) token.trim() else DEFAULT_DEVICE_TOKEN

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .apply()
    }

    fun sendCommand(text: String, callback: Callback) {
        val body = JSONObject().apply {
            put("text", text)
            put("device_id", deviceId)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$baseUrl/command")
            .addHeader("Authorization", "Bearer $deviceToken")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback.onError(e.message ?: "Network error")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val bodyStr = response.body?.string()
                if (!response.isSuccessful || bodyStr == null) {
                    callback.onError("Server error (${response.code}): ${bodyStr ?: ""}")
                    return
                }
                try {
                    callback.onResult(JSONObject(bodyStr))
                } catch (e: Exception) {
                    callback.onError("Bad response format: ${e.message}")
                }
            }
        })
    }

    /** Call once on app startup so the backend can resolve app names -> package names. */
    fun registerInstalledApps(apps: Map<String, String>, callback: Callback) {
        val appsJson = JSONObject()
        apps.forEach { (name, pkg) -> appsJson.put(name, pkg) }

        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("apps", appsJson)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$baseUrl/register_apps")
            .addHeader("Authorization", "Bearer $deviceToken")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback.onError(e.message ?: "Network error")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val bodyStr = response.body?.string() ?: "{}"
                callback.onResult(JSONObject(bodyStr))
            }
        })
    }
}
