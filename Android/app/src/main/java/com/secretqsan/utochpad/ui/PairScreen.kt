package com.secretqsan.utochpad.ui

import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.secretqsan.utochpad.AppUiState
import com.secretqsan.utochpad.SettingsActivity
import com.secretqsan.utochpad.UtochpadCaptureActivity
import com.secretqsan.utochpad.UtochpadController
import kotlinx.coroutines.launch
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

/**
 * Pairing page, landscape only.
 *
 * One mark, one name, one action, stacked down the centre of the sheet.
 * Manual code entry lives in a dialog, so opening it can never push the page
 * past the bottom of a phone held sideways.
 */
@Composable
fun PairScreen(controller: UtochpadController, state: AppUiState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val soft = LocalSoft.current

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .softBackground(),
    ) {
        SoftCenteredColumn {
            AppMark()
            Spacer(Modifier.height(16.dp))
            SoftText(
                text = "Utochpad",
                color = soft.inkStrong,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )

            Spacer(Modifier.height(26.dp))

            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SoftButton(
                    onClick = { scanner.launch(scanOptions) },
                    enabled = !busy,
                    primary = true,
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            color = soft.primaryInk,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        SoftText(
                            text = "正在配对…",
                            color = soft.primaryInk,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = null,
                            tint = soft.primaryInk,
                            modifier = Modifier.size(19.dp),
                        )
                        SoftText(
                            text = "扫描电脑二维码",
                            color = soft.primaryInk,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !busy) { showManual = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    SoftText(
                        text = "无法扫码？使用配对码",
                        color = if (busy) soft.inkSoft else soft.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    SoftText(
                        text = it,
                        color = soft.danger,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 3,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                state.notice?.let {
                    Spacer(Modifier.height(8.dp))
                    SoftText(
                        text = it,
                        color = soft.inkSoft,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        SettingsCorner {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }

    if (showManual) {
        ManualCodeDialog(
            code = manualCode,
            onCodeChange = { manualCode = it },
            busy = busy,
            onDismiss = { showManual = false },
            onPaste = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val text = clipboard?.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                if (!text.isNullOrEmpty()) manualCode = text
            },
            onConfirm = {
                showManual = false
                startPair(manualCode)
            },
        )
    }
}

/** Manual pairing in a soft dialog, so the page behind it never grows. */
@Composable
private fun ManualCodeDialog(
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPaste: () -> Unit,
    onConfirm: () -> Unit,
) {
    val soft = LocalSoft.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .softRaised(RoundedCornerShape(24.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SoftText(
                    text = "使用配对码",
                    color = soft.inkStrong,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                SoftField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = "配对码",
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SoftButton(
                        onClick = onPaste,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        SoftText(
                            text = "粘贴",
                            color = soft.inkStrong,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    SoftButton(
                        onClick = onConfirm,
                        enabled = code.isNotBlank() && !busy,
                        primary = true,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        SoftText(
                            text = "配对",
                            color = if (code.isNotBlank() && !busy) {
                                soft.primaryInk
                            } else {
                                soft.inkSoft
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Recessed soft input. The label stays as a small caption inside the field so
 * the box keeps the pressed-in look no matter whether it has content.
 */
@Composable
private fun SoftField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val soft = LocalSoft.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(
            color = soft.inkStrong,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        minLines = 1,
        maxLines = 4,
        cursorBrush = SolidColor(soft.accent),
        modifier = modifier,
        decorationBox = { innerTextField ->
            Column(
                modifier = Modifier
                    .softInset(RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                SoftText(
                    text = label,
                    color = soft.inkSoft,
                    fontSize = 11.sp,
                    letterSpacing = 0.3.sp,
                )
                Spacer(Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (value.isEmpty()) {
                        SoftText(
                            text = "在此粘贴或输入配对码",
                            color = soft.inkSoft.copy(alpha = 0.60f),
                            fontSize = 13.sp,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}
