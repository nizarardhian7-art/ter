package com.ccompile.lite

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.ccompile.lite.termux.TermuxSessionManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView

    companion object {
        const val CHANNEL_INSTALL = "termux_install_channel"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        
        applyStatusBarIconStyle()
        createNotificationChannels()
        requestNotificationPermission()
        requestStoragePermission()
        setupPager()
        
        viewPager.isUserInputEnabled = true

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ArchiveExtractorService.eventFlow.collect { event ->
                    when (event) {
                        is ArchiveExtractorService.ExtractEvent.Progress -> {
                            viewModel.postInstallProgress(event.message, event.percent)
                        }
                        is ArchiveExtractorService.ExtractEvent.Finished -> {
                            viewModel.postInstallFinished(event.success)
                        }
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            TermuxSessionManager.restoreSessions(this) {
                viewModel.notifySessionChanged()
            }
        }

        lifecycleScope.launch {
            val result = UpdateChecker.check(this@MainActivity)
            if (!result.active) showUpdateDialog(result.message)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            TermuxSessionManager.saveState(this)
            TermuxSessionManager.closeAllSessions()
        }
    }

    private fun applyStatusBarIconStyle() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val isNightMode = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        controller.isAppearanceLightStatusBars = !isNightMode
        controller.isAppearanceLightNavigationBars = !isNightMode
    }

    private fun setupPager() {
        viewPager = findViewById(R.id.viewPager)
        bottomNav = findViewById(R.id.bottomNav)

        val adapter = MainPagerAdapter(this)
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 2

        viewPager.setCurrentItem(0, false)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bottomNav.menu.getItem(position).isChecked = true
                viewPager.isUserInputEnabled = position != 1

                val isTerminalTab = position == 1
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                val isNightMode = (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                controller.isAppearanceLightStatusBars = if (isTerminalTab) false else !isNightMode

                if (position != 1) {
                    controller.hide(WindowInsetsCompat.Type.ime())
                }
            }
        })

        bottomNav.setOnItemSelectedListener { item ->
            val pos = when (item.itemId) {
                R.id.tab_explorer -> 0
                R.id.tab_terminal -> 1
                R.id.tab_settings -> 2
                else -> 0
            }
            viewPager.setCurrentItem(pos, true)
            true
        }
    }

    private fun showUpdateDialog(serverMessage: String) {
        val displayMessage = if (serverMessage.isNotBlank()) serverMessage
        else getString(R.string.update_message_default)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available_title))
            .setMessage(displayMessage)
            .setPositiveButton(getString(R.string.update_open_telegram)) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.telegram_url))))
            }
            .setNegativeButton(getString(R.string.update_later), null)
            .setCancelable(true)
            .show()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_INSTALL, "TermuX Installer",
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
            }
        } else {
            val perms = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (ContextCompat.checkSelfPermission(this, perms[0]) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, perms, 100)
            }
        }
    }
}