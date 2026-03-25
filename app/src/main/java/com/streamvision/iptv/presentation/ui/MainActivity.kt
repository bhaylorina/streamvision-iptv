package com.streamvision.iptv.presentation.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import kotlin.math.abs

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
        setupMiniPlayerGestures() // FIX: Set up gesture detection for Mini Player
    }

    private fun setupMiniPlayerGestures() {
        val miniPlayerLayout = findViewById<View>(R.id.mini_player_root) ?: return
        val miniPlayerView = findViewById<View>(R.id.mini_player_view)

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x

                if (abs(diffX) > abs(diffY)) {
                    // Left / Right Swipes
                    if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        findViewById<View>(R.id.btn_mini_close)?.performClick()
                        return true
                    }
                } else {
                    // Up / Down Swipes
                    if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffY < 0) {
                            // Swipe Up -> Expand
                            findViewById<View>(R.id.btn_mini_fullscreen)?.performClick()
                            return true
                        } else {
                            // Swipe Down -> Dismiss
                            findViewById<View>(R.id.btn_mini_close)?.performClick()
                            return true
                        }
                    }
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Tap -> Expand Mini Player
                findViewById<View>(R.id.btn_mini_fullscreen)?.performClick()
                return true
            }
        })

        // Apply swipe detector to Mini Player layout entirely
        val touchListener = View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        miniPlayerLayout.setOnTouchListener(touchListener)
        miniPlayerView?.setOnTouchListener(touchListener)
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
                    binding.bottomNavigation.visibility = View.GONE
                    setNavHostBottomMargin(0)
                }
                else -> {
                    isPlayerVisible = false
                    binding.bottomNavigation.visibility = View.VISIBLE
                    setNavHostBottomMargin(80) 
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
