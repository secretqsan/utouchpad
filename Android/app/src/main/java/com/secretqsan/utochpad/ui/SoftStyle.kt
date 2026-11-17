package com.secretqsan.utochpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Soft UI palette for the touchpad page: one base colour shaped only by paired
 * light/dark shadows. Every other screen uses Miuix.
 */
@Immutable
internal data class SoftPalette(
    val isDark: Boolean,
    val base: Color,
    val baseTop: Color,
    val baseBottom: Color,
    val shadowLight: Color,
    val shadowDark: Color,
    val raisedTop: Color,
    val raisedBottom: Color,
    val ink: Color,
    val inkStrong: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
    val gridDark: Color,
    val gridLight: Color,
    val knobFaceTop: Color,
    val knobFaceMid: Color,
    val knobFaceBottom: Color,
    val knobPressedTop: Color,
    val knobPressedBottom: Color,
    val tick: Color,
    val knobShadowDark: Float,
    val knobShadowLight: Float,
    val buttonShadowDark: Float,
    val buttonShadowLight: Float,
)

internal val LightSoftPalette = SoftPalette(
    isDark = false,
    base = Color(0xFFE6EBF2),
    baseTop = Color(0xFFEDF1F7),
    baseBottom = Color(0xFFDDE3EC),
    shadowLight = Color(0xFFFFFFFF),
    shadowDark = Color(0x8C9EB0C6),
    raisedTop = Color(0xFFF3F6FA),
    raisedBottom = Color(0xFFE2E7EF),
    ink = Color(0xFF586474),
    inkStrong = Color(0xFF37424F),
    green = Color(0xFF3D9A6D),
    amber = Color(0xFFAC7D22),
    red = Color(0xFFC2554D),
    gridDark = Color(0x1455697E),
    gridLight = Color(0x80FFFFFF),
    knobFaceTop = Color(0xFFF2F6FA),
    knobFaceMid = Color(0xFFE8EDF3),
    knobFaceBottom = Color(0xFFDCE2EA),
    knobPressedTop = Color(0xFFD8DFE8),
    knobPressedBottom = Color(0xFFEEF2F7),
    tick = Color(0xFF96A1AE),
    knobShadowDark = 0.16f,
    knobShadowLight = 0.85f,
    buttonShadowDark = 0.14f,
    buttonShadowLight = 0.60f,
)

internal val DarkSoftPalette = SoftPalette(
    isDark = true,
    base = Color(0xFF23272E),
    baseTop = Color(0xFF262B33),
    baseBottom = Color(0xFF1E2229),
    shadowLight = Color(0x16FFFFFF),
    shadowDark = Color(0xA6000000),
    raisedTop = Color(0xFF2B313A),
    raisedBottom = Color(0xFF20252C),
    ink = Color(0xFFAAB4C0),
    inkStrong = Color(0xFFDEE4EC),
    green = Color(0xFF5CCB98),
    amber = Color(0xFFDFB167),
    red = Color(0xFFE9827A),
    gridDark = Color(0x3D000000),
    gridLight = Color(0x14FFFFFF),
    knobFaceTop = Color(0xFF2E343D),
    knobFaceMid = Color(0xFF272D35),
    knobFaceBottom = Color(0xFF20252C),
    knobPressedTop = Color(0xFF1E232A),
    knobPressedBottom = Color(0xFF2B313A),
    tick = Color(0xFF8E99A6),
    knobShadowDark = 0.50f,
    knobShadowLight = 0.10f,
    buttonShadowDark = 0.45f,
    buttonShadowLight = 0.08f,
)

internal val LocalSoft = staticCompositionLocalOf<SoftPalette> { DarkSoftPalette }

/** Provides the soft palette for the selected appearance. */
@Composable
internal fun SoftTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkSoftPalette else LightSoftPalette
    CompositionLocalProvider(LocalSoft provides palette, content = content)
}

// ---------------------------------------------------------------------------
// Drawing primitives
// ---------------------------------------------------------------------------

private fun DrawScope.shapePath(shape: Shape): Path {
    val path = Path()
    path.addOutline(shape.createOutline(size, layoutDirection, this))
    return path
}

/**
 * Soft base sheet with an engraved grid: every line is drawn twice, a dark
 * groove on the upper-left and a light one on the lower-right.
 */
@Composable
internal fun Modifier.softBackground(grid: Boolean = true): Modifier {
    val soft = LocalSoft.current
    return drawBehind {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(soft.baseTop, soft.base, soft.baseBottom),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    soft.shadowLight.copy(alpha = soft.shadowLight.alpha * 0.30f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.18f, size.height * 0.08f),
                radius = size.maxDimension * 0.70f,
            ),
        )

        if (grid) {
            val step = 36.dp.toPx()
            val stroke = 1.dp.toPx()
            val lip = 0.55.dp.toPx()

            var x = step
            while (x < size.width) {
                drawLine(
                    color = soft.gridDark,
                    start = Offset(x - lip, 0f),
                    end = Offset(x - lip, size.height),
                    strokeWidth = stroke,
                )
                drawLine(
                    color = soft.gridLight,
                    start = Offset(x + lip, 0f),
                    end = Offset(x + lip, size.height),
                    strokeWidth = stroke,
                )
                x += step
            }

            var y = step
            while (y < size.height) {
                drawLine(
                    color = soft.gridDark,
                    start = Offset(0f, y - lip),
                    end = Offset(size.width, y - lip),
                    strokeWidth = stroke,
                )
                drawLine(
                    color = soft.gridLight,
                    start = Offset(0f, y + lip),
                    end = Offset(size.width, y + lip),
                    strokeWidth = stroke,
                )
                y += step
            }
        }
    }
}

/** Element pressed out of the base: light and dark shadow plus a soft face. */
@Composable
internal fun Modifier.softRaised(
    shape: Shape = RoundedCornerShape(20.dp),
    offset: Dp = 4.dp,
    blur: Dp = 9.dp,
    topColor: Color = LocalSoft.current.raisedTop,
    bottomColor: Color = LocalSoft.current.raisedBottom,
): Modifier {
    val soft = LocalSoft.current
    return this
        .dropShadow(
            shape,
            Shadow(
                radius = blur,
                color = soft.shadowDark,
                offset = DpOffset(offset, offset),
            ),
        )
        .dropShadow(
            shape,
            Shadow(
                radius = blur,
                color = soft.shadowLight,
                offset = DpOffset(-offset, -offset),
            ),
        )
        .drawBehind {
            drawPath(
                path = shapePath(shape),
                brush = Brush.linearGradient(
                    colors = listOf(topColor, bottomColor),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
        }
}

// ---------------------------------------------------------------------------
// Components used by the touchpad page
// ---------------------------------------------------------------------------

/** Soft label on the base sheet. */
@Composable
internal fun SoftText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    fontSize: TextUnit = 13.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = 0.1.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color ?: LocalSoft.current.ink,
        fontSize = fontSize,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        lineHeight = lineHeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

/** Small status LED with a soft halo instead of a hard bezel. */
@Composable
internal fun StatusLed(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.dp)) {
        val radius = size.minDimension / 2f
        drawCircle(
            color = color.copy(alpha = 0.22f),
            radius = radius,
            center = center,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.95f),
                    color,
                    color.copy(alpha = 0.60f),
                ),
                center = center,
                radius = radius * 0.72f,
            ),
            radius = radius * 0.58f,
            center = center,
        )
    }
}
