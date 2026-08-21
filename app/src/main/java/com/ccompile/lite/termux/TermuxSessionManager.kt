package com.ccompile.lite.termux

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import java.util.UUID

object TermuxSessionManager {

    data class TerminalSessionInfo(
        val id: String,
        var title: String = "Terminal",
        val cwd: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    private const val PREFS_NAME = "termux_session_state"
    private const val KEY_SESSIONS_JSON = "sessions_json"
    private const val KEY_ACTIVE_INDEX = "active_index"

    private val sessions = LinkedHashMap<String, TerminalSession>()
    private val sessionInfos = LinkedHashMap<String, TerminalSessionInfo>()
    private var activeSessionId: String? = null

    private val noopClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private var activeViewClient: TerminalSessionClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun saveState(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray()

            for (info in sessionInfos.values) {
                val obj = JSONObject()
                obj.put("title", info.title)
                obj.put("cwd", info.cwd ?: "")
                jsonArray.put(obj)
            }

            val activeIndex = if (activeSessionId != null) {
                sessionInfos.keys.toList().indexOf(activeSessionId)
            } else 0

            prefs.edit()
                .putString(KEY_SESSIONS_JSON, jsonArray.toString())
                .putInt(KEY_ACTIVE_INDEX, activeIndex.coerceAtLeast(0))
                .apply()
        } catch (_: Exception) {}
    }

    fun restoreSessions(context: Context, onDone: (() -> Unit)? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { restoreSessions(context, onDone) }
            return
        }

        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_SESSIONS_JSON, null)
            val activeIndex = prefs.getInt(KEY_ACTIVE_INDEX, 0)

            if (jsonStr.isNullOrEmpty()) {
                onDone?.invoke()
                return
            }

            val jsonArray = JSONArray(jsonStr)
            if (jsonArray.length() == 0) {
                onDone?.invoke()
                return
            }

            val installer = BootstrapInstaller(context)
            if (!installer.isInstalled()) {
                onDone?.invoke()
                return
            }

            val savedSessions = mutableListOf<Pair<String, String?>>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val title = obj.optString("title", "Terminal ${i + 1}")
                val cwd = obj.optString("cwd", "").takeIf { it.isNotBlank() }
                savedSessions.add(title to cwd)
            }

            for ((index, pair) in savedSessions.withIndex()) {
                val (title, cwd) = pair
                val session = createSessionInternal(context, cwd)
                sessionInfos[activeSessionId]?.title = title
            }

            val keys = sessions.keys.toList()
            if (activeIndex in keys.indices) {
                switchSession(keys[activeIndex])
            }

            onDone?.invoke()
        } catch (_: Exception) {
            onDone?.invoke()
        }
    }

    fun clearSavedState(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
    }

    fun getActiveSession(): TerminalSession? {
        return activeSessionId?.let { sessions[it] }
    }

    fun getActiveSessionInfo(): TerminalSessionInfo? {
        return activeSessionId?.let { sessionInfos[it] }
    }

    fun getSessionInfoList(): List<TerminalSessionInfo> {
        return sessionInfos.values.toList()
    }

    fun getOrCreateSession(context: Context, cwd: String? = null): TerminalSession {
        return getActiveSession() ?: createSession(context, cwd)
    }

    fun createSessionAsync(context: Context, cwd: String? = null, onReady: ((TerminalSession?) -> Unit)? = null) {
        val installer = BootstrapInstaller(context)

        if (!installer.isInstalled()) {
            installer.ensureReadyAsync { ok ->
                if (ok) {
                    mainHandler.post {
                        val session = createSessionInternal(context, cwd)
                        onReady?.invoke(session)
                    }
                } else {
                    mainHandler.post { onReady?.invoke(null) }
                }
            }
        } else {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                val session = createSessionInternal(context, cwd)
                onReady?.invoke(session)
            } else {
                mainHandler.post {
                    val session = createSessionInternal(context, cwd)
                    onReady?.invoke(session)
                }
            }
        }
    }

    fun createSession(context: Context, cwd: String? = null): TerminalSession {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw IllegalStateException("createSession must be called on the main thread")
        }

        val installer = BootstrapInstaller(context)
        installer.ensureReady()

        return createSessionInternal(context, cwd)
    }

    private fun createSessionInternal(context: Context, cwd: String? = null): TerminalSession {
        val installer = BootstrapInstaller(context)
        val homeDir = installer.homeDir.absolutePath
        val bashBin = File(installer.prefixDir, "bin/bash").absolutePath
        val workingDir = cwd?.takeIf { File(it).exists() } ?: homeDir

        // Bash from the Termux bootstrap has a compiled-in login profile path.
        // Suppress that path and source the relocated fork profile explicitly.
        val profilePath = File(installer.prefixDir, "etc/profile").absolutePath
        val shellCommand = "source \"$profilePath\"; exec \"$bashBin\" --noprofile --norc -i"
        val session = TerminalSession(
            bashBin,
            workingDir,
            arrayOf(bashBin, "--noprofile", "--norc", "-c", shellCommand),
            installer.buildEnv(),
            null,
            noopClient
        )

        session.initializeEmulator(80, 24, 12, 24)

        val id = UUID.randomUUID().toString()
        sessions[id] = session
        sessionInfos[id] = TerminalSessionInfo(
            id = id,
            title = "Terminal ${sessions.size}",
            cwd = workingDir
        )

        activeSessionId = id
        activeViewClient?.let { session.updateTerminalSessionClient(it) }

        saveState(context)

        return session
    }

    fun switchSession(id: String) {
        if (id == activeSessionId) return
        if (!sessions.containsKey(id)) return

        activeSessionId?.let { oldId ->
            sessions[oldId]?.updateTerminalSessionClient(noopClient)
        }

        activeSessionId = id

        val newSession = sessions[id]
        activeViewClient?.let { newSession?.updateTerminalSessionClient(it) }
    }

    fun closeSession(context: Context, id: String, onNewSessionReady: (() -> Unit)? = null) {
        val session = sessions.remove(id) ?: return
        sessionInfos.remove(id)
        session.finishIfRunning()

        if (id == activeSessionId) {
            activeSessionId = null

            if (sessions.isNotEmpty()) {
                val newId = sessions.keys.first()
                activeSessionId = newId
                activeViewClient?.let { client -> sessions[newId]?.updateTerminalSessionClient(client) }
                saveState(context)
                onNewSessionReady?.invoke()
            } else {
                try {
                    createSessionAsync(context.applicationContext) {
                        onNewSessionReady?.invoke()
                    }
                } catch (e: Exception) {
                    onNewSessionReady?.invoke()
                }
            }
        } else {
            saveState(context)
        }
    }

    fun sendRaw(text: String) {
        getActiveSession()?.write(text)
    }

    fun setActiveViewClient(client: TerminalSessionClient?) {
        activeViewClient = client
        val session = getActiveSession()
        if (session != null) {
            session.updateTerminalSessionClient(client ?: noopClient)
        }
    }

    fun getAllSessions(): List<TerminalSession> {
        return sessions.values.toList()
    }

    fun closeAllSessions() {
        sessions.values.forEach { it.finishIfRunning() }
        sessions.clear()
        sessionInfos.clear()
        activeSessionId = null
        activeViewClient = null
    }
}