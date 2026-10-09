package com.flexy.f1live.ui

import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.flexy.f1live.ui.components.edgeFade
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val TransitionMillis = 450

private const val FadeThroughThreshold = 0.35f

private val SharedAxisSlide = 30.dp

private const val FadeThroughStartScale = 0.92f

private const val PredictiveScale = 0.9f

private val PredictiveMargin = 8.dp

private val PredictiveEnterOffset = 96.dp

private const val PredictiveEnterScale = 1.1f

private const val ScrimAlphaLight = 0.2f
private const val ScrimAlphaDark = 0.8f

private val BackGestureEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

private val EmphasizedEasing = PathEasing(
    Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    },
)

private fun decelerate(x: Float): Float = 1f - (1f - x) * (1f - x)

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

private fun range(value: Float, start: Float, end: Float): Float =
    ((value - start) / (end - start)).coerceIn(0f, 1f)

internal enum class NavMotionKind { SharedAxis, FadeThrough, PredictiveCard, PredictiveFade }

@Stable
internal class NavMotion {
    var kind: NavMotionKind = NavMotionKind.FadeThrough
        private set
    var forward: Boolean = true
        private set

    var gestureActive by mutableStateOf(false)
        private set

    var touchY by mutableFloatStateOf(0f)
        private set
    var startTouchY: Float = 0f
        private set
    var swipeEdge: Int = NavigationEvent.EDGE_LEFT
        private set

    var releaseProgress: Float = 0f
        private set

    internal var dispatcher: NavigationEventDispatcher? = null

    fun decide(fromTopLevel: Boolean, toTopLevel: Boolean, pop: Boolean) {
        val state = dispatcher?.transitionState?.value
        if (state is NavigationEventTransitionState.InProgress) onTransitionState(state)
        val predictive = pop && gestureActive
        kind = when {
            fromTopLevel && toTopLevel ->
                if (predictive) NavMotionKind.PredictiveFade else NavMotionKind.FadeThrough
            predictive -> NavMotionKind.PredictiveCard
            else -> NavMotionKind.SharedAxis
        }
        forward = !pop
    }

    fun onTransitionState(state: NavigationEventTransitionState) {
        if (state is NavigationEventTransitionState.InProgress &&
            state.direction == NavigationEventTransitionState.TRANSITIONING_BACK
        ) {
            val event = state.latestEvent
            if (!gestureActive) {
                startTouchY = event.touchY
                swipeEdge = event.swipeEdge
            }
            touchY = event.touchY
            releaseProgress = event.progress
            gestureActive = true
        } else {
            gestureActive = false
        }
    }

    fun timelineMillis(): Int {
        val predictive =
            kind == NavMotionKind.PredictiveCard || kind == NavMotionKind.PredictiveFade
        if (!predictive || gestureActive) return TransitionMillis
        val remaining = (1f - releaseProgress).coerceAtLeast(0.1f)
        return (TransitionMillis / remaining).roundToInt()
    }
}

@Composable
internal fun rememberNavMotion(): NavMotion {
    val motion = remember { NavMotion() }
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    motion.dispatcher = dispatcher
    LaunchedEffect(dispatcher) {
        dispatcher?.transitionState?.collect { motion.onTransitionState(it) }
    }
    return motion
}

private class Pose {
    var scale = 1f
    var originX = 0.5f
    var originY = 0.5f
    var translationX = 0f
    var translationY = 0f
    var alpha = 1f
    var rounded = false
    var scrim = 0f

    fun reset() {
        scale = 1f
        originX = 0.5f
        originY = 0.5f
        translationX = 0f
        translationY = 0f
        alpha = 1f
        rounded = false
        scrim = 0f
    }

    fun rect(left: Float, top: Float, scale: Float) {
        this.scale = scale
        originX = 0f
        originY = 0f
        translationX = left
        translationY = top
    }
}

private fun Pose.compute(
    motion: NavMotion,
    timeline: Float,
    entering: Boolean,
    exiting: Boolean,
    size: Size,
    density: Density,
    dark: Boolean,
) {
    reset()
    if (!entering && !exiting) return
    val q = if (exiting) timeline else 1f - timeline
    when (motion.kind) {
        NavMotionKind.SharedAxis -> {
            val p = EmphasizedEasing.transform(q)
            val slide = with(density) { SharedAxisSlide.toPx() } * if (motion.forward) 1f else -1f
            if (exiting) {
                translationX = -slide * p
                alpha = 1f - range(p, 0f, FadeThroughThreshold)
            } else {
                translationX = slide * (1f - p)
                alpha = range(p, FadeThroughThreshold, 1f)
            }
        }

        NavMotionKind.FadeThrough -> {
            val p = EmphasizedEasing.transform(q)
            if (exiting) {
                alpha = 1f - range(p, 0f, FadeThroughThreshold)
            } else {
                alpha = range(p, FadeThroughThreshold, 1f)
                scale = lerp(FadeThroughStartScale, 1f, p)
            }
        }

        NavMotionKind.PredictiveFade -> {
            val p = BackGestureEasing.transform(q)
            if (exiting) {
                scale = lerp(1f, PredictiveScale, p)
                alpha = 1f - range(p, 0f, FadeThroughThreshold)
            } else {
                scale = lerp(PredictiveEnterScale, 1f, p)
                alpha = range(p, FadeThroughThreshold, 1f)
            }
        }

        NavMotionKind.PredictiveCard -> predictiveCard(motion, q, exiting, size, density, dark)
    }
}

