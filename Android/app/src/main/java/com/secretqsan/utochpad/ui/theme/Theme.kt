package com.secretqsan.utochpad.ui.theme

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Miuix (HyperOS) theme for every screen except the soft touchpad surface. */
@Composable
fun UtochpadTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MiuixTheme(
        colors = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
