package com.secretqsan.utochpad.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.secretqsan.utochpad.AppUiState
import com.secretqsan.utochpad.SettingsActivity
import com.secretqsan.utochpad.UtochpadController
import com.secretqsan.utochpad.net.ConnState

@Composable
fun MainScreen(controller: UtochpadController, state: AppUiState) {
    val connected = state.connection is ConnState.Connected
    val context = LocalContext.current
    val soft = LocalSoft.current

    FullscreenEffect(connected)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .softBackground(),
    ) {
        when {
            // Connecting to the PC and preparing the input devices share one
            // screen; only the status line differs while loading.
            !connected -> LoadingOverlay(
                status = (state.connection as? ConnState.Disconnected)?.reason
                    ?: "正在连接电脑…",
                retry = if (state.connection is ConnState.Connecting) null else controller::retry,
            )
            state.modeError != null -> ModeErrorOverlay(state.modeError, controller)
            state.modeBusy || !state.modeReady ->
                LoadingOverlay(status = "正在准备触控板与 Radial…")
            else -> TouchpadScreen(
                controller = controller,
                state = state,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Settings stays reachable on every screen, including while the app is
        // stuck waiting for the PC, so the pairing can always be cleared.
        SettingsCorner {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }

        state.notice?.let { notice ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                NoticeBanner(
                    message = notice,
                    onDismiss = controller::clearNotice,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp, start = 14.dp, end = 14.dp),
                )
            }
        }

        if (state.authBusy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (state.notice != null) 82.dp else 14.dp)
                        .softRaised(CircleShape, offset = 3.dp, blur = 7.dp)
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                ) {
                    CircularProgressIndicator(
                        color = soft.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(15.dp),
                    )
                    SoftText(
                        text = "请在系统指纹提示中确认",
                        color = soft.inkStrong,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

/**
 * One loading screen for both wait states: reaching the PC and preparing the
 * input devices. The spinner is the same soft-raised treatment either way, and
 * [retry] is only passed once an attempt has failed.
 */
@Composable
private fun LoadingOverlay(status: String, retry: (() -> Unit)? = null) {
    val soft = LocalSoft.current

    SoftCenteredColumn {
        AppMark()
        Spacer(Modifier.height(12.dp))
        SoftText(
            text = "Utochpad",
            color = soft.inkStrong,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )

        Spacer(Modifier.height(16.dp))

        if (retry == null) {
            SoftSpinner()
            Spacer(Modifier.height(16.dp))
        }

        SoftText(
            text = status,
            color = soft.ink,
            fontSize = 15.sp,
            maxLines = 3,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(8.dp))
        SoftText(
            text = "请保持手机 App 在前台，并让手机和电脑连接同一个 Wi-Fi。",
            color = soft.inkSoft,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )

        // Only after a failed attempt: a retry button while loading is dead
        // weight on the screen.
        if (retry != null) {
            Spacer(Modifier.height(16.dp))
            SoftButton(
                onClick = retry,
                modifier = Modifier.heightIn(min = 42.dp),
            ) {
                SoftText(
                    text = "立即重试",
                    color = soft.inkStrong,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.3.sp,
                )
            }
        }
    }
}

@Composable
private fun ModeErrorOverlay(message: String, controller: UtochpadController) {
    val soft = LocalSoft.current
    SoftCenteredColumn {
        Box(
            modifier = Modifier
                .size(66.dp)
                .softRaised(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SoftText(
                text = "!",
                color = soft.danger,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(20.dp))
        SoftText(
            text = message,
            color = soft.danger,
            fontSize = 15.sp,
            maxLines = 3,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(10.dp))
        SoftText(
            text = "请确认电脑上的 Utochpad 正在运行，然后重试。",
            color = soft.inkSoft,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(24.dp))
        SoftButton(
            onClick = controller::retryMode,
            primary = true,
            modifier = Modifier.heightIn(min = 46.dp),
        ) {
            SoftText(
                text = "重试",
                color = soft.primaryInk,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
            )
        }
    }
}

@Composable
private fun NoticeBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val soft = LocalSoft.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .softRaised(RoundedCornerShape(18.dp))
            .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftText(
            text = message,
            color = soft.inkStrong,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = 3,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            SoftText(
                text = "知道了",
                color = soft.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun FullscreenEffect(enabled: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity

    DisposableEffect(enabled, activity) {
        if (activity == null) {
            return@DisposableEffect onDispose { }
        }

        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, view)

        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
