package com.secretqsan.utochpad.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretqsan.utochpad.R

/**
 * Visual language for the whole app: soft UI (neumorphism).
 *
 * Everything sits on one flat base colour and is shaped only by a pair of
 * blurred shadows: a light one from the upper left and a dark one toward the
 * lower right. Raised elements draw that pair outside the shape, pressed or
 * recessed elements draw it inside, so surfaces read as pressed out of or sunk
 * into the same sheet of material instead of as separate outlined cards.
 *
 * The shadows use Compose's own drop/inner shadow painters, which rasterise the
 * blur off-screen and work consistently across Android versions.
 *
 * Two complete palettes exist, soft light and soft dark. Everything that draws
 * a surface reads the palette from [LocalSoft], so the appearance setting
 * switches the whole material, not just the Material colours.
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
    val insetTop: Color,
    val insetBottom: Color,
    val ink: Color,
    val inkStrong: Color,
    val inkSoft: Color,
    val accent: Color,
    val accentDeep: Color,
    val primaryInk: Color,
    val gridDark: Color,
    val gridLight: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
    val danger: Color,
    val knobFaceTop: Color,
    val knobFaceMid: Color,
    val knobFaceBottom: Color,
    val knobPressedTop: Color,
    val knobPressedBottom: Color,
    val tick: Color,
    val divider: Color,
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
    insetTop = Color(0xFFDCE2EA),
    insetBottom = Color(0xFFF0F4F9),
    ink = Color(0xFF586474),
    inkStrong = Color(0xFF37424F),
    inkSoft = Color(0xFF8B96A5),
    accent = Color(0xFF4A6F9E),
    accentDeep = Color(0xFF395776),
    primaryInk = Color(0xFF395776),
    gridDark = Color(0x1455697E),
    gridLight = Color(0x80FFFFFF),
    green = Color(0xFF3D9A6D),
    amber = Color(0xFFAC7D22),
    red = Color(0xFFC2554D),
    danger = Color(0xFFB0443C),
    knobFaceTop = Color(0xFFF2F6FA),
    knobFaceMid = Color(0xFFE8EDF3),
    knobFaceBottom = Color(0xFFDCE2EA),
    knobPressedTop = Color(0xFFD8DFE8),
    knobPressedBottom = Color(0xFFEEF2F7),
    tick = Color(0xFF96A1AE),
    divider = Color(0x1F8A99A6),
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
    insetTop = Color(0xFF1D2128),
    insetBottom = Color(0xFF2A3039),
    ink = Color(0xFFAAB4C0),
    inkStrong = Color(0xFFDEE4EC),
    inkSoft = Color(0xFF79838F),
    accent = Color(0xFF82A9DB),
    accentDeep = Color(0xFF5B85B4),
    primaryInk = Color(0xFF9CC2EE),
    gridDark = Color(0x3D000000),
    gridLight = Color(0x14FFFFFF),
    green = Color(0xFF5CCB98),
    amber = Color(0xFFDFB167),
    red = Color(0xFFE9827A),
    danger = Color(0xFFF3908A),
    knobFaceTop = Color(0xFF2E343D),
    knobFaceMid = Color(0xFF272D35),
    knobFaceBottom = Color(0xFF20252C),
    knobPressedTop = Color(0xFF1E232A),
    knobPressedBottom = Color(0xFF2B313A),
    tick = Color(0xFF8E99A6),
    divider = Color(0x14FFFFFF),
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
 * Soft base sheet with a barely visible diagonal light and an engraved grid.
 *
 * The grid is what makes the sheet read as a physical pad instead of a flat
 * fill: every line is drawn twice, a dark groove wall on the upper-left and a
 * light one on the lower-right, so the lines look pressed into the material.
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

/** Element sunk into the base: darker face and an inner shadow on both walls. */
@Composable
internal fun Modifier.softInset(
    shape: Shape = RoundedCornerShape(16.dp),
    offset: Dp = 3.dp,
    blur: Dp = 7.dp,
): Modifier {
    val soft = LocalSoft.current
    return this
        .drawBehind {
            drawPath(
                path = shapePath(shape),
                brush = Brush.linearGradient(
                    colors = listOf(soft.insetTop, soft.insetBottom),
                    start = Offset.Zero,
                    end = Offset(0f, size.height),
                ),
            )
        }
        .innerShadow(
            shape,
            Shadow(
                radius = blur,
                color = soft.shadowDark,
                offset = DpOffset(offset, offset),
            ),
        )
        .innerShadow(
            shape,
            Shadow(
                radius = blur,
                color = soft.shadowLight,
                offset = DpOffset(-offset, -offset),
            ),
        )
}

