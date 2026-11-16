package com.secretqsan.utochpad.ui

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretqsan.utochpad.AppUiState
import com.secretqsan.utochpad.UtochpadController
import com.secretqsan.utochpad.net.ConnState
import com.secretqsan.utochpad.net.Contact
import com.secretqsan.utochpad.net.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Landscape input page: the whole screen is the pad, with the radial knob on
 * the right. Every finger is forwarded as a raw PTP contact; Windows decides
 * whether it was a tap, a scroll or a swipe.
 */
@Composable
fun TouchpadScreen(
    controller: UtochpadController,
    state: AppUiState,
    modifier: Modifier = Modifier,
) {
    val padState = remember { PadState() }
    val hoverScope = rememberCoroutineScope()
    val soft = LocalSoft.current

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Every frame is forwarded, including the pointer-up that
                    // ends a gesture; the pad does no gesture recognition.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            handlePadPointerEvent(event, size, padState, controller, hoverScope)
                        }
                    }
                },
        )

        // Overlay elements are siblings above the pad; only the settings button
        // and the dial consume touches.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 20.dp, top = 15.dp)
                    .softRaised(CircleShape, offset = 3.dp, blur = 7.dp)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                val (ledColor, label) = when (val connection = state.connection) {
                    ConnState.Connecting -> soft.amber to "正在连接"
                    ConnState.Connected -> soft.green to "已连接"
                    is ConnState.Disconnected -> soft.red to connection.reason
                }

                StatusLed(ledColor)
                SoftText(
                    text = "$label · ${state.serviceName}",
                    color = soft.inkStrong,
                    fontSize = 12.sp,
                    letterSpacing = 0.2.sp,
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }

            RadialKnob(
                controller = controller,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 26.dp)
                    .size(140.dp),
            )
        }
    }
}

