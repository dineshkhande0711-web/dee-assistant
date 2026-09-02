package com.yourname.deeassistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the Python backend. BASE_URL, DEVICE_ID and DEVICE_TOKEN must
 * match what you configured server-side (see backend/main.py).
 *
 * IMPORTANT: change BASE_URL to your backend's actual reachable address
 * (e.g. your machine's LAN IP if testing locally: "http://192.168.1.x:8000",
 * or a real domain if you deploy it). "localhost" refers to the PHONE
 * itself, not your PyCharm machine.
 */
object NetworkClient {

    private const val BASE_URL = "http://192.168.1.100:8000"
    private const val DEVICE_ID = "my-phone"          // "friend-phone" on their build
    private const val DEVICE_TOKEN = "change-me-1"    // must match backend DEVICE_TOKENS

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    interface Callback {
        fun onResult(response: JSONObject)
        fun onError(error: String)
    }

    fun sendCommand(text: String, callback: Callback) {
        val body = JSONObject().apply {
            put("text", text)
            put("device_id", DEVICE_ID)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$BASE_URL/command")
            .addHeader("Authorization", "Bearer $DEVICE_TOKEN")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback.onError(e.message ?: "Network error")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val bodyStr = response.body?.string()
                if (!response.isSuccessful || bodyStr == null) {
                    callback.onError("Server error: ${response.code}")
                    return
                }
                try {
                    callback.onResult(JSONObject(bodyStr))
                } catch (e: Exception) {
                    callback.onError("Bad response: ${e.message}")
                }
            }
        })
    }

    /** Call once on app startup so the backend can resolve app names -> package names. */
    fun registerInstalledApps(apps: Map<String, String>, callback: Callback) {
        val appsJson = JSONObject()
        apps.forEach { (name, pkg) -> appsJson.put(name, pkg) }

        val body = JSONObject().apply {
            put("device_id", DEVICE_ID)
            put("apps", appsJson)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$BASE_URL/register_apps")
            .addHeader("Authorization", "Bearer $DEVICE_TOKEN")
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