/**
 * Button face that animates between raised and pressed.
 *
 * Every button stays the same soft material: the base sheet lifted by one
 * light and one dark shadow. A primary button is not a coloured slab; it is
 * only a little more lifted, with a faint accent tint in the face and accent
 * ink for its label. A saturated gradient would flatten the relief back into
 * an ordinary filled button.
 */
@Composable
internal fun Modifier.softButtonSurface(
    shape: Shape,
    pressed: Float,
    primary: Boolean,
    enabled: Boolean,
): Modifier {
    val soft = LocalSoft.current
    val lift = (1f - pressed).coerceIn(0f, 1f)
    val boost = if (primary) 1.15f else 1f
    val distance = 4.dp * (0.35f + 0.65f * lift) * boost
    val blur = 9.dp
    val strength = if (enabled) 1f else 0.40f

    var surface = this
        .dropShadow(
            shape,
            Shadow(
                radius = blur,
                color = soft.shadowDark,
                offset = DpOffset(distance, distance),
                alpha = (lift * strength * boost).coerceAtMost(1f),
            ),
        )
        .dropShadow(
            shape,
            Shadow(
                radius = blur,
                color = soft.shadowLight,
                offset = DpOffset(-distance, -distance),
                alpha = (lift * strength * boost).coerceAtMost(1f),
            ),
        )

    surface = surface.drawBehind {
        // Primary and neutral buttons are the same material. The primary one
        // is only lifted a little more, and its label carries the accent.
        val top = lerp(soft.raisedTop, soft.insetTop, pressed)
        val bottom = lerp(soft.raisedBottom, soft.insetBottom, pressed)
        drawPath(
            path = shapePath(shape),
            brush = Brush.linearGradient(
                colors = listOf(top, bottom),
                start = Offset.Zero,
                end = Offset(0f, size.height),
            ),
        )
    }

    if (pressed > 0f) {
        val innerDistance = 3.dp
        val innerBlur = 7.dp
        surface = surface
            .innerShadow(
                shape,
                Shadow(
                    radius = innerBlur,
                    color = soft.shadowDark,
                    offset = DpOffset(innerDistance, innerDistance),
                    alpha = pressed * strength,
                ),
            )
            .innerShadow(
                shape,
                Shadow(
                    radius = innerBlur,
                    color = soft.shadowLight,
                    offset = DpOffset(-innerDistance, -innerDistance),
                    alpha = pressed * strength,
                ),
            )
    }

    return surface
}

// ---------------------------------------------------------------------------
// Components
// ---------------------------------------------------------------------------

/**
 * Centred column for whole-sheet messages.
 *
 * A plain centred Column squeezes its last children when they are taller than
 * the landscape sheet, which cut text and made the round spinner ellipsoidal;
 * scrolling keeps every child at its natural size and is invisible while they
 * fit.
 */
@Composable
internal fun SoftCenteredColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

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

/** Raised soft button. Pressing sinks it into the base sheet. */
@Composable
internal fun SoftButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    shape: Shape = RoundedCornerShape(16.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "softButtonPress",
    )
    Box(
        modifier = modifier
            .softButtonSurface(shape, press, primary, enabled)
            .clip(shape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Round raised button for a single icon. */
@Composable
internal fun SoftIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "softIconPress",
    )
    Box(
        modifier = modifier
            .size(42.dp)
            .softButtonSurface(CircleShape, press, primary = false, enabled = true)
            .clip(CircleShape)
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Raised soft capsule with the settings gear and its label. */
@Composable
private fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val soft = LocalSoft.current
    SoftButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 38.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = null,
            tint = soft.inkStrong,
            modifier = Modifier.size(16.dp),
        )
        SoftText(
            text = "设置",
            color = soft.inkStrong,
            fontSize = 12.sp,
            letterSpacing = 0.4.sp,
        )
    }
}

/**
 * The app's single settings entry point. Every screen pins it to the same
 * corner, so it never moves between the pairing, loading and touchpad pages.
 */
@Composable
internal fun SettingsCorner(onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentAlignment = Alignment.TopEnd,
    ) {
        SettingsButton(
            onClick = onClick,
            modifier = Modifier.padding(end = 12.dp, top = 10.dp),
        )
    }
}