private fun Pose.predictiveCard(
    motion: NavMotion,
    q: Float,
    closing: Boolean,
    size: Size,
    density: Density,
    dark: Boolean,
) {
    val width = size.width
    val height = size.height
    val margin = with(density) { PredictiveMargin.toPx() }
    val enterOffset = with(density) { PredictiveEnterOffset.toPx() }
    val maxScrim = if (dark) ScrimAlphaDark else ScrimAlphaLight
    val release = motion.releaseProgress
    rounded = true

    fun preCommit(progress: Float, follow: Float) {
        val p = BackGestureEasing.transform(progress)
        val s = lerp(1f, PredictiveScale, p)
        val dy = motion.touchY - motion.startTouchY
        val ratio = decelerate(minOf(height / 2f, abs(dy)) / (height / 2f))
        val yShift = max(0f, (height - height * s) / 2f - margin) * ratio *
            (if (dy < 0f) -1f else 1f) * follow
        val top = (height - height * s) / 2f + yShift
        if (closing) {
            val centered = (width - width * PredictiveScale) / 2f
            val target = if (motion.swipeEdge == NavigationEvent.EDGE_RIGHT) {
                centered
            } else {
                width - width * PredictiveScale - margin
            }
            rect(left = lerp(0f, target, p), top = top, scale = s)
        } else {
            val left = -enterOffset + (width - width * s) / 2f
            rect(left = left, top = top, scale = s)
            scrim = maxScrim
        }
    }

    if (motion.gestureActive) {
        preCommit(q, follow = 1f)
        return
    }
    if (q <= release) {
        preCommit(q, follow = if (release > 0f) q / release else 0f)
        return
    }
    preCommit(release, follow = 1f)
    val linear = range(q, release, 1f)
    val e = EmphasizedEasing.transform(linear)
    val startLeft = translationX
    val startTop = translationY
    val startScale = scale
    if (closing) {
        rect(
            left = lerp(startLeft, startLeft + enterOffset, e),
            top = lerp(startTop, 0f, e),
            scale = lerp(startScale, 1f, e),
        )
        alpha = max(1f - linear * 5f, 0f)
    } else {
        rect(
            left = lerp(startLeft, 0f, e),
            top = lerp(startTop, 0f, e),
            scale = lerp(startScale, 1f, e),
        )
        scrim = maxScrim * (1f - linear)
    }
}

private class CardShape(private val view: View) {
    private var shape: Shape? = null

    fun get(density: Density): Shape = shape ?: run {
        val radius = view.rootWindowInsets
            ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        val px = if (radius > 0) radius.toFloat() else with(density) { 28.dp.toPx() }
        RoundedCornerShape(px).also { if (view.rootWindowInsets != null) shape = it }
    }
}

@Composable
internal fun AnimatedVisibilityScope.NavMotionScreen(
    motion: NavMotion,
    background: Color,
    fadeTop: Dp,
    fadeBottom: Dp,
    content: @Composable () -> Unit,
) {
    val timeline: State<Float> = transition.animateFloat(
        transitionSpec = { tween(motion.timelineMillis(), easing = LinearEasing) },
        label = "nav-motion",
    ) { state -> if (state == EnterExitState.Visible) 0f else 1f }
    val view = LocalView.current
    val cardShape = remember(view) { CardShape(view) }
    val dark = background.luminance() < 0.5f
    val layerPose = remember { Pose() }
    val scrimPose = remember { Pose() }
    val states = transition

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                scrimPose.compute(
                    motion = motion,
                    timeline = timeline.value,
                    entering = states.targetState == EnterExitState.Visible &&
                        states.currentState == EnterExitState.PreEnter,
                    exiting = states.targetState == EnterExitState.PostExit,
                    size = size,
                    density = this,
                    dark = dark,
                )
                if (scrimPose.scrim > 0f) {
                    drawRect(Color.Black.copy(alpha = scrimPose.scrim))
                }
            }
            .graphicsLayer {
                val pose = layerPose
                pose.compute(
                    motion = motion,
                    timeline = timeline.value,
                    entering = states.targetState == EnterExitState.Visible &&
                        states.currentState == EnterExitState.PreEnter,
                    exiting = states.targetState == EnterExitState.PostExit,
                    size = size,
                    density = this,
                    dark = dark,
                )
                transformOrigin = TransformOrigin(pose.originX, pose.originY)
                scaleX = pose.scale
                scaleY = pose.scale
                translationX = pose.translationX
                translationY = pose.translationY
                alpha = pose.alpha
                if (pose.rounded) {
                    shape = cardShape.get(this)
                    clip = true
                } else {
                    shape = RectangleShape
                    clip = false
                }
            }
            .background(background)
            .edgeFade(top = fadeTop, bottom = fadeBottom),
    ) {
        content()
    }
}
