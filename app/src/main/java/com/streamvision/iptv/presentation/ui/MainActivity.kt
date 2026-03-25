package com.streamvision.iptv.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.streamvision.iptv.R
import com.streamvision.iptv.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FIX: Draw edge-to-edge so we can manually apply insets to every view.
        // This is required on Android 15+ and ensures no view overlaps the status bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // FIX: Apply system bar insets once to the root — every fragment
        // will automatically sit below the status bar because navHostFragment
        // gets a topMargin equal to the status bar height.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Push nav host below status bar
            val navParams = binding.navHostFragment.layoutParams as ViewGroup.MarginLayoutParams
            navParams.topMargin = systemBars.top
            binding.navHostFragment.layoutParams = navParams

            // Push bottom nav above gesture bar / nav bar
            val bottomNavParams = binding.bottomNavigation.layoutParams as ViewGroup.MarginLayoutParams
            bottomNavParams.bottomMargin = systemBars.bottom
            binding.bottomNavigation.layoutParams = bottomNavParams

            insets
        }

        setupNavigation()
        requestPermissions()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        binding.bottomNavigation.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.playerFragment -> {
                    binding.bottomNavigation.visibility = View.GONE
                    binding.miniPlayer.root.visibility = View.GONE
                    setNavHostBottomMargin(0)
                }
                else -> {
                    binding.bottomNavigation.visibility = View.VISIBLE
                    setNavHostBottomMargin(56)
                }
            }
        }
    }

    private fun setNavHostBottomMargin(dp: Int) {
        val px = (dp * resources.displayMetrics.density).toInt()
        val params = binding.navHostFragment.layoutParams as ViewGroup.MarginLayoutParams
        params.bottomMargin = px
        binding.navHostFragment.layoutParams = params
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // FIX: Keep screen on whenever the mini player is visible
    fun showMiniPlayer(channelName: String) {
        binding.miniPlayer.root.visibility = View.VISIBLE
        binding.miniPlayer.tvMiniTitle.text = channelName
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun hideMiniPlayer() {
        binding.miniPlayer.root.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun updateMiniPlayerState(isPlaying: Boolean) {
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.miniPlayer.btnMiniPlayPause.setImageResource(icon)
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