/** Soft switch: a recessed track with a raised thumb that travels across it. */
@Composable
internal fun SoftSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val soft = LocalSoft.current
    val interaction = remember { MutableInteractionSource() }
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "softSwitchThumb",
    )

    Box(
        modifier = modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(CircleShape)
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            ),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .softInset(CircleShape, offset = 2.dp, blur = 5.dp),
        )
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                soft.accent.copy(alpha = 0.30f * progress),
                                soft.accentDeep.copy(alpha = 0.20f * progress),
                            ),
                        ),
                        shape = CircleShape,
                    ),
            )
        }
        Box(
            modifier = Modifier
                .padding(4.dp)
                // Read the animation in the layout lambda so the thumb moves
                // without recomposing the whole switch every frame.
                .offset { IntOffset((20.dp * progress).roundToPx(), 0) }
                .size(22.dp)
                .softRaised(
                    shape = CircleShape,
                    offset = 2.dp,
                    blur = 4.dp,
                    topColor = soft.knobFaceTop,
                    bottomColor = soft.knobFaceBottom,
                ),
        )
    }
}

/** Loading spinner inside a raised soft disc, the app's one loading treatment. */
@Composable
internal fun SoftSpinner(
    modifier: Modifier = Modifier,
    discSize: Dp = 64.dp,
) {
    val soft = LocalSoft.current
    Box(
        modifier = modifier
            .size(discSize)
            .softRaised(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = soft.accent,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(discSize * 0.40f),
        )
    }
}

/**
 * Brand mark: the white rounded tile from the app icon, with the wide touchpad
 * raised out of it and the four-point star and guide line on its face.
 *
 * Proportions are taken straight from brand/utochpad-logo.svg (512 x 512). The
 * mark keeps the brand's warm material even when the app is in its dark
 * appearance, the way a printed logo would.
 */
@Composable
internal fun AppMark(modifier: Modifier = Modifier, width: Dp = 80.dp) {
    val tile = RoundedCornerShape(width * 0.2227f) // 114 / 512
    val star = Color(0xFFB58A57)
    val faceTop = Color(0xFFFCF5E9)
    val faceMid = Color(0xFFF5E9D6)
    val faceBottom = Color(0xFFE6D1B2)
    val rim = Color(0xFFFFFEF9)

    Box(
        modifier = modifier
            .size(width)
            .background(Color.White, tile),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val padLeft = w * 0.1289f
            val padTop = w * 0.2949f
            val padWidth = w * 0.7422f
            val padHeight = w * 0.4102f
            val padRadius = w * 0.0820f

            fun fourPointStar(centerX: Float, centerY: Float, radius: Float): Path {
                val k = radius * 0.219f
                return Path().apply {
                    moveTo(centerX, centerY - radius)
                    cubicTo(centerX, centerY - k, centerX + k, centerY, centerX + radius, centerY)
                    cubicTo(centerX + k, centerY, centerX, centerY + k, centerX, centerY + radius)
                    cubicTo(centerX, centerY + k, centerX - k, centerY, centerX - radius, centerY)
                    cubicTo(centerX - k, centerY, centerX, centerY - k, centerX, centerY - radius)
                    close()
                }
            }

            // Pad face, lit from the upper left.
            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to faceTop,
                        0.55f to faceMid,
                        1f to faceBottom,
                    ),
                    start = Offset(padLeft, padTop),
                    end = Offset(padLeft + padWidth, padTop + padHeight),
                ),
                topLeft = Offset(padLeft, padTop),
                size = Size(padWidth, padHeight),
                cornerRadius = CornerRadius(padRadius, padRadius),
            )

            // Rim light: the upper-left edge catches the light, the far edge
            // fades away.
            val rimInset = w * 0.0039f // 2 / 512
            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to rim.copy(alpha = 0.95f),
                        0.5f to rim.copy(alpha = 0.32f),
                        1f to Color.Transparent,
                    ),
                    start = Offset(padLeft, padTop),
                    end = Offset(padLeft + padWidth, padTop + padHeight),
                ),
                topLeft = Offset(padLeft + rimInset, padTop + rimInset),
                size = Size(padWidth - rimInset * 2, padHeight - rimInset * 2),
                cornerRadius = CornerRadius(padRadius - rimInset, padRadius - rimInset),
                style = Stroke(width = w * 0.0059f), // 3 / 512
            )

            // Star: its top tip clears the pad edge and rests on the white tile.
            drawPath(
                path = fourPointStar(w * 0.6983f, w * 0.3718f, w * 0.1152f),
                color = star,
            )

            // Guide line at the bottom centre, half the thickness of the
            // first low-resolution pass so it matches the star's arms.
            val lineWidth = w * 0.0234f // 12 / 512
            val lineHeight = w * 0.1250f // 64 / 512
            drawRoundRect(
                color = star,
                topLeft = Offset((w - lineWidth) / 2f, w * 0.5293f), // 271 / 512
                size = Size(lineWidth, lineHeight),
                cornerRadius = CornerRadius(lineWidth / 2f),
            )
        }
    }
}
