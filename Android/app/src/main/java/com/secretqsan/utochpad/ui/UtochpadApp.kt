package com.secretqsan.utochpad.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.secretqsan.utochpad.UtochpadController
import com.secretqsan.utochpad.data.AppTheme
import com.secretqsan.utochpad.ui.theme.UtochpadTheme

@Composable
fun UtochpadApp(controller: UtochpadController) {
    val state by controller.state.collectAsState()
    val dark = state.theme == AppTheme.Dark

    UtochpadTheme(darkTheme = dark) {
        SoftTheme(darkTheme = dark) {
            SystemBarsAppearance(dark)
            if (!state.paired) {
                PairScreen(controller, state)
            } else {
                MainScreen(controller, state)
            }
        }
    }
}

/**
 * Keeps the status/navigation bar icons readable after the appearance setting
 * changes. Both activities call `enableEdgeToEdge` at creation, but the theme
 * can be switched while one of them is still alive.
 */
@Composable
internal fun SystemBarsAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity ?: return

    DisposableEffect(activity, view, darkTheme) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
        onDispose { }
    }
}
