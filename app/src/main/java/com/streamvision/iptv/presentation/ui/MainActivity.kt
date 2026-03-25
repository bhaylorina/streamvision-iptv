package com.streamvision.iptv.presentation.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import com.streamvision.iptv.player.PlayerManager
import com.streamvision.iptv.presentation.ui.player.PlayerFragment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    @Inject
    lateinit var playerManager: PlayerManager

    private var isPlayerVisible = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navParams = binding.navHostFragment.layoutParams as ViewGroup.MarginLayoutParams
            navParams.topMargin = systemBars.top
            binding.navHostFragment.layoutParams = navParams

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
                    isPlayerVisible = true
                    // IMPORTANT: Detach player from MiniPlayer UI before entering fullscreen
                    binding.miniPlayer.miniPlayerView.player = null
                    binding.miniPlayer.root.visibility = View.GONE
                    binding.bottomNavigation.visibility = View.GONE
                    setNavHostBottomMargin(0)
                }
                else -> {
                    isPlayerVisible = false
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun showMiniPlayer(channelName: String) {
        // Prevent mini player if we are already in the player screen
        if (navController.currentDestination?.id == R.id.playerFragment) return

        binding.miniPlayer.root.visibility = View.VISIBLE
        binding.miniPlayer.tvMiniTitle.text = channelName
        binding.miniPlayer.miniPlayerView.player = playerManager.player
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.miniPlayer.btnMiniPlayPause.setOnClickListener {
            if (playerManager.isPlaying) playerManager.pause() else playerManager.resume()
            updateMiniPlayerState(playerManager.isPlaying)
        }

        binding.miniPlayer.btnMiniFullscreen.setOnClickListener {
            playerManager.currentChannel?.let { channel ->
                val bundle = Bundle().apply { putLong("channelId", channel.id) }
                navController.navigate(R.id.playerFragment, bundle)
            }
        }

        binding.miniPlayer.btnMiniClose.setOnClickListener {
            playerManager.stop()
            hideMiniPlayer()
        }
    }

    fun hideMiniPlayer() {
        binding.miniPlayer.miniPlayerView.player = null
        binding.miniPlayer.root.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun updateMiniPlayerState(isPlaying: Boolean) {
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.miniPlayer.btnMiniPlayPause.setImageResource(icon)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isPlayerVisible && playerManager.isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPiP: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPiP, newConfig)
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val current = navHost?.childFragmentManager?.primaryNavigationFragment
        if (current is PlayerFragment) {
            current.handlePipModeChange(isInPiP)
        }
    }

    override fun onSupportNavigateUp(): Boolean = navController.navigateUp() || super.onSupportNavigateUp()
}
