package com.ccompile.lite

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val PREF_NAME = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_CACHED_ACTIVE = "cached_active"
    private const val KEY_CACHED_MESSAGE = "cached_message"
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000 // 24 hours

    data class Result(val active: Boolean, val message: String)

    /**
     * Checks the remote license JSON.
     * Returns cached result if last check was under 24 hours ago.
     * On network failure, defaults to active = true (fail-open) to avoid
     * blocking users with no internet.
     */
    suspend fun check(context: Context, forceRefresh: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
            val now = System.currentTimeMillis()

            if (!forceRefresh && now - lastCheck < INTERVAL_MS) {
                val cachedActive = prefs.getBoolean(KEY_CACHED_ACTIVE, true)
                val cachedMessage = prefs.getString(KEY_CACHED_MESSAGE, "") ?: ""
                return@withContext Result(cachedActive, cachedMessage)
            }

            return@withContext try {
                val licenseUrl = context.getString(R.string.license_url)
                val conn = URL(licenseUrl).openConnection() as HttpURLConnection
                conn.apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    requestMethod = "GET"
                    setRequestProperty("Cache-Control", "no-cache")
                }

                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    return@withContext Result(true, "")
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(body)
                val active = json.optBoolean("active", true)
                val message = json.optString("message", "")

                prefs.edit()
                    .putLong(KEY_LAST_CHECK, now)
                    .putBoolean(KEY_CACHED_ACTIVE, active)
                    .putString(KEY_CACHED_MESSAGE, message)
                    .apply()

                Result(active, message)
            } catch (e: Exception) {
                // Network unavailable or timeout — fail-open
                Result(true, "")
            }
        }
}
