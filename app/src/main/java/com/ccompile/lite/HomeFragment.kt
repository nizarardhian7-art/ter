package com.ccompile.lite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.ccompile.lite.databinding.FragmentHomeBinding
import com.ccompile.lite.termux.BootstrapInstaller
import com.ccompile.lite.termux.TermuxSessionManager
import com.ccompile.lite.termux.ToolkitInstaller
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MyAdapter
    private var currentDir: File = Environment.getExternalStorageDirectory()

    private var sortMode = SortMode.NAME_ASC
    private var showHiddenFiles = false
    private var currentProjectDir: File? = null

    private enum class SortMode {
        NAME_ASC, NAME_DESC, SIZE_DESC, SIZE_ASC, DATE_DESC, DATE_ASC, TYPE
    }

    companion object {
        private const val PREFS_EXPLORER = "explorer_prefs"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_SHOW_HIDDEN = "show_hidden"
    }

    private val baseProjectDir: File
        get() = File(Environment.getExternalStorageDirectory(), "CProject")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true) {
            ensureBaseProjectDir()
            loadDirectory(currentDir)
        } else {
            showToast(getString(R.string.permission_denied))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setOnApplyWindowInsetsListener { v, insets ->
            val topInset = insets.systemWindowInsetTop
            v.setPadding(v.paddingLeft, topInset, v.paddingRight, v.paddingBottom)
            insets
        }

        loadExplorerPrefs()
        binding.toolbar.inflateMenu(R.menu.menu_home)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_new_folder -> { createNewFolder(); true }
                R.id.action_sort -> { showSortDialog(); true }
                R.id.action_toggle_hidden -> { toggleHiddenFiles(); true }
                else -> false
            }
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = MyAdapter(
            onItemClick = { file ->
                if (adapter.selectionMode) {
                    // Checkbox toggle is handled in adapter
                } else {
                    if (file.isDirectory) loadDirectory(file)
                    else handleFileClick(file)
                }
            },
            onItemLongClick = { file ->
                if (!adapter.selectionMode) {
                    showLongPressDialog(file)
                }
            }
        )
        adapter.onSelectionChanged = { updateSelectionUI() }
        binding.recyclerView.adapter = adapter

        viewModel.navigateToPath.observe(viewLifecycleOwner) { path ->
            if (path != null) {
                val targetDir = File(path)
                if (targetDir.exists()) loadDirectory(targetDir)
                viewModel.clearNavigate()
            }
        }

        binding.btnGrantPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")))
            } else {
                permissionLauncher.launch(arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ))
            }
        }

        binding.btnNewFolderEmpty.setOnClickListener { createNewFolder() }

        binding.fabBuild.setOnClickListener {
            currentProjectDir?.let { dir ->
                val pt = ProjectDetector.detect(dir)
                if (pt != null) showBuildDialog(dir, pt)
            }
        }

        binding.btnSelectAll.setOnClickListener { adapter.selectAll() }
        binding.btnDeleteSelected.setOnClickListener { deleteSelectedFiles() }
        binding.btnCancelSelection.setOnClickListener {
            adapter.exitSelectionMode()
            updateSelectionUI()
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (adapter.selectionMode) {
                        adapter.exitSelectionMode()
                        updateSelectionUI()
                        return
                    }
                    val root = Environment.getExternalStorageDirectory().absolutePath
                    if (currentDir.absolutePath != root) {
                        currentDir.parentFile?.let { loadDirectory(it) }
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun loadDirectory(dir: File) {
        currentDir = dir
        QuickAccessManager.addRecent(requireContext(), dir.absolutePath)
        updateBreadcrumb()

        if (!hasStoragePermission()) {
            binding.layoutPermission.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.layoutEmptyFolder.visibility = View.GONE
            binding.progressLoading.visibility = View.GONE
            binding.fabBuild.visibility = View.GONE
            currentProjectDir = null
            adapter.updateData(emptyList(), emptyList(), emptyList(), emptySet())
            return
        }

        if (!dir.canRead()) {
            showToast(getString(R.string.access_denied))
            binding.fabBuild.visibility = View.GONE
            currentProjectDir = null
            adapter.updateData(emptyList(), emptyList(), emptyList(), emptySet())
            return
        }

        binding.layoutPermission.visibility = View.GONE
        binding.layoutEmptyFolder.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.progressLoading.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val rawFiles = dir.listFiles()?.toList() ?: emptyList()
            val files = sortAndFilter(rawFiles)

            val sdcardRoot = Environment.getExternalStorageDirectory().absolutePath
            val isRoot = dir.absolutePath == sdcardRoot
            
            val pinned = if (isRoot) QuickAccessManager.getPinned(requireContext()) else emptyList()
            val recent = if (isRoot) {
                QuickAccessManager.getRecent(requireContext()).filter { it.absolutePath != dir.absolutePath }
            } else emptyList()
            
            val pinnedSet = pinned.map { it.absolutePath }.toSet()
            val projectType = ProjectDetector.detect(dir)

            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                binding.progressLoading.visibility = View.GONE

                if (files.isEmpty() && pinned.isEmpty() && recent.isEmpty()) {
                    binding.layoutEmptyFolder.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                    adapter.updateData(emptyList(), emptyList(), emptyList(), emptySet())
                } else {
                    binding.layoutEmptyFolder.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    adapter.updateData(pinned, recent, files, pinnedSet)
                }

                currentProjectDir = if (projectType != null) dir else null
                binding.fabBuild.visibility =
                    if (projectType != null && !adapter.selectionMode) View.VISIBLE else View.GONE

                if (projectType != null) {
                    if (projectType == ProjectType.CMAKE || projectType == ProjectType.NDK_BUILD) {
                        binding.fabBuild.text = "Build Native (${projectType.label})"
                    } else {
                        binding.fabBuild.text = "Build APK (${projectType.label})"
                    }
                }
            }
        }
    }

    private fun sortAndFilter(files: List<File>): List<File> {
        var filtered = files
        if (!showHiddenFiles) {
            filtered = filtered.filter { !it.name.startsWith(".") }
        }
        return filtered.sortedWith(
            compareBy<File> { !it.isDirectory }.then(
                when (sortMode) {
                    SortMode.NAME_ASC -> compareBy { it.name.lowercase() }
                    SortMode.NAME_DESC -> compareByDescending { it.name.lowercase() }
                    SortMode.SIZE_DESC -> compareByDescending { it.length() }
                    SortMode.SIZE_ASC -> compareBy { it.length() }
                    SortMode.DATE_DESC -> compareByDescending { it.lastModified() }
                    SortMode.DATE_ASC -> compareBy { it.lastModified() }
                    SortMode.TYPE -> compareBy { it.extension.lowercase() }
                }
            )
        )
    }

    private fun updateBreadcrumb() {
        if (!isAdded || _binding == null) return
        val container = binding.breadcrumbContainer
        container.removeAllViews()

        val root = Environment.getExternalStorageDirectory()
        val segments = mutableListOf<File>()
        var dir: File? = currentDir
        while (dir != null && dir.absolutePath.startsWith(root.absolutePath)) {
            segments.add(0, dir)
            if (dir.absolutePath == root.absolutePath) break
            dir = dir.parentFile
        }

        for ((index, segment) in segments.withIndex()) {
            val isLast = index == segments.size - 1
            val tv = android.widget.TextView(requireContext()).apply {
                text = if (segment.absolutePath == root.absolutePath) "🏠 Storage" else segment.name
                textSize = 14f
                setTextColor(
                    if (isLast) ContextCompat.getColor(requireContext(), R.color.log_default)
                    else ContextCompat.getColor(requireContext(), R.color.log_info)
                )
                setPadding(if (index == 0) 0 else dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(6))
                if (!isLast) {
                    setBackgroundResource(android.R.attr.selectableItemBackground.let { attr ->
                        val outValue = android.util.TypedValue()
                        requireContext().theme.resolveAttribute(attr, outValue, true)
                        outValue.resourceId
                    })
                    setOnClickListener { loadDirectory(segment) }
                }
            }
            container.addView(tv)
            if (!isLast) {
                val sep = android.widget.TextView(requireContext()).apply {
                    text = " › "
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.log_separator))
                }
                container.addView(sep)
            }
        }
        binding.breadcrumbScroll.post {
            binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT)
        }
    }

    // --- Helper function for beautiful Input Dialogs ---
    private fun showInputDialog(title: String, hint: String, prefill: String = "", onConfirm: (String) -> Unit) {
        val container = FrameLayout(requireContext())
        val padding = dpToPx(24)
        container.setPadding(padding, dpToPx(8), padding, dpToPx(0))

        val textInputLayout = TextInputLayout(requireContext()).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            this.hint = hint
        }
        
        val editText = TextInputEditText(requireContext()).apply {
            setSingleLine()
            setText(prefill)
            setSelection(prefill.length)
        }
        
        textInputLayout.addView(editText)
        container.addView(textInputLayout)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(container)
            .setPositiveButton(getString(R.string.btn_execute)) { _, _ ->
                onConfirm(editText.text.toString().trim())
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showLongPressDialog(file: File) {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (file.isDirectory) {
            items.add(getString(R.string.open_terminal_here))
            actions.add { openTerminalHere(file) }

            val pt = ProjectDetector.detect(file)
            if (pt != null) {
                items.add("Configure Build...")
                actions.add { showBuildDialog(file, pt) }
            }

            val pinned = QuickAccessManager.isPinned(requireContext(), file.absolutePath)
            items.add(if (pinned) getString(R.string.unpin_folder) else getString(R.string.pin_folder))
            actions.add {
                QuickAccessManager.togglePin(requireContext(), file.absolutePath)
                loadDirectory(currentDir)
            }
        }
        items.add(getString(R.string.copy_path))
        actions.add { copyPath(file) }
        items.add(getString(R.string.rename))
        actions.add { renameFile(file) }
        items.add(getString(R.string.delete))
        actions.add { deleteFile(file) }

        if (!file.isDirectory && isArchiveFile(file)) {
            items.add(getString(R.string.extract_here))
            actions.add { extractArchive(file) }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(file.name)
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showBuildDialog(projectDir: File, pt: ProjectType) {
        val isNative = pt == ProjectType.CMAKE || pt == ProjectType.NDK_BUILD
        val options = if (isNative) {
            arrayOf("Build Native (${pt.label})")
        } else {
            arrayOf("Build APK (Debug)", "Build APK (Release)")
        }

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(8), dpToPx(24), dpToPx(8))
        }
        val checkBox = MaterialCheckBox(requireContext()).apply {
            text = getString(R.string.build_clean_cache)
            textSize = 14f
        }
        layout.addView(checkBox)

        var selectedOption = 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.build_options_title))
            .setSingleChoiceItems(options, 0) { _, which -> selectedOption = which }
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_execute)) { _, _ ->
                val type = if (isNative) "native" else if (selectedOption == 0) "debug" else "release"
                val isClean = checkBox.isChecked
                startBuild(type, projectDir, isClean)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun startBuild(type: String, projectDir: File, cleanBuild: Boolean = false) {
        val ctx = requireContext()

        val bootstrap = BootstrapInstaller(ctx)
        if (!bootstrap.isInstalled()) {
            bootstrap.ensureReadyAsync { ok -> if (ok && _binding != null) startBuild(type, projectDir, cleanBuild) }
            return
        }

        val toolkit = ToolkitInstaller(ctx)
        if (!toolkit.isInstalled()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Build Environment Missing")
                .setMessage("Build Toolkit is required for compilation.\nPlease initialize it from the Settings tab.")
                .setPositiveButton("Open Settings") { _, _ ->
                    requireActivity().findViewById<ViewPager2>(R.id.viewPager)?.setCurrentItem(2, true)
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
            return
        }

        val pt = ProjectDetector.detect(projectDir)
        val quoted = "'" + projectDir.absolutePath.replace("'", "'\\''") + "'"

        var cmd = ""
        if (cleanBuild) {
            val isNative = pt == ProjectType.CMAKE || pt == ProjectType.NDK_BUILD
            val safeName = projectDir.name.replace("'", "'\\''")
            val prefix = if (isNative) "Native_" else ""
            
            cmd += "rm -rf $quoted/build $quoted/app/build $quoted/.gradle $quoted/.cxx && "
            cmd += "rm -rf ~/workspace/'$prefix$safeName' && "
        }
        
        cmd += if (pt == ProjectType.CMAKE || pt == ProjectType.NDK_BUILD) {
            "termux-build native $quoted\n"
        } else {
            "termux-build build $type $quoted\n"
        }

        TermuxSessionManager.sendRaw(cmd)
        requireActivity().findViewById<ViewPager2>(R.id.viewPager)?.setCurrentItem(1, true)
        showToast(getString(R.string.msg_build_started, projectDir.name))
    }

    private fun ensureBaseProjectDir() {
        if (hasStoragePermission() && !baseProjectDir.exists()) baseProjectDir.mkdirs()
    }

    private fun createNewFolder() {
        if (!hasStoragePermission()) {
            showToast(getString(R.string.permission_required))
            return
        }
        showInputDialog(
            title = getString(R.string.new_folder),
            hint = "Directory Name",
            prefill = ""
        ) { name ->
            if (name.isNotEmpty()) {
                val newDir = File(currentDir, name)
                if (newDir.mkdir()) {
                    ProjectDetector.invalidateAll()
                    loadDirectory(currentDir)
                    showToast(getString(R.string.msg_folder_created))
                } else {
                    showToast(getString(R.string.msg_folder_failed))
                }
            }
        }
    }

    private fun isArchiveFile(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".tar.gz") || name.endsWith(".tgz") ||
                name.endsWith(".tar.xz") || name.endsWith(".zip") || name.endsWith(".7z")
    }

    private fun openTerminalHere(directory: File) {
        if (!directory.exists() || !directory.isDirectory) return
        val ctx = requireContext()
        TermuxSessionManager.createSessionAsync(ctx, directory.absolutePath) { session ->
            if (!isAdded || _binding == null) return@createSessionAsync
            if (session != null) {
                viewModel.notifySessionChanged()
                requireActivity().findViewById<ViewPager2>(R.id.viewPager)?.setCurrentItem(1, true)
            }
        }
    }

    private fun copyPath(file: File) {
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Path", file.absolutePath))
        showToast(getString(R.string.log_copied))
    }

    private fun renameFile(file: File) {
        showInputDialog(
            title = getString(R.string.rename),
            hint = "New Name",
            prefill = file.name
        ) { newName ->
            if (newName.isNotEmpty() && newName != file.name) {
                val newFile = File(file.parent, newName)
                if (file.renameTo(newFile)) {
                    ProjectDetector.invalidateAll()
                    loadDirectory(currentDir)
                    showToast(getString(R.string.msg_renamed))
                } else {
                    showToast(getString(R.string.msg_rename_failed))
                }
            }
        }
    }

    private fun deleteFile(file: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete))
            .setMessage("Permanently delete ${file.name}?")
            .setPositiveButton(getString(R.string.btn_execute)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                    withContext(Dispatchers.Main) {
                        if (!isAdded || _binding == null) return@withContext
                        if (deleted) {
                            ProjectDetector.invalidate(file.absolutePath)
                            ProjectDetector.invalidateAll()
                            loadDirectory(currentDir)
                            showToast(getString(R.string.msg_deleted))
                        } else {
                            showToast(getString(R.string.msg_delete_failed))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun extractArchive(file: File) {
        if (!hasStoragePermission()) {
            showToast(getString(R.string.permission_required))
            return
        }
        val intent = Intent(requireContext(), ArchiveExtractorService::class.java).apply {
            putExtra("source_file", file.absolutePath)
            putExtra("dest_dir", currentDir.absolutePath)
            putExtra("delete_source", false)
            putExtra("title", "Extracting ${file.name}")
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun deleteSelectedFiles() {
        val selected = adapter.getSelectedFiles()
        if (selected.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete ${selected.size} item(s)?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton(getString(R.string.btn_execute)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    var count = 0
                    for (f in selected) {
                        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                        if (ok) count++
                    }
                    withContext(Dispatchers.Main) {
                        if (!isAdded || _binding == null) return@withContext
                        ProjectDetector.invalidateAll()
                        adapter.exitSelectionMode()
                        updateSelectionUI()
                        loadDirectory(currentDir)
                        showToast(getString(R.string.msg_deleted))
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun updateSelectionUI() {
        if (_binding == null) return
        if (adapter.selectionMode) {
            binding.selectionActionBar.visibility = View.VISIBLE
            binding.fabBuild.visibility = View.GONE
            binding.tvSelectionCount.text = "${adapter.getSelectedCount()} selected"
        } else {
            binding.selectionActionBar.visibility = View.GONE
            binding.fabBuild.visibility = if (currentProjectDir != null) View.VISIBLE else View.GONE
        }
    }

    private fun handleFileClick(file: File) {
        when {
            file.name.endsWith(".apk") -> installApk(file)
            isArchiveFile(file) -> showLongPressDialog(file)
            isRunnableScript(file) -> showRunDialog(file)
            else -> showToast(getString(R.string.opening_file_not_supported))
        }
    }

    private fun interpreterCommand(file: File): String? {
        val n = file.name.lowercase()
        return when {
            n.endsWith(".sh") || n.endsWith(".bash") -> "bash"
            n.endsWith(".py") -> "python3"
            n.endsWith(".pl") -> "perl"
            n.endsWith(".rb") -> "ruby"
            n.endsWith(".js") || n.endsWith(".mjs") -> "node"
            n.endsWith(".php") -> "php"
            n.endsWith(".lua") -> "lua"
            else -> null
        }
    }

    private fun isElfExecutable(file: File): Boolean = try {
        file.inputStream().use { s ->
            val h = ByteArray(4)
            val r = s.read(h)
            r == 4 && h[0] == 0x7F.toByte() && h[1] == 0x45.toByte() && h[2] == 0x4C.toByte() && h[3] == 0x46.toByte()
        }
    } catch (_: Exception) { false }

    private fun isRunnableScript(file: File): Boolean =
        !file.isDirectory && (interpreterCommand(file) != null || (isElfExecutable(file) && file.canExecute()))

    private fun showRunDialog(file: File) {
        val ctx = context ?: return
        val interp = interpreterCommand(file)
        val desc = if (interp != null) "Interpreter: $interp" else "Binary executable"
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Execute Script")
            .setMessage("${file.name}\n$desc\nWorking directory: ${file.parent}")
            .setPositiveButton(getString(R.string.btn_execute)) { _, _ -> runInTerminal(file, interp, false) }
            .setNeutralButton("Run in New Session") { _, _ -> runInTerminal(file, interp, true) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun runInTerminal(file: File, interp: String?, newSession: Boolean) {
        val ctx = context ?: return
        val quoted = "'" + file.name.replace("'", "'\\''") + "'"
        val cmd = if (interp != null) "$interp $quoted" else { file.setExecutable(true, false); "./$quoted" }

        if (newSession) {
            TermuxSessionManager.createSessionAsync(ctx, file.parent) { session ->
                if (!isAdded || _binding == null) return@createSessionAsync
                if (session != null) {
                    TermuxSessionManager.sendRaw("cd '${file.parent}' && $cmd\n")
                    viewModel.notifySessionChanged()
                    activity?.findViewById<ViewPager2>(R.id.viewPager)?.setCurrentItem(1, true)
                }
            }
        } else {
            val installer = BootstrapInstaller(ctx)
            if (!installer.isInstalled()) {
                installer.ensureReadyAsync { ok ->
                    if (ok && isAdded && _binding != null) {
                        TermuxSessionManager.sendRaw("cd '${file.parent}' && $cmd\n")
                        viewModel.notifySessionChanged()
                        activity?.findViewById<ViewPager2>(R.id.viewPager)?.setCurrentItem(1, true)
                    }
                }
            } else {
                TermuxSessionManager.sendRaw("cd '${file.parent}' && $cmd\n")
                viewModel.notifySessionChanged()
                activity?.findViewById<ViewPager2>(R.id.viewPager)?.setCurrentItem(1, true)
            }
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(),
                "${requireContext().packageName}.provider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            showToast(getString(R.string.install_failed, e.message))
        }
    }

    private fun showSortDialog() {
        val options = arrayOf("Name (A → Z)", "Name (Z → A)", "Size (largest)", "Size (smallest)",
            "Date (newest)", "Date (oldest)", "Type (extension)")
        val current = when (sortMode) {
            SortMode.NAME_ASC -> 0; SortMode.NAME_DESC -> 1; SortMode.SIZE_DESC -> 2
            SortMode.SIZE_ASC -> 3; SortMode.DATE_DESC -> 4; SortMode.DATE_ASC -> 5; SortMode.TYPE -> 6
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sort configuration")
            .setSingleChoiceItems(options, current) { dialog, which ->
                sortMode = when (which) {
                    0 -> SortMode.NAME_ASC; 1 -> SortMode.NAME_DESC; 2 -> SortMode.SIZE_DESC
                    3 -> SortMode.SIZE_ASC; 4 -> SortMode.DATE_DESC; 5 -> SortMode.DATE_ASC
                    else -> SortMode.TYPE
                }
                saveExplorerPrefs()
                loadDirectory(currentDir)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun toggleHiddenFiles() {
        showHiddenFiles = !showHiddenFiles
        saveExplorerPrefs()
        loadDirectory(currentDir)
    }

    private fun loadExplorerPrefs() {
        try {
            val prefs = requireContext().getSharedPreferences(PREFS_EXPLORER, android.content.Context.MODE_PRIVATE)
            sortMode = try {
                SortMode.valueOf(prefs.getString(KEY_SORT_MODE, SortMode.NAME_ASC.name) ?: SortMode.NAME_ASC.name)
            } catch (_: Exception) { SortMode.NAME_ASC }
            showHiddenFiles = prefs.getBoolean(KEY_SHOW_HIDDEN, false)
        } catch (_: Exception) {}
    }

    private fun saveExplorerPrefs() {
        try {
            requireContext().getSharedPreferences(PREFS_EXPLORER, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SORT_MODE, sortMode.name)
                .putBoolean(KEY_SHOW_HIDDEN, showHiddenFiles)
                .apply()
        } catch (_: Exception) {}
    }

    private fun hasStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(requireContext(),
            Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun showToast(message: String) {
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        ensureBaseProjectDir()
        loadDirectory(currentDir)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}