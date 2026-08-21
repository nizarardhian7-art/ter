package com.ccompile.lite

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.ccompile.lite.databinding.FragmentToolkitSetupBinding
import com.ccompile.lite.termux.TermuxSessionManager
import com.ccompile.lite.termux.ToolkitInstaller
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ToolkitSetupFragment : Fragment() {

    private var _binding: FragmentToolkitSetupBinding? = null
    private val binding get() = _binding!!

    private lateinit var installer: ToolkitInstaller

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentToolkitSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        installer = ToolkitInstaller(requireContext())

        binding.btnInstallToolkit.setOnClickListener {
            installToolkit()
        }

        binding.btnImportToolkit.setOnClickListener {
            Toast.makeText(requireContext(), "Please use the Import button in the main Settings menu", Toast.LENGTH_SHORT).show()
        }

        binding.btnRemoveToolkit.setOnClickListener {
            removeToolkit()
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        if (_binding == null) return

        if (installer.isInstalled()) {
            binding.tvToolkitStatus.text = "Environment Ready ✓"
            binding.tvToolkitStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.log_success)
            )
            binding.tvToolkitSize.text = formatSize(installer.getInstalledSize())
            binding.btnInstallToolkit.isEnabled = false
            binding.btnImportToolkit.isEnabled = true
            binding.btnRemoveToolkit.isEnabled = true
            binding.progressToolkit.visibility = View.GONE
        } else {
            binding.tvToolkitStatus.text = "Not Configured"
            binding.tvToolkitStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.log_error)
            )
            binding.tvToolkitSize.text = "—"
            binding.btnInstallToolkit.isEnabled = true
            binding.btnImportToolkit.isEnabled = true
            binding.btnRemoveToolkit.isEnabled = false
            binding.progressToolkit.visibility = View.GONE
        }
    }

    private fun installToolkit() {
        TermuxSessionManager.sendRaw("termux-build setup\n")
        requireActivity().findViewById<ViewPager2>(R.id.viewPager)?.setCurrentItem(1, true)
        Toast.makeText(requireContext(), "Initializing Toolkit in terminal...", Toast.LENGTH_SHORT).show()
    }

    private fun removeToolkit() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Uninstall Build Tools?")
            .setMessage("The Build Toolkit (~150MB) will be removed. You will need to download or import it again to compile projects.")
            .setPositiveButton("Uninstall") { _, _ ->
                installer.uninstall()
                Toast.makeText(requireContext(), "Toolkit uninstalled", Toast.LENGTH_SHORT).show()
                updateStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}