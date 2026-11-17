package com.secretqsan.utochpad.ui

import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.secretqsan.utochpad.AppUiState
import com.secretqsan.utochpad.R
import com.secretqsan.utochpad.SettingsActivity
import com.secretqsan.utochpad.UtochpadCaptureActivity
import com.secretqsan.utochpad.UtochpadController
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private fun friendlyPairError(ex: Throwable?): String {
    val raw = ex?.message.orEmpty()

    return when {
        ex is SocketTimeoutException ||
            ex is ConnectException ||
            raw.contains("failed to connect", ignoreCase = true) ||
            raw.contains("timeout", ignoreCase = true) ||
            raw.contains("超时", ignoreCase = true) ->
            "连接电脑超时。请确认电脑上的 Utochpad 正在运行，手机和电脑连接同一个 Wi-Fi，然后重试。"

        ex is UnknownHostException ->
            "找不到电脑，请重新扫描二维码。"

        raw.contains("refused", ignoreCase = true) ->
            "电脑拒绝了连接。请确认 Utochpad 正在运行，然后重试。"

        // Messages written for the user by the PC (expired code, bad QR, ...).
        raw.contains("配对码") || raw.contains("二维码") || raw.contains("配对信息") -> raw

        else -> "配对失败，请重试。"
    }
}

/** Pairing page: brand mark, name and the one action, on a clean full screen. */
@Composable
fun PairScreen(controller: UtochpadController, state: AppUiState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }

    fun startPair(raw: String) {
        if (raw.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            val result = controller.pairWithCode(raw)
            if (result.isFailure) {
                error = friendlyPairError(result.exceptionOrNull())
            } else {
                // The page switches to the connection screen right away, so the
                // toast is the only place this confirmation is still visible.
                Toast.makeText(context, "配对成功", Toast.LENGTH_SHORT).show()
            }
            busy = false
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            startPair(contents)
        }
    }

    // Our own capture activity hosts the camera preview and nothing else.
    val scanOptions = remember {
        ScanOptions().apply {
            setCaptureActivity(UtochpadCaptureActivity::class.java)
            setBeepEnabled(false)
            setOrientationLocked(false)
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        }
    }

    // The system bars are always hidden here, so their insets must not move the
    // page when they animate. Only the cutout is a fixed part of the surface.
    Scaffold(contentWindowInsets = WindowInsets.displayCutout) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppMark()
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Utochpad",
                    style = MiuixTheme.textStyles.title1,
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = { scanner.launch(scanOptions) },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary,
                    ),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            size = 18.dp,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = "正在配对…")
                    } else {
                        Icon(
                            imageVector = MiuixIcons.Scan,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = "扫描电脑二维码")
                    }
                }

                // Functional link, not a second button: one primary action stays
                // the only filled surface on the page.
                Text(
                    text = "无法扫码？使用配对码",
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (busy) {
                        MiuixTheme.colorScheme.disabledOnSurface
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !busy) { showManual = true }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )

                // Pairing errors come from the last attempt or from a pairing
                // the PC has invalidated; both are functional, not decoration.
                (error ?: state.notice)?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                    )
                }
            }

            IconButton(
                onClick = {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 14.dp, top = 12.dp),
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

        // Must stay inside the Scaffold: SuperDialog registers with the
        // Scaffold's popup host through composition locals.
        SuperDialog(
            show = showManual,
            title = "使用配对码",
            onDismissRequest = { showManual = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = manualCode,
                    onValueChange = { manualCode = it },
                    label = "配对码",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = "粘贴",
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            val text = clipboard?.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                            if (!text.isNullOrEmpty()) manualCode = text
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            showManual = false
                            startPair(manualCode)
                        },
                        enabled = manualCode.isNotBlank() && !busy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary,
                            contentColor = MiuixTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(text = "配对")
                    }
                }
            }
        }
    }
}
