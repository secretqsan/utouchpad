package com.secretqsan.utochpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.secretqsan.utochpad.AppUiState
import com.secretqsan.utochpad.R
import com.secretqsan.utochpad.UtochpadController
import com.secretqsan.utochpad.data.AppTheme

/** Settings screen in the app's soft UI language. */
@Composable
fun SettingsScreen(
    controller: UtochpadController,
    state: AppUiState,
    onBack: () -> Unit,
) {
    val soft = LocalSoft.current
    var showUnpair by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .softBackground(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            SettingsTopBar(onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                ) {
                    SectionLabel(stringResource(R.string.settings_category_appearance))
                    SoftCard {
                        SwitchRow(
                            title = stringResource(R.string.settings_dark_theme),
                            summary = stringResource(R.string.settings_dark_theme_summary),
                            checked = state.theme == AppTheme.Dark,
                            onCheckedChange = { dark ->
                                controller.setTheme(
                                    if (dark) AppTheme.Dark else AppTheme.Light,
                                )
                            },
                        )
                    }

                    SectionLabel(stringResource(R.string.settings_category_connection))
                    SoftCard {
                        ValueRow(
                            title = stringResource(R.string.settings_pc),
                            value = state.serviceName,
                        )
                        SoftDivider()
                        ValueRow(
                            title = stringResource(R.string.settings_password),
                            value = stringResource(
                                if (state.passwordSet) {
                                    R.string.settings_password_saved
                                } else {
                                    R.string.settings_password_missing
                                },
                            ),
                        )
                    }

                    SectionLabel(stringResource(R.string.settings_category_pairing))
                    SoftCard {
                        ArrowRow(
                            title = stringResource(R.string.settings_unpair),
                            summary = stringResource(R.string.settings_unpair_summary),
                            onClick = { showUnpair = true },
                        )
                    }

                    SectionLabel(stringResource(R.string.settings_category_about))
                    SoftCard {
                        ValueRow(
                            title = stringResource(R.string.settings_version),
                            value = versionName,
                        )
                    }

                    Spacer(Modifier.height(28.dp))
                }
            }
        }

        if (showUnpair) {
            UnpairDialog(
                title = stringResource(R.string.unpair_dialog_title),
                message = stringResource(R.string.unpair_dialog_message),
                cancelText = stringResource(R.string.action_cancel),
                confirmText = stringResource(R.string.unpair_confirm),
                onCancel = { showUnpair = false },
                onConfirm = {
                    showUnpair = false
                    controller.unpair()
                    onBack()
                },
            )
        }
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    val soft = LocalSoft.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftIconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = soft.inkStrong,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        SoftText(
            text = stringResource(R.string.settings_title),
            color = soft.inkStrong,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    val soft = LocalSoft.current
    SoftText(
        text = text,
        color = soft.inkSoft,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SoftCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .softRaised(RoundedCornerShape(24.dp)),
        content = content,
    )
}

@Composable
private fun SoftDivider() {
    val soft = LocalSoft.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp)
            .height(1.dp)
            .background(soft.divider),
    )
}

@Composable
private fun ValueRow(title: String, value: String) {
    val soft = LocalSoft.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SoftText(
            text = title,
            color = soft.inkStrong,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        SoftText(
            text = value,
            color = soft.inkSoft,
            fontSize = 13.sp,
            modifier = Modifier.widthIn(max = 220.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val soft = LocalSoft.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SoftText(
                text = title,
                color = soft.inkStrong,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(2.dp))
            SoftText(
                text = summary,
                color = soft.inkSoft,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(14.dp))
        SoftSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ArrowRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    val soft = LocalSoft.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SoftText(
                text = title,
                color = soft.inkStrong,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(3.dp))
            SoftText(
                text = summary,
                color = soft.inkSoft,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(12.dp))
        Canvas(modifier = Modifier.size(18.dp)) {
            val stroke = 2.dp.toPx()
            drawLine(
                color = soft.inkSoft,
                start = Offset(size.width * 0.38f, size.height * 0.28f),
                end = Offset(size.width * 0.64f, size.height * 0.5f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = soft.inkSoft,
                start = Offset(size.width * 0.64f, size.height * 0.5f),
                end = Offset(size.width * 0.38f, size.height * 0.72f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun UnpairDialog(
    title: String,
    message: String,
    cancelText: String,
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val soft = LocalSoft.current
    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .softRaised(RoundedCornerShape(26.dp))
                .padding(24.dp),
        ) {
            SoftText(
                text = title,
                color = soft.inkStrong,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            SoftText(
                text = message,
                color = soft.ink,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 6,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SoftButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    SoftText(
                        text = cancelText,
                        color = soft.inkStrong,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                SoftButton(
                    onClick = onConfirm,
                    primary = true,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    SoftText(
                        text = confirmText,
                        color = soft.danger,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