/** Radial ring: the outer annulus sends relative dial deltas, the inner disc is the button. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RadialKnob(controller: UtochpadController, modifier: Modifier = Modifier) {
    var knobSize by remember { mutableStateOf(IntSize.Zero) }
    var pressed by remember { mutableStateOf(false) }
    // 0 = raised, 1 = fully sunk.
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "radialButtonPress",
    )
    var angleDegrees by remember { mutableFloatStateOf(0f) }
    var rotatingPointer by remember { mutableIntStateOf(-1) }
    var buttonPointer by remember { mutableIntStateOf(-1) }
    var lastAngle by remember { mutableFloatStateOf(0f) }
    var accumulated by remember { mutableFloatStateOf(0f) }
    val view = LocalView.current
    val soft = LocalSoft.current

    fun send(dial: Int, buttons: Int) {
        controller.sendRadialFrame(Protocol.buildRadialFrame(dial, buttons))
    }

    fun angleAt(x: Float, y: Float): Float {
        val centerX = knobSize.width / 2f
        val centerY = knobSize.height / 2f
        val degrees = Math.toDegrees(
            atan2((y - centerY).toDouble(), (x - centerX).toDouble()),
        ).toFloat()
        return if (degrees < 0f) degrees + 360f else degrees
    }

    fun isButtonZone(x: Float, y: Float): Boolean {
        val centerX = knobSize.width / 2f
        val centerY = knobSize.height / 2f
        val radius = hypot((x - centerX).toDouble(), (y - centerY).toDouble())
        return radius <= min(knobSize.width, knobSize.height) / 2.0 * ButtonZoneFraction
    }

    fun pressButton(pointerId: Int) {
        if (pressed) return
        buttonPointer = pointerId
        pressed = true
        send(0, 1)
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun releaseButton() {
        buttonPointer = -1
        if (pressed) {
            pressed = false
            send(0, 0)
        }
    }

    fun beginRotation(pointerId: Int, x: Float, y: Float) {
        rotatingPointer = pointerId
        lastAngle = angleAt(x, y)
        accumulated = 0f
    }

    fun applyRotation(x: Float, y: Float) {
        val centerX = knobSize.width / 2f
        val centerY = knobSize.height / 2f
        val radius = hypot((x - centerX).toDouble(), (y - centerY).toDouble())
        val current = angleAt(x, y)

        // Close to the centre the angle is mostly noise: move the reference
        // point without emitting rotation, as a real dial would.
        if (radius < min(knobSize.width, knobSize.height) * 0.22) {
            lastAngle = current
            return
        }

        var delta = current - lastAngle
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        lastAngle = current

        angleDegrees += delta
        accumulated += delta * 10f
        val dial = accumulated.toInt()
        accumulated -= dial
        if (dial != 0) {
            send(dial, if (pressed) 1 else 0)
        }
    }

    fun assignPointer(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)
        when {
            isButtonZone(x, y) -> if (!pressed) pressButton(id)
            rotatingPointer == -1 -> beginRotation(id, x, y)
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { knobSize = it }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> assignPointer(event, 0)

                    MotionEvent.ACTION_POINTER_DOWN -> assignPointer(event, event.actionIndex)

                    MotionEvent.ACTION_MOVE -> {
                        if (rotatingPointer != -1) {
                            val index = event.findPointerIndex(rotatingPointer)
                            if (index >= 0) {
                                applyRotation(event.getX(index), event.getY(index))
                            }
                        }
                    }

                    MotionEvent.ACTION_POINTER_UP -> {
                        when (event.getPointerId(event.actionIndex)) {
                            buttonPointer -> releaseButton()
                            rotatingPointer -> rotatingPointer = -1
                        }
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        releaseButton()
                        rotatingPointer = -1
                    }
                }

                true
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = center
            val outerRadius = size.minDimension / 2f
            val discRadius = outerRadius - 1.dp.toPx()
            val buttonRadius = outerRadius * 0.53f

            // Neumorphic dual shadow: the same disc blurred and offset in
            // opposite directions, with the alpha edge centred on the disc rim.
            val shadowOffset = 5.dp.toPx()
            val shadowBlur = 8.dp.toPx()
            val shadowRadius = discRadius + shadowBlur
            val shadowCore = (discRadius - shadowBlur) / shadowRadius

            fun dualShadow(direction: Offset, color: Color) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to color,
                            shadowCore to color,
                            1f to Color.Transparent,
                        ),
                        center = c + direction,
                        radius = shadowRadius,
                    ),
                    radius = shadowRadius,
                    center = c + direction,
                )
            }

            dualShadow(
                Offset(shadowOffset, shadowOffset),
                Color.Black.copy(alpha = soft.knobShadowDark),
            )
            dualShadow(
                Offset(-shadowOffset, -shadowOffset),
                Color.White.copy(alpha = soft.knobShadowLight),
            )

            // Raised soft face, lit from the upper left.
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(soft.knobFaceTop, soft.knobFaceMid, soft.knobFaceBottom),
                    start = c - Offset(outerRadius, outerRadius),
                    end = c + Offset(outerRadius, outerRadius),
                ),
                radius = discRadius,
                center = c,
            )

            // Ticks on the rotating ring. There is no index mark: the radial
            // protocol only carries relative rotation.
            rotate(angleDegrees, pivot = c) {
                val tickCount = 12
                val tickOuter = discRadius - 9.dp.toPx()
                val tickInner = tickOuter - 9.dp.toPx()
                for (i in 0 until tickCount) {
                    val radians = Math.toRadians((i * 360f / tickCount).toDouble())
                    val directionX = cos(radians).toFloat()
                    val directionY = sin(radians).toFloat()
                    val start = Offset(
                        c.x + directionX * tickInner,
                        c.y + directionY * tickInner,
                    )
                    val end = Offset(
                        c.x + directionX * tickOuter,
                        c.y + directionY * tickOuter,
                    )
                    drawLine(
                        color = soft.tick,
                        start = start,
                        end = end,
                        strokeWidth = 1.1.dp.toPx(),
                    )
                }
            }

            // Inner button, raised by its own shadow pair and sunken when held.
            // Separation comes only from the soft shadows, never an outline.
            val buttonShadowOffset = 3.dp.toPx()
            val buttonShadowBlur = 5.dp.toPx()
            val shadowDirection = Offset(buttonShadowOffset, buttonShadowOffset)
            val shadowDistance = hypot(shadowDirection.x, shadowDirection.y)
            val buttonShadowRadius = buttonRadius + buttonShadowBlur
            val buttonShadowCore = (buttonRadius - buttonShadowBlur) / buttonShadowRadius

            fun buttonShadow(direction: Offset, color: Color) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to color,
                            buttonShadowCore to color,
                            1f to Color.Transparent,
                        ),
                        center = c + direction,
                        radius = buttonShadowRadius,
                    ),
                    radius = buttonShadowRadius,
                    center = c + direction,
                )
            }

            // The raised shadow fades out as the button sinks instead of
            // disappearing in one frame.
            if (press < 1f) {
                val raised = 1f - press
                buttonShadow(
                    shadowDirection,
                    Color.Black.copy(alpha = soft.buttonShadowDark * raised),
                )
                buttonShadow(
                    -shadowDirection,
                    Color.White.copy(alpha = soft.buttonShadowLight * raised),
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lerp(soft.knobFaceTop, soft.knobPressedTop, press),
                        lerp(soft.knobFaceBottom, soft.knobPressedBottom, press),
                    ),
                    center = c - Offset(buttonRadius * 0.25f, buttonRadius * 0.30f),
                    radius = buttonRadius * 1.8f,
                ),
                radius = buttonRadius,
                center = c,
            )

            if (press > 0f) {
                // Sunken state: a soft crescent hugging the inner wall. Pushing
                // the gradient centre to the opposite side keeps only a narrow
                // band shaded; the drawn circle clips it to the button.
                val insetRadius = buttonRadius + shadowDistance
                val insetStart = (1f - buttonShadowBlur / insetRadius).coerceIn(0f, 0.9f)

                fun insetShadow(direction: Offset, color: Color, strength: Float) {
                    fun stop(fraction: Float) = color.copy(alpha = strength * fraction)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                insetStart to Color.Transparent,
                                insetStart + (1f - insetStart) * 0.35f to stop(0.16f),
                                insetStart + (1f - insetStart) * 0.65f to stop(0.50f),
                                insetStart + (1f - insetStart) * 0.85f to stop(0.80f),
                                1f to stop(1f),
                            ),
                            center = c + direction,
                            radius = insetRadius,
                        ),
                        radius = buttonRadius,
                        center = c,
                    )
                }

                // Light from the upper left: the upper-left wall falls into
                // shadow, the lower-right wall catches the light.
                insetShadow(
                    shadowDirection,
                    Color.Black,
                    (if (soft.isDark) 0.45f else 0.28f) * press,
                )
                insetShadow(
                    -shadowDirection,
                    Color.White,
                    (if (soft.isDark) 0.10f else 0.48f) * press,
                )
            }
        }
    }
}

private const val ButtonZoneFraction = 0.55f

/** How long a lifted contact stays in the report with Tip=0 before removal. */
private const val LiftHoldMillis = 32L

