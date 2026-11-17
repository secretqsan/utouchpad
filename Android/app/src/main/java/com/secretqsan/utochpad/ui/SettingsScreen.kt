package com.secretqsan.utochpad.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.secretqsan.utochpad.AppUiState
import com.secretqsan.utochpad.R
import com.secretqsan.utochpad.UtochpadController
import com.secretqsan.utochpad.data.AppTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Settings screen in Miuix style. */
@Composable
fun SettingsScreen(
    controller: UtochpadController,
    state: AppUiState,
    onBack: () -> Unit,
) {
    var showUnpair by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.settings_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SmallTitle(stringResource(R.string.settings_category_appearance))
            SettingsCard {
                SuperSwitch(
                    checked = state.theme == AppTheme.Dark,
                    onCheckedChange = { dark ->
                        controller.setTheme(if (dark) AppTheme.Dark else AppTheme.Light)
                    },
                    title = stringResource(R.string.settings_dark_theme),
                    summary = stringResource(R.string.settings_dark_theme_summary),
                )
            }

            SmallTitle(stringResource(R.string.settings_category_connection))
            SettingsCard {
                BasicComponent(
                    title = stringResource(R.string.settings_pc),
                    endActions = {
                        Text(
                            text = state.serviceName,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                BasicComponent(
                    title = stringResource(R.string.settings_password),
                    endActions = {
                        Text(
                            text = stringResource(
                                if (state.passwordSet) {
                                    R.string.settings_password_saved
                                } else {
                                    R.string.settings_password_missing
                                },
                            ),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                )
            }

            SmallTitle(stringResource(R.string.settings_category_pairing))
            SettingsCard {
                SuperArrow(
                    title = stringResource(R.string.settings_unpair),
                    summary = stringResource(R.string.settings_unpair_summary),
                    onClick = { showUnpair = true },
                )
            }

            SmallTitle(stringResource(R.string.settings_category_about))
            SettingsCard {
                BasicComponent(
                    title = stringResource(R.string.settings_version),
                    endActions = {
                        Text(
                            text = versionName,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                )
            }

            Spacer(Modifier.height(28.dp))
        }

        // Must stay inside the Scaffold: SuperDialog registers with the
        // Scaffold's popup host through composition locals.
        SuperDialog(
            show = showUnpair,
            title = stringResource(R.string.unpair_dialog_title),
            summary = stringResource(R.string.unpair_dialog_message),
            onDismissRequest = { showUnpair = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showUnpair = false },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        showUnpair = false
                        controller.unpair()
                        onBack()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.error,
                        contentColor = MiuixTheme.colorScheme.onError,
                    ),
                ) {
                    Text(text = stringResource(R.string.unpair_confirm))
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        content = content,
    )
}

