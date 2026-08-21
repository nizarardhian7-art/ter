package com.ccompile.lite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.ccompile.lite.databinding.FragmentDashboardBinding
import com.ccompile.lite.termux.TermuxSessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class DashboardFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private var terminalView: TerminalView? = null
    private var currentTextSize = 24
    private var textSizeAccum = 24f

    companion object {
        private const val PREFS_TERMINAL = "terminal_prefs"
        private const val KEY_FONT_SIZE = "font_size"
        const val KEY_EXTRA_ROW3 = "extra_keys_row3_visible"
    }

    private fun loadExtraKeysPrefs() {
        try {
            // FIX: Context.MODE_PRIVATE (sebelumnya typo kurang underscore)
            val prefs = requireContext().getSharedPreferences(PREFS_TERMINAL, Context.MODE_PRIVATE)
            val showRow3 = prefs.getBoolean(KEY_EXTRA_ROW3, false)
            binding.extraKeysRow3.visibility = if (showRow3) View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }

    fun refreshExtraKeysVisibility() {
        if (_binding == null) return
        loadExtraKeysPrefs()
    }

    private var ctrlPressed = false
    private var altPressed = false

    private var lastScanTime = 0L
    private var lastSuggestedPkg = ""
    private var lastSuggestTime = 0L
    private val missingCmdRegex = Regex("""([A-Za-z0-9_+.-]+): (?:command )?not found""")
    private val aptMissingRegex = Regex("""Unable to locate package ([A-Za-z0-9_+.-]+)""")
    private val pkgMap = mapOf(
        "python3" to "python", "python" to "python", "pip" to "python", "pip3" to "python",
        "node" to "nodejs", "nodejs" to "nodejs",
        "php" to "php", "lua" to "lua", "perl" to "perl", "ruby" to "ruby",
        "clang" to "clang", "clang++" to "clang", "gcc" to "clang", "g++" to "clang", "cc" to "clang",
        "make" to "make", "cmake" to "cmake", "ninja" to "ninja",
        "git" to "git", "curl" to "curl", "wget" to "wget",
        "zip" to "zip", "unzip" to "zip", "7z" to "p7zip", "7za" to "p7zip",
        "nano" to "nano", "vim" to "vim", "vi" to "vim",
        "ssh" to "openssh", "ffmpeg" to "ffmpeg", "jq" to "jq",
        "rsync" to "rsync", "gzip" to "gzip", "xz" to "xz-utils", "tar" to "tar"
    )

    private val terminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            textSizeAccum = (textSizeAccum * scale).coerceIn(8f, 48f)
            val newSize = textSizeAccum.toInt()
            if (newSize != currentTextSize) {
                currentTextSize = newSize
                terminalView?.setTextSize(newSize)
                saveFontSize()
            }
            return 1.0f
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            terminalView?.let {
                it.requestFocus()
                if (isAdded) {
                    val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }

        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(e: MotionEvent?) = false

        override fun readControlKey() = ctrlPressed
        override fun readAltKey() = altPressed
        override fun readShiftKey() = false
        override fun readFnKey() = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
        override fun onEmulatorSet() {}

        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView?.onScreenUpdated()
            maybeDetectMissingPackage(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            val info = TermuxSessionManager.getActiveSessionInfo()
            if (info != null) {
                val title = changedSession.getTitle() ?: getString(R.string.terminal_title)
                info.title = title
                updateSessionTitle()
            }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            if (_binding == null || !isAdded) return
            val ctx = context ?: return
            val closingId = TermuxSessionManager.getActiveSessionInfo()?.id ?: return
            if (finishedSession != TermuxSessionManager.getActiveSession()) return

            TermuxSessionManager.closeSession(ctx, closingId) {
                if (_binding == null) return@closeSession
                attachTerminalView()
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            val ctx = context ?: return
            if (!text.isNullOrBlank()) {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Copy", text))
                Toast.makeText(ctx, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
            }
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val ctx = context ?: return
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(ctx).toString()
                if (text.isNotEmpty()) {
                    session?.write(text)
                }
            }
        }

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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(v.paddingLeft, insets.systemWindowInsetTop, v.paddingRight, v.paddingBottom)
            insets
        }
        binding.root.requestApplyInsets()

        TermuxSessionManager.setActiveViewClient(sessionClient)

        loadFontSize()
        setupSessionBar()
        setupExtraKeys()
        attachTerminalView()
        updateSessionTitle()

        viewModel.sessionChanged.observe(viewLifecycleOwner) {
            attachTerminalView()
        }
    }

    private fun setupSessionBar() {
        binding.btnNewSession.setOnClickListener {
            val ctx = requireContext()
            TermuxSessionManager.createSessionAsync(ctx) { session ->
                if (_binding == null) return@createSessionAsync
                if (session != null) {
                    attachTerminalView()
                    updateSessionTitle()
                }
            }
        }

        binding.btnCloseSession.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val info = TermuxSessionManager.getActiveSessionInfo()
            if (info != null) {
                TermuxSessionManager.closeSession(ctx, info.id) {
                    if (_binding == null) return@closeSession
                    attachTerminalView()
                    updateSessionTitle()
                }
            }
        }

        binding.btnSessionList.setOnClickListener {
            showSessionListDialog()
        }
    }

    private fun showSessionListDialog() {
        val sessions = TermuxSessionManager.getSessionInfoList()
        if (sessions.isEmpty()) return

        val items = sessions.mapIndexed { index, info ->
            "${index + 1}. ${info.title}${if (info.id == TermuxSessionManager.getActiveSessionInfo()?.id) " (active)" else ""}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Active Sessions")
            .setItems(items) { _, which ->
                val target = sessions[which]
                if (target.id != TermuxSessionManager.getActiveSessionInfo()?.id) {
                    TermuxSessionManager.switchSession(target.id)
                    attachTerminalView()
                    updateSessionTitle()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun updateSessionTitle() {
        if (_binding == null) return
        val info = TermuxSessionManager.getActiveSessionInfo()
        binding.tvSessionTitle.text = info?.title ?: getString(R.string.terminal_title)
    }

    private fun setupExtraKeys() {
        binding.btnEsc.setOnClickListener {
            TermuxSessionManager.sendRaw("\u001b")
            refocusTerminal()
        }

        binding.btnCtrl.setOnClickListener {
            ctrlPressed = !ctrlPressed
            applyModifierVisualState(binding.btnCtrl, ctrlPressed, "Ctrl")
            binding.btnCtrl.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }

        binding.btnAlt.setOnClickListener {
            altPressed = !altPressed
            applyModifierVisualState(binding.btnAlt, altPressed, "Alt")
            binding.btnAlt.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }

        binding.btnTab.setOnClickListener {
            TermuxSessionManager.sendRaw("\t")
            refocusTerminal()
        }

        binding.btnUp.setOnClickListener { sendKeyWithModifier("\u001b[A") }
        binding.btnDown.setOnClickListener { sendKeyWithModifier("\u001b[B") }
        binding.btnLeft.setOnClickListener { sendKeyWithModifier("\u001b[D") }
        binding.btnRight.setOnClickListener { sendKeyWithModifier("\u001b[C") }

        binding.btnEnter.setOnClickListener {
            TermuxSessionManager.sendRaw("\r")
            refocusTerminal()
        }

        binding.btnStop.setOnClickListener {
            TermuxSessionManager.sendRaw("\u0003")
            refocusTerminal()
        }

        binding.btnStop.setOnLongClickListener {
            TermuxSessionManager.sendRaw("\u001a")
            refocusTerminal()
            true
        }

        binding.btnCopyIcon.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val manualSelection = terminalView?.getSelectedText()
            val textToCopy = if (!manualSelection.isNullOrBlank()) {
                manualSelection
            } else {
                val session = TermuxSessionManager.getActiveSession()
                val emulator = session?.emulator
                val screen = emulator?.screen
                if (emulator != null && screen != null) {
                    try {
                        screen.getSelectedText(0, 0, emulator.mColumns, emulator.mRows - 1)
                    } catch (e: Exception) { null }
                } else null
            }
            if (!textToCopy.isNullOrBlank()) {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Text", textToCopy))
                Toast.makeText(ctx, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, getString(R.string.no_text_selected), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearIcon.setOnClickListener {
            val session = TermuxSessionManager.getActiveSession()
            if (session != null) {
                TermuxSessionManager.sendRaw("clear\n")
                refocusTerminal()
            } else {
                attachTerminalView()
            }
        }

        binding.btnTilde.setOnClickListener { TermuxSessionManager.sendRaw("~"); refocusTerminal() }
        binding.btnPipe.setOnClickListener { TermuxSessionManager.sendRaw("|"); refocusTerminal() }
        binding.btnSlash.setOnClickListener { TermuxSessionManager.sendRaw("/"); refocusTerminal() }
        binding.btnDash.setOnClickListener { TermuxSessionManager.sendRaw("-"); refocusTerminal() }
        binding.btnUnderscore.setOnClickListener { TermuxSessionManager.sendRaw("_"); refocusTerminal() }
        binding.btnDollar.setOnClickListener { TermuxSessionManager.sendRaw("$"); refocusTerminal() }
        binding.btnBraceOpen.setOnClickListener { TermuxSessionManager.sendRaw("{"); refocusTerminal() }
        binding.btnBraceClose.setOnClickListener { TermuxSessionManager.sendRaw("}"); refocusTerminal() }
        binding.btnBracketOpen.setOnClickListener { TermuxSessionManager.sendRaw("["); refocusTerminal() }
        binding.btnBracketClose.setOnClickListener { TermuxSessionManager.sendRaw("]"); refocusTerminal() }
        binding.btnQuote.setOnClickListener { TermuxSessionManager.sendRaw("\""); refocusTerminal() }

        loadExtraKeysPrefs()
    }

    private fun refocusTerminal() {
        terminalView?.let { tv ->
            tv.requestFocus()
            tv.post {
                if (isAdded) {
                    val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    private fun sendKeyWithModifier(sequence: String) {
        val finalSeq = if (ctrlPressed || altPressed) {
            val base = when (sequence) {
                "\u001b[A" -> "A"
                "\u001b[B" -> "B"
                "\u001b[C" -> "C"
                "\u001b[D" -> "D"
                else -> return
            }
            val mod = if (ctrlPressed) 5 else 3
            "\u001b[1;${mod}${base}"
        } else {
            sequence
        }
        TermuxSessionManager.sendRaw(finalSeq)
        refocusTerminal()
    }

    private fun maybeDetectMissingPackage(session: TerminalSession) {
        val now = System.currentTimeMillis()
        if (now - lastScanTime < 400) return
        lastScanTime = now
        val emulator = session.emulator ?: return
        val screen = emulator.screen ?: return
        val rows = emulator.mRows
        val cols = emulator.mColumns
        val tail = try {
            screen.getSelectedText(0, maxOf(0, rows - 3), cols, rows - 1)
        } catch (e: Exception) { return }
        if (tail.isEmpty()) return
        if (!tail.contains("not found") && !tail.contains("Unable to locate")) return
        val cmd = aptMissingRegex.find(tail)?.groupValues?.get(1)
            ?: missingCmdRegex.find(tail)?.groupValues?.get(1) ?: return
        if (cmd == lastSuggestedPkg && now - lastSuggestTime < 60_000) return
        lastSuggestedPkg = cmd; lastSuggestTime = now
        val pkg = pkgMap[cmd] ?: cmd
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Missing Dependency")
            .setMessage("Command '$cmd' not found. Install package '$pkg' now?")
            .setPositiveButton("Install") { _, _ -> TermuxSessionManager.sendRaw("pkg install -y $pkg\n") }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun applyModifierVisualState(button: MaterialButton, active: Boolean, label: String) {
        button.isSelected = active
        button.text = label
        button.strokeWidth = if (active) dpToPx(1.5f) else 0
    }

    private fun dpToPx(dp: Float): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun attachTerminalView() {
        if (_binding == null || !isAdded) return
        val ctx = context ?: return

        val installer = com.ccompile.lite.termux.BootstrapInstaller(ctx)

        if (!installer.isInstalled()) {
            showSetupProgress(true)
            installer.ensureReadyAsync { ok ->
                if (_binding == null || !isAdded) return@ensureReadyAsync
                showSetupProgress(false)
                if (ok) {
                    attachTerminalView()
                }
            }
            return
        }

        val container = binding.terminalContainer
        val existingSession = TermuxSessionManager.getActiveSession()

        if (existingSession != null) {
            attachSessionToView(ctx, container, existingSession)
        } else {
            showSetupProgress(true)
            TermuxSessionManager.createSessionAsync(ctx) { session ->
                if (_binding == null || !isAdded) return@createSessionAsync
                showSetupProgress(false)
                if (session != null) {
                    attachSessionToView(ctx, container, session)
                } else {
                    try {
                        val fallbackSession = TermuxSessionManager.createSession(ctx)
                        attachSessionToView(ctx, container, fallbackSession)
                    } catch (e: Exception) {}
                }
            }
        }
    }

    private fun attachSessionToView(ctx: Context, container: FrameLayout, session: TerminalSession) {
        if (_binding == null) return

        session.updateTerminalSessionClient(sessionClient)
        TermuxSessionManager.setActiveViewClient(sessionClient)

        if (terminalView != null && container.childCount > 0 && terminalView?.currentSession == session) {
            terminalView?.requestFocus()
            updateSessionTitle()
            return
        }

        container.removeAllViews()
        val tv = TerminalView(ctx, null).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setTextSize(currentTextSize)
            keepScreenOn = true
            isFocusable = true
            isFocusableInTouchMode = true
            setTerminalViewClient(terminalViewClient)
        }
        container.addView(tv)
        terminalView = tv
        tv.attachSession(session)
        tv.requestFocus()
        updateSessionTitle()
    }

    private fun showSetupProgress(show: Boolean) {
        if (_binding == null) return
        binding.setupProgressContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadFontSize() {
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_TERMINAL, Context.MODE_PRIVATE)
            val saved = prefs.getInt(KEY_FONT_SIZE, 24)
            currentTextSize = saved.coerceIn(8, 48)
            textSizeAccum = currentTextSize.toFloat()
        } catch (_: Exception) {}
    }

    private fun saveFontSize() {
        try {
            requireContext().getSharedPreferences(PREFS_TERMINAL, Context.MODE_PRIVATE)
                .edit().putInt(KEY_FONT_SIZE, currentTextSize).apply()
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        attachTerminalView()
        terminalView?.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        TermuxSessionManager.setActiveViewClient(null)

        terminalView?.let { tv ->
            try { tv.attachSession(null) } catch (_: Exception) {}
            (tv.parent as? ViewGroup)?.removeView(tv)
        }
        terminalView = null

        _binding = null
    }
}