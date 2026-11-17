package com.secretqsan.utochpad

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import com.secretqsan.utochpad.data.AppTheme
import com.secretqsan.utochpad.ui.SettingsScreen
import com.secretqsan.utochpad.ui.SystemBarsAppearance
import com.secretqsan.utochpad.ui.theme.UtochpadTheme

/**
 * Settings has its own window and back stack entry. The controller lives in the
 * application, so opening it does not interrupt the phone session.
 */
class SettingsActivity : FragmentActivity() {
    private val controller: UtochpadController
        get() = (application as UtochpadApplication).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        setContent {
            val state by controller.state.collectAsState()
            val darkTheme = state.theme == AppTheme.Dark
            UtochpadTheme(darkTheme = darkTheme) {
                SystemBarsAppearance(darkTheme)
                SettingsScreen(
                    controller = controller,
                    state = state,
                    onBack = { finish() },
                )
            }
        }
    }
}
