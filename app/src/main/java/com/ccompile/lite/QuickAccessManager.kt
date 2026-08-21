package com.ccompile.lite

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Mengelola folder "Recent" dan "Pinned" via SharedPreferences.
 */
object QuickAccessManager {

    private const val PREFS_NAME = "quick_access_prefs"
    private const val KEY_RECENT = "recent_folders"
    private const val KEY_PINNED = "pinned_folders"
    private const val MAX_RECENT = 5

    // ─── Recent ───────────────────────────────────────────────

    fun addRecent(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = mutableListOf<String>()
        val json = prefs.getString(KEY_RECENT, null)
        if (json != null) {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) list.add(arr.getString(i))
        }
        list.remove(path)
        list.add(0, path)
        while (list.size > MAX_RECENT) list.removeAt(list.size - 1)

        prefs.edit().putString(KEY_RECENT, JSONArray(list).toString()).apply()
    }

    fun getRecent(context: Context): List<File> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        val result = mutableListOf<File>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val f = File(arr.getString(i))
                if (f.exists() && f.isDirectory) result.add(f)
            }
        } catch (_: Exception) {}
        return result
    }

    // ─── Pinned ───────────────────────────────────────────────

    fun togglePin(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = mutableListOf<String>()
        val json = prefs.getString(KEY_PINNED, null)
        if (json != null) {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) list.add(arr.getString(i))
        }
        if (list.contains(path)) list.remove(path) else list.add(0, path)
        prefs.edit().putString(KEY_PINNED, JSONArray(list).toString()).apply()
    }

    fun isPinned(context: Context, path: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PINNED, null) ?: return false
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                if (arr.getString(i) == path) return true
            }
        } catch (_: Exception) {}
        return false
    }

    fun getPinned(context: Context): List<File> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PINNED, null) ?: return emptyList()
        val result = mutableListOf<File>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val f = File(arr.getString(i))
                if (f.exists() && f.isDirectory) result.add(f)
            }
        } catch (_: Exception) {}
        return result
    }
}