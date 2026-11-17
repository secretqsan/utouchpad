package com.secretqsan.utochpad

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import com.secretqsan.utochpad.data.AppTheme
import com.secretqsan.utochpad.ui.UtochpadApp

class MainActivity : FragmentActivity() {
    private val controller: UtochpadController
        get() = (application as UtochpadApplication).controller

    private val localNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "需要允许 Utochpad 访问本地网络，才能连接电脑。请在系统设置中开启“本地网络”权限。",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Match the bar icons to the saved appearance before the first frame;
        // the in-app setting can later change it while the activity is alive.
        val dark = controller.state.value.theme == AppTheme.Dark
        val barStyle = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = barStyle,
            navigationBarStyle = barStyle,
        )

        // The whole app is one fullscreen surface: pairing, loading and the
        // touchpad. Hiding the bars before the first frame keeps the content
        // from shifting when the connection becomes ready.
        hideSystemBars()

        ensureLocalNetworkPermission()

        setContent {
            UtochpadApp(controller)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun ensureLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT >= 36 &&
            checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            localNetworkPermission.launch(LOCAL_NETWORK_PERMISSION)
        }
    }

    private companion object {
        // Introduced in Android 16 (API 36); declared as a string so the app
        // still compiles and runs on older SDKs.
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
