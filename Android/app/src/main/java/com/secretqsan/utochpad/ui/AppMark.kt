package com.secretqsan.utochpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Brand mark, with proportions taken from brand/utochpad-logo.svg. It keeps the
 * brand's warm material in dark appearance too, like a printed logo.
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
