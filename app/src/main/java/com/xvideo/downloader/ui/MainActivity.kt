package com.xvideo.downloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.xvideo.downloader.R
import com.xvideo.downloader.databinding.ActivityMainBinding
import com.xvideo.downloader.ui.downloads.DownloadsFragment
import com.xvideo.downloader.ui.home.HomeFragment
import com.xvideo.downloader.ui.local.LocalVideosFragment
import com.xvideo.downloader.ui.online.OnlinePlayerFragment
import com.xvideo.downloader.ui.settings.SettingsFragment
import com.xvideo.downloader.util.PermissionUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // Track fragments for show/hide management
    private var activeFragment: Fragment? = null
    private val fragmentTags = mutableMapOf<Int, String>()

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_ONLINE = "online"
        private const val TAG_DOWNLOADS = "downloads"
        private const val TAG_LOCAL = "local"
        private const val TAG_SETTINGS = "settings"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Snackbar.make(
                binding.root,
                R.string.permission_denied,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNavigation()
        setupKeyboardListener()
        checkPermissions()

        // Handle shared text from other apps
        handleIntent(intent)

        // Load default fragment
        if (savedInstanceState == null) {
            loadFragment(HomeFragment(), TAG_HOME)
        } else {
            // Restore active fragment reference
            activeFragment = supportFragmentManager.fragments.firstOrNull { it.isVisible }
        }

        // Handle back press for WebView navigation
        onBackPressedDispatcher.addCallback(this) {
            val currentFragment = activeFragment
            if (currentFragment is HomeFragment && currentFragment.canGoBack()) {
                currentFragment.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
            if (sharedText.contains("twitter.com") || sharedText.contains("x.com")) {
                // Navigate to home and let it handle the URL
                switchToTab(R.id.nav_home)
            }
        }
    }

    /**
     * Called by HomeFragment after parsing a video URL.
     * Switches to the Downloads tab with the parsed VideoInfo.
     */
    fun navigateToDownloads(downloadsFragment: DownloadsFragment) {
        // Switch to downloads tab
        binding.bottomNavigation.selectedItemId = R.id.nav_downloads

        // Replace the downloads fragment with the new one carrying VideoInfo
        val transaction = supportFragmentManager.beginTransaction()

        // Hide current active fragment
        activeFragment?.let { transaction.hide(it) }

        // Remove existing downloads fragment if any
        supportFragmentManager.findFragmentByTag(TAG_DOWNLOADS)?.let {
            transaction.remove(it)
        }

        // Add new downloads fragment with VideoInfo
        transaction.add(R.id.fragmentContainer, downloadsFragment, TAG_DOWNLOADS)
        transaction.commit()

        activeFragment = downloadsFragment
    }

    /**
     * Hide bottom navigation when keyboard opens to avoid layout conflicts.
     * Show it again when keyboard closes.
     */
    private fun setupKeyboardListener() {
        val rootView = window.decorView
        keyboardListener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            rootView.getWindowVisibleDisplayFrame(r)
            val screenHeight = rootView.height
            val keypadHeight = screenHeight - r.bottom
            val isKeyboardOpen = keypadHeight > screenHeight * 0.15

            binding.bottomNavigation.isVisible = !isKeyboardOpen
            // Adjust fragment container margin
            val params = binding.fragmentContainer.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            params.bottomMargin = if (isKeyboardOpen) 0 else resources.getDimensionPixelSize(R.dimen.bottom_nav_height)
            binding.fragmentContainer.layoutParams = params
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(keyboardListener)
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showOrCreateFragment(HomeFragment(), TAG_HOME)
                R.id.nav_online -> showOrCreateFragment(OnlinePlayerFragment(), TAG_ONLINE)
                R.id.nav_downloads -> showOrCreateFragment(DownloadsFragment(), TAG_DOWNLOADS)
                R.id.nav_local -> showOrCreateFragment(LocalVideosFragment(), TAG_LOCAL)
                R.id.nav_settings -> showOrCreateFragment(SettingsFragment(), TAG_SETTINGS)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
    }

    /**
     * Show existing fragment or create new one. Uses show/hide to preserve state.
     */
    private fun showOrCreateFragment(newFragment: Fragment, tag: String) {
        val transaction = supportFragmentManager.beginTransaction()

        // Hide current active fragment
        activeFragment?.let { transaction.hide(it) }

        // Check if fragment already exists
        val existingFragment = supportFragmentManager.findFragmentByTag(tag)
        if (existingFragment != null) {
            transaction.show(existingFragment)
            activeFragment = existingFragment
        } else {
            transaction.add(R.id.fragmentContainer, newFragment, tag)
            activeFragment = newFragment
        }

        transaction.commit()
    }

    /**
     * Load initial fragment (used only on first creation).
     */
    private fun loadFragment(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, fragment, tag)
            .commit()
        activeFragment = fragment
    }

    /**
     * Switch to a specific tab by menu item ID.
     */
    private fun switchToTab(menuItemId: Int) {
        binding.bottomNavigation.selectedItemId = menuItemId
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Storage permissions
        if (!PermissionUtils.hasStoragePermission(this)) {
            permissionsToRequest.addAll(PermissionUtils.getRequiredStoragePermissions())
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onDestroy() {
        keyboardListener?.let {
            window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
        keyboardListener = null
        super.onDestroy()
    }
}