private class PadContact(val id: Int, var x: Int, var y: Int, var tip: Boolean)

private class PadState {
    // Keyed by the Compose pointer id; the value keeps its own PTP contact id.
    private val byPointer = LinkedHashMap<Long, PadContact>()

    /**
     * Records the current position of a finger and returns true when the report
     * would change. A new finger first drops contacts still hovering from a
     * previous lift so they cannot take a slot from a real finger.
     */
    fun update(pointerId: Long, x: Int, y: Int): Boolean {
        val existing = byPointer[pointerId]
        if (existing != null && existing.tip) {
            if (existing.x == x && existing.y == y) return false
            existing.x = x
            existing.y = y
            return true
        }

        byPointer.entries.removeAll { !it.value.tip }

        var id = 0
        while (byPointer.values.any { it.id == id }) id++
        byPointer[pointerId] = PadContact(id, x, y, tip = true)
        return true
    }

    /**
     * Marks the contact as lifted but keeps it in the report for a few frames,
     * which is what a real touchpad reports when a finger leaves the surface.
     */
    fun release(pointerId: Long): PadContact? {
        val contact = byPointer[pointerId] ?: return null
        if (!contact.tip) return null
        contact.tip = false
        return contact
    }

    /**
     * Removes a contact after the Tip=0 report was visible. The identity check
     * makes a late removal harmless if the pointer id already started a new
     * contact.
     */
    fun drop(pointerId: Long, contact: PadContact): Boolean {
        if (byPointer[pointerId] !== contact) return false
        byPointer.remove(pointerId)
        return true
    }

    /** True while a lifted contact is still in the report (Tip=0 hold). */
    fun hasLiftedContact(): Boolean = byPointer.values.any { !it.tip }

    fun snapshot(): List<Contact> =
        byPointer.values
            .sortedBy { it.id }
            // A lifted contact keeps Confidence=1: it is still in range.
            .map { Contact(it.id, it.x, it.y, tip = it.tip, confidence = true) }
}

/**
 * Forwards one Compose pointer event as a complete PTP snapshot. A pointer that
 * was pressed in the previous event stays in the snapshot with Tip=0 for
 * [LiftHoldMillis], after which it is removed and another snapshot is sent.
 */
private fun handlePadPointerEvent(
    event: PointerEvent,
    size: IntSize,
    state: PadState,
    controller: UtochpadController,
    scope: CoroutineScope,
) {
    // A new finger reuses the slot of a contact still reported with Tip=0.
    // Flush that frame first: Windows needs to see it to turn the lift into a click.
    val newPress = event.changes.any { it.pressed && !it.previousPressed }
    if (newPress && state.hasLiftedContact()) {
        controller.sendTouchFrame(state.snapshot())
    }

    var changed = false
    val lifted = ArrayList<Pair<Long, PadContact>>(event.changes.size)

    for (change in event.changes) {
        val pointerId = change.id.value
        if (change.pressed) {
            val x = scaleCoordinate(change.position.x, size.width)
            val y = scaleCoordinate(change.position.y, size.height)
            if (state.update(pointerId, x, y)) changed = true
        } else if (change.previousPressed) {
            val contact = state.release(pointerId)
            if (contact != null) {
                lifted.add(pointerId to contact)
                changed = true
            }
        }
    }

    if (changed) controller.sendTouchFrame(state.snapshot())

    for ((pointerId, contact) in lifted) {
        scope.launch {
            delay(LiftHoldMillis)
            if (state.drop(pointerId, contact)) {
                controller.sendTouchFrame(state.snapshot())
            }
        }
    }
}

private fun scaleCoordinate(value: Float, length: Int): Int =
    ((value / length.toFloat()) * 4095f).toInt().coerceIn(0, 4095)
