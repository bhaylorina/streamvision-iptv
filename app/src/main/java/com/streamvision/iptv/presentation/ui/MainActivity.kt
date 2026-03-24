package com.streamvision.iptv.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                    // Full screen player — hide everything
                    binding.bottomNavigation.visibility = View.GONE
                    binding.miniPlayer.root.visibility = View.GONE
                    setNavHostBottomMargin(0)
                }
                else -> {
                    binding.bottomNavigation.visibility = View.VISIBLE
                    setNavHostBottomMargin(56)
                    // ✅ Mini player visibility is managed by ChannelsFragment/FavoritesFragment
                    // Do NOT force-show it here — let the fragment decide
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

    fun showMiniPlayer(channelName: String) {
        binding.miniPlayer.root.visibility = View.VISIBLE
        binding.miniPlayer.tvMiniTitle.text = channelName
    }

    fun hideMiniPlayer() {
        binding.miniPlayer.root.visibility = View.GONE
    }

    fun updateMiniPlayerState(isPlaying: Boolean) {
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.miniPlayer.btnMiniPlayPause.setImageResource(icon)
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
