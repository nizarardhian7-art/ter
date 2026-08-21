package com.ccompile.lite

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.ccompile.lite.databinding.FragmentProfileBinding
import com.ccompile.lite.termux.TermuxSessionManager
import com.ccompile.lite.termux.ToolkitInstaller
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProfileFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var toolkitInstaller: ToolkitInstaller
    private var pendingImportTarget: String? = null

    private val pickZipLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri -> copyUriThenProcess(uri) }
            }
        }

    private val pickToolkitLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri -> importToolkitArchive(uri) }
            }
        }

    private fun copyUriThenProcess(uri: Uri) {
        pendingImportTarget = "ndk"
        showInstallingState("Copying file...")
        lifecycleScope.launch(Dispatchers.IO) {
            val cacheFile = File(requireContext().cacheDir, "imported-${System.currentTimeMillis()}.bin")
            try {
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot read file")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    Toast.makeText(requireContext(), "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
                    pendingImportTarget = null
                    resetInstallingState()
                }
                return@launch
            }
            withContext(Dispatchers.Main) { 
                if (isAdded) startExtractService(cacheFile) 
            }
        }
    }

    private fun startExtractService(file: File) {
        val destDir = File(requireContext().filesDir, "tools/ndk")
        val intent = Intent(requireContext(), ArchiveExtractorService::class.java).apply {
            putExtra("source_file", file.absolutePath)
            putExtra("dest_dir", destDir.absolutePath)
            putExtra("delete_source", true)
            putExtra("title", "Installing NDK...")
        }
        requireContext().startService(intent)
    }

    private fun importToolkitArchive(uri: Uri) {
        val tk = binding.toolkitSection
        tk.tvToolkitStatus.text = "Copying local package..."
        tk.progressToolkit.visibility = View.VISIBLE
        tk.progressToolkit.isIndeterminate = true

        lifecycleScope.launch(Dispatchers.IO) {
            val cacheFile = File(requireContext().cacheDir, "imported-backup.zip")
            try {
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot read file")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    Toast.makeText(requireContext(), "Failed to copy package file", Toast.LENGTH_SHORT).show()
                    refreshToolkitStatus()
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                tk.progressToolkit.visibility = View.GONE
                
                val quoted = "'" + cacheFile.absolutePath.replace("'", "'\\''") + "'"
                TermuxSessionManager.sendRaw("termux-build restore $quoted\n")
                
                requireActivity().findViewById<ViewPager2>(R.id.viewPager)?.setCurrentItem(1, true)
                Toast.makeText(requireContext(), "Configuring environment in terminal...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setOnApplyWindowInsetsListener { v, insets ->
            val topInset = insets.systemWindowInsetTop
            v.setPadding(v.paddingLeft, topInset, v.paddingRight, v.paddingBottom)
            insets
        }

        val versionName = try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) { "—" }
        binding.tvVersion.text = versionName

        checkNdkStatus()

        binding.btnInstallNdk.setOnClickListener {
            pickZipLauncher.launch(
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/x-tar",
                        "application/x-gzip",
                        "application/x-xz",
                        "application/gzip",
                        "application/zip",
                        "application/octet-stream"
                    ))
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                }
            )
        }

        binding.btnResetNdk.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.remove_ndk_confirm_title))
                .setMessage(getString(R.string.remove_ndk_confirm_msg))
                .setPositiveButton(getString(R.string.yes)) { _, _ -> removeNdk() }
                .setNegativeButton(getString(R.string.no), null)
                .show()
        }

        binding.rowTelegram.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.telegram_url))))
        }

        binding.rowCheckUpdate.setOnClickListener {
            checkUpdateManually()
        }

        val terminalPrefs = requireContext().getSharedPreferences("terminal_prefs", android.content.Context.MODE_PRIVATE)
        binding.switchExtraRow3.isChecked = terminalPrefs.getBoolean(DashboardFragment.KEY_EXTRA_ROW3, false)
        binding.switchExtraRow3.setOnCheckedChangeListener { _, isChecked ->
            terminalPrefs.edit().putBoolean(DashboardFragment.KEY_EXTRA_ROW3, isChecked).apply()
        }

        toolkitInstaller = ToolkitInstaller(requireContext())
        setupToolkitSection()

        viewModel.installProgress.observe(viewLifecycleOwner) { msg ->
            if (msg == null) return@observe
            showInstallingState(msg)
        }

        viewModel.installPercent.observe(viewLifecycleOwner) { percent ->
            val active = viewModel.installProgress.value != null || ArchiveExtractorService.isRunning
            if (!active) return@observe
            binding.progressBar.isIndeterminate = percent <= 0
            if (percent > 0) binding.progressBar.progress = percent
        }

        viewModel.installFinished.observe(viewLifecycleOwner) { success ->
            if (success == null) return@observe
            viewModel.clearInstallFinished()
            pendingImportTarget = null
            
            if (!success) {
                Toast.makeText(requireContext(), "Extraction failed. Check logs.", Toast.LENGTH_LONG).show()
            }
            checkNdkStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        checkNdkStatus()
        if (::toolkitInstaller.isInitialized) refreshToolkitStatus()
    }

    private fun setupToolkitSection() {
        val tk = binding.toolkitSection

        tk.btnInstallToolkit.setOnClickListener {
            TermuxSessionManager.sendRaw("termux-build setup\n")
            requireActivity().findViewById<ViewPager2>(R.id.viewPager)?.setCurrentItem(1, true)
            Toast.makeText(requireContext(), "Initializing Toolkit in terminal...", Toast.LENGTH_SHORT).show()
        }

        tk.btnImportToolkit.setOnClickListener {
            pickToolkitLauncher.launch(
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/x-tar",
                        "application/x-gzip",
                        "application/x-xz",
                        "application/gzip",
                        "application/zip",
                        "application/octet-stream"
                    ))
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                }
            )
        }

        tk.btnRemoveToolkit.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Uninstall Build Tools?")
                .setMessage("The Build Toolkit will be removed. You will need to download or import it again for compiling projects.")
                .setPositiveButton("Uninstall") { _, _ ->
                    toolkitInstaller.uninstall()
                    Toast.makeText(requireContext(), "Toolkit uninstalled", Toast.LENGTH_SHORT).show()
                    refreshToolkitStatus()
                }
                .setNegativeButton(getString(R.string.no), null)
                .show()
        }

        refreshToolkitStatus()
    }

    private fun refreshToolkitStatus() {
        if (_binding == null) return
        val tk = binding.toolkitSection
        val installed = toolkitInstaller.isInstalled()

        tk.btnInstallToolkit.isEnabled = !installed
        tk.btnImportToolkit.isEnabled = true
        tk.btnRemoveToolkit.isEnabled = installed
        tk.progressToolkit.visibility = View.GONE

        if (installed) {
            tk.tvToolkitStatus.text = "Environment Ready ✓"
            tk.tvToolkitStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.log_success)
            )
            tk.tvToolkitSize.text = formatSize(toolkitInstaller.getInstalledSize())
        } else {
            tk.tvToolkitStatus.text = "Not Configured"
            tk.tvToolkitStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.log_error)
            )
            tk.tvToolkitSize.text = "—"
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun showInstallingState(msg: String) {
        if (_binding == null) return
        binding.progressBar.visibility = View.VISIBLE
        binding.tvNdkStatus.text = "Installing..."
        binding.tvNdkStatus.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.log_info)
        )
        setStatusDot(R.color.log_info)
        binding.btnInstallNdk.isEnabled = false
        binding.btnResetNdk.isEnabled = false
    }

    private fun resetInstallingState() {
        if (_binding == null) return
        binding.progressBar.visibility = View.GONE
        binding.btnInstallNdk.isEnabled = true
        binding.btnResetNdk.isEnabled = true
        checkNdkStatus()
    }

    private fun removeNdk() {
        val ndkRoot = File(requireContext().filesDir, "tools/ndk")
        val legacyRoot = File(requireContext().filesDir, "home/android-sdk/ndk")
        listOf(ndkRoot, legacyRoot).forEach { dir ->
            if (dir.exists()) {
                try {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "chmod -R 777 '${dir.absolutePath}'")).waitFor()
                } catch (_: Exception) {}
                dir.deleteRecursively()
            }
        }
        Toast.makeText(requireContext(), getString(R.string.ndk_removed), Toast.LENGTH_SHORT).show()
        checkNdkStatus()
    }

    private fun checkNdkStatus() {
        if (_binding == null) return

        if (ArchiveExtractorService.isRunning && pendingImportTarget == "ndk") {
            val msg = viewModel.installProgress.value ?: "Extracting..."
            showInstallingState(msg)
            return
        }

        binding.progressBar.visibility = View.GONE
        lifecycleScope.launch(Dispatchers.IO) {
            val ndkDirs = listOf(
                File(requireContext().filesDir, "tools/ndk"),
                File(requireContext().filesDir, "home/android-sdk/ndk")
            )
            var ndkBuildFile: File? = null
            for (dir in ndkDirs) {
                if (dir.exists()) {
                    val found = dir.walkTopDown().firstOrNull {
                        it.name == "ndk-build" || (it.isFile && it.name == "source.properties" && it.readText().contains("Pkg.Revision"))
                    }
                    if (found != null) {
                        ndkBuildFile = found
                        break
                    }
                }
            }

            val exists = ndkBuildFile != null && ndkBuildFile.exists()
            var ndkVersion = ""
            
            if (exists && ndkBuildFile != null) {
                val propsFile = if (ndkBuildFile.name == "source.properties") ndkBuildFile
                    else ndkBuildFile.parentFile?.let { File(it, "source.properties") }
                val propsText = propsFile?.takeIf { it.exists() }?.readText() ?: ""
                ndkVersion = Regex("""Pkg\.Revision\s*=\s*([^\r\n]+)""")
                    .find(propsText)?.groupValues?.get(1)?.trim().orEmpty()
                if (ndkVersion.isEmpty()) {
                    ndkVersion = ndkBuildFile.parentFile?.name ?: ""
                }
            }

            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                binding.btnInstallNdk.isEnabled = !exists
                binding.btnResetNdk.isEnabled = exists

                if (exists) {
                    binding.tvNdkStatus.text = if (ndkVersion.isNotBlank())
                        "NDK $ndkVersion ✓"
                    else
                        "NDK Installed ✓"
                    binding.tvNdkStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.log_success))
                    setStatusDot(R.color.log_success)
                } else {
                    binding.tvNdkStatus.text = "Not Installed"
                    binding.tvNdkStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.log_error))
                    setStatusDot(R.color.log_error)
                }
            }
        }
    }

    private fun setStatusDot(@androidx.annotation.ColorRes colorRes: Int) {
        val drawable = binding.viewStatusDot.background?.mutate()
        drawable?.setTint(ContextCompat.getColor(requireContext(), colorRes))
        binding.viewStatusDot.background = drawable
    }

    private fun checkUpdateManually() {
        binding.tvCheckUpdate.text = "Checking..."
        binding.progressUpdate.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = UpdateChecker.check(requireContext(), forceRefresh = true)
            if (!isAdded || _binding == null) return@launch

            binding.progressUpdate.visibility = View.GONE
            binding.tvCheckUpdate.text = "Check for Updates"

            if (!result.active) {
                val message = result.message.ifBlank { "A new version is available." }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.update_available_title))
                    .setMessage(message)
                    .setPositiveButton("Open Community") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse(getString(R.string.telegram_url))))
                    }
                    .setNegativeButton(getString(R.string.update_later), null)
                    .show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.update_up_to_date), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}