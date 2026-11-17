package com.secretqsan.utochpad.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.secretqsan.utochpad.AppUiState
import com.secretqsan.utochpad.R
import com.secretqsan.utochpad.SettingsActivity
import com.secretqsan.utochpad.UtochpadController
import com.secretqsan.utochpad.net.ConnState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainScreen(controller: UtochpadController, state: AppUiState) {
    val connected = state.connection is ConnState.Connected
    val context = LocalContext.current

    KeepScreenOn(connected)

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            // Connecting and preparing the devices share one screen; only the
            // status line differs.
            !connected -> StatusScreen(
                status = (state.connection as? ConnState.Disconnected)?.reason
                    ?: "正在连接电脑…",
                retry = if (state.connection is ConnState.Connecting) null else controller::retry,
            )

            state.modeError != null -> ModeErrorScreen(state.modeError, controller)
            state.modeBusy || !state.modeReady ->
                StatusScreen(status = "正在准备触控板与 Radial…")

            else -> TouchpadScreen(
                controller = controller,
                state = state,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Settings stays reachable even while the app waits for the PC, so the
        // pairing can always be cleared.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.TopEnd,
        ) {
            IconButton(
                onClick = {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
                modifier = Modifier.padding(end = 12.dp, top = 10.dp),
                backgroundColor = MiuixTheme.colorScheme.surfaceVariant,
                cornerRadius = 22.dp,
            ) {
                Icon(
                    imageVector = MiuixIcons.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // One overlay column: the banner and the fingerprint pill stack in
        // order however tall the banner turns out to be.
        if (state.notice != null || state.authBusy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp, start = 14.dp, end = 14.dp),
                ) {
                    state.notice?.let { notice ->
                        NoticeBanner(
                            message = notice,
                            onDismiss = controller::clearNotice,
                        )
                    }
                    if (state.authBusy) {
                        AuthBusyPill()
                    }
                }
            }
        }
    }
}

/**
 * One status screen for both wait states: reaching the PC and preparing the
 * input devices. [retry] is only passed once an attempt has failed.
 */
@Composable
private fun StatusScreen(status: String, retry: (() -> Unit)? = null) {
    // Bars are hidden app-wide; their insets must not shift the centred status
    // text when the window controls settle.
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (retry == null) {
                CircularProgressIndicator()
                Spacer(Modifier.height(20.dp))
            }

            Text(
                text = "Utochpad",
                style = MiuixTheme.textStyles.title1,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = status,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "请保持手机 App 在前台，并让手机和电脑连接同一个 Wi-Fi。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )

            // Only after a failed attempt: a retry button while loading is dead weight.
            if (retry != null) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = retry,
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(text = "立即重试")
                }
            }
        }
    }
}

@Composable
private fun ModeErrorScreen(message: String, controller: UtochpadController) {
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = message,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "请确认电脑上的 Utochpad 正在运行，然后重试。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = controller::retryMode,
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.primary,
                    contentColor = MiuixTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(text = "重试")
            }
        }
    }
}

@Composable
private fun NoticeBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.weight(1f),
                maxLines = 3,
            )
            TextButton(
                text = "知道了",
                onClick = onDismiss,
                minWidth = 0.dp,
                minHeight = 34.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun AuthBusyPill(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                size = 16.dp,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "请在系统指纹提示中确认",
                style = MiuixTheme.textStyles.footnote1,
            )
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val activity = LocalContext.current as? Activity

    DisposableEffect(enabled, activity) {
        val window = activity?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
