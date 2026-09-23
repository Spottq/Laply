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

/*
 * Screen-to-screen motion for the NavHost, following Material 3 and the Android predictive back
 * guidance:
 *
 * - Forward / backward between a tab and a detail screen: shared axis X (MaterialSharedAxis:
 *   30dp slide, fade-through at 35 %, motionDurationLong1 = 450 ms, emphasized easing).
 * - Between top-level tabs: fade through (MaterialFadeThrough: incoming scales 92 % -> 100 %,
 *   fade-through at 35 %, 450 ms, emphasized easing).
 * - Predictive back gesture out of a detail screen: the same motion the system plays between
 *   activities (AOSP WM Shell DefaultCrossActivityBackAnimation) - the closing screen shrinks to a
 *   rounded 90 % card that keeps an 8dp margin to the display edge and follows the finger
 *   vertically; the previous screen waits 96dp to the left under a scrim and settles in with an
 *   emphasized 450 ms animation once the gesture commits.
 * - Predictive back gesture between tabs: the "full screen surfaces" pattern of the predictive back
 *   design guide - exiting 100 % -> 90 %, entering 110 % -> 100 %, fade-through at 35 % progress.
 *
 * NavHost (navigation-compose 2.8+) seeks its transition with the back gesture progress, which is
 * what keeps both screens composed during the gesture. The NavHost enter/exit transitions are left
 * empty; every screen instead reads one linear timeline from its own AnimatedVisibilityScope and
 * turns it into graphicsLayer transforms here, which is what gives access to the swipe edge and the
 * touch position (NavHost's transition lambdas only ever see the progress) and lets the post-commit
 * motion differ from the gesture-driven one. Transforms only: nothing is re-measured per frame.
 */

/** motionDurationLong1: MaterialSharedAxis / MaterialFadeThrough and the system post-commit. */
private const val TransitionMillis = 450

/** FadeThroughProvider.FADE_THROUGH_THRESHOLD, also the predictive back fade-through threshold. */
private const val FadeThroughThreshold = 0.35f

/** mtrl_transition_shared_axis_slide_distance. */
private val SharedAxisSlide = 30.dp

/** MaterialFadeThrough's incoming start scale. */
private const val FadeThroughStartScale = 0.92f

/** CrossActivityBackAnimation.MAX_SCALE and the design guide's "90 % minimum scale". */
private const val PredictiveScale = 0.9f

/** cross_task_back_vertical_margin: the gap a shrunk surface keeps to the display edge. */
private val PredictiveMargin = 8.dp

/** cross_activity_back_entering_start_offset: where the previous screen waits, to the left. */
private val PredictiveEnterOffset = 96.dp

/** Predictive back "full screen surfaces": the entering surface starts at 110 %. */
private const val PredictiveEnterScale = 1.1f

/** CrossActivityBackAnimation MAX_SCRIM_ALPHA_LIGHT / MAX_SCRIM_ALPHA_DARK. */
private const val ScrimAlphaLight = 0.2f
private const val ScrimAlphaDark = 0.8f

/** Interpolators.BACK_GESTURE: cubic-bezier(0.1, 0.1, 0, 1), "matches SystemUI animations". */
private val BackGestureEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/** motionEasingEmphasizedInterpolator (the path version, not the single-bezier approximation). */
private val EmphasizedEasing = PathEasing(
    Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    },
)

/** android.view.animation.DecelerateInterpolator (factor 1), used for the vertical follow. */
private fun decelerate(x: Float): Float = 1f - (1f - x) * (1f - x)

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

/** Maps [value] from [start]..[end] onto 0..1, clamped. */
private fun range(value: Float, start: Float, end: Float): Float =
    ((value - start) / (end - start)).coerceIn(0f, 1f)

internal enum class NavMotionKind { SharedAxis, FadeThrough, PredictiveCard, PredictiveFade }

/**
 * Which motion the transition in flight uses, plus the live predictive back gesture. One instance
 * per NavHost; the NavHost transition lambdas call [decide] and every screen wrapped in
 * [NavMotionScreen] reads it.
 */
@Stable
internal class NavMotion {
    /** Plain fields: written by the NavHost transition lambdas just before the screens compose. */
    var kind: NavMotionKind = NavMotionKind.FadeThrough
        private set
    var forward: Boolean = true
        private set

    /** True from the first to the last event of a back gesture (including its cancel spring). */
    var gestureActive by mutableStateOf(false)
        private set

    /** Last touch, read by the screens' graphics layers - changes only invalidate those. */
    var touchY by mutableFloatStateOf(0f)
        private set
    var startTouchY: Float = 0f
        private set
    var swipeEdge: Int = NavigationEvent.EDGE_LEFT
        private set

    /** Gesture progress when the finger lifted; the post-commit motion starts from there. */
    var releaseProgress: Float = 0f
        private set

    /** Set by [rememberNavMotion]; read synchronously in [decide]. */
    internal var dispatcher: NavigationEventDispatcher? = null

    fun decide(fromTopLevel: Boolean, toTopLevel: Boolean, pop: Boolean) {
        // The NavHost starts seeking on the first gesture event, which can reach it before the
        // collector below has run - so ask the dispatcher directly rather than trust the copy.
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

    /**
     * Length of every screen's timeline. The NavHost seeks the timeline with the gesture progress
     * (so the length does not matter while dragging) and, once the gesture commits, plays the
     * remaining (1 - progress) of it in real time. Stretching the timeline at that moment makes
     * the post-commit phase last exactly [TransitionMillis], wherever the finger was released.
     */
    fun timelineMillis(): Int {
        val predictive =
            kind == NavMotionKind.PredictiveCard || kind == NavMotionKind.PredictiveFade
        if (!predictive || gestureActive) return TransitionMillis
        val remaining = (1f - releaseProgress).coerceAtLeast(0.1f)
        return (TransitionMillis / remaining).roundToInt()
    }
}

/** Collects the activity's back gesture state into a remembered [NavMotion]. */
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

/** Where a screen currently is, as graphicsLayer values. Reused per screen, never allocated. */
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

    /** A surface drawn into the rect (left, top, width = scale * full width), pivot top-left. */
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
    // 0 -> 1 over the transition for both screens.
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
            // Progress-driven while dragging; after the release the rest of the same curve plays.
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

/** DefaultCrossActivityBackAnimation, pre-commit (gesture driven) and post-commit. */
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

    // Pre-commit rects at gesture progress [progress] with the vertical follow scaled by [follow].
    fun preCommit(progress: Float, follow: Float) {
        val p = BackGestureEasing.transform(progress)
        val s = lerp(1f, PredictiveScale, p)
        // Vertical follow: up to half the screen of finger travel, decelerated, and never past
        // the 8dp margin.
        val dy = motion.touchY - motion.startTouchY
        val ratio = decelerate(minOf(height / 2f, abs(dy)) / (height / 2f))
        val yShift = max(0f, (height - height * s) / 2f - margin) * ratio *
            (if (dy < 0f) -1f else 1f) * follow
        val top = (height - height * s) / 2f + yShift
        if (closing) {
            // Scaled into the middle for a right-edge swipe; to the right (8dp from the display
            // edge) for a left-edge one, so the previous screen shows on the side of the finger.
            val centered = (width - width * PredictiveScale) / 2f
            val target = if (motion.swipeEdge == NavigationEvent.EDGE_RIGHT) {
                centered
            } else {
                width - width * PredictiveScale - margin
            }
            rect(left = lerp(0f, target, p), top = top, scale = s)
        } else {
            // 96dp to the left, scaled in sync around its own centre.
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
        // Cancelled without the system's return spring (or its last frames): shrink back along
        // the same path.
        preCommit(q, follow = if (release > 0f) q / release else 0f)
        return
    }
    // Committed: start from where the finger left the surfaces and settle.
    preCommit(release, follow = 1f)
    val linear = range(q, release, 1f)
    val e = EmphasizedEasing.transform(linear)
    val startLeft = translationX
    val startTop = translationY
    val startScale = scale
    if (closing) {
        // Grows back to full size while moving right by its offset + 96dp, fading out in the
        // first fifth (closingAlpha = 1 - 5 * progress).
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

/**
 * The card's clip: the display's own corner radius (RoundedCorner, API 31+), so a full-size card
 * lines up with the physical screen corners exactly, as the system's cross-activity animation does.
 * Resolved lazily (the window insets are not attached on the first composition) and kept.
 */
private class CardShape(private val view: View) {
    private var shape: Shape? = null

    fun get(density: Density): Shape = shape ?: run {
        val radius = view.rootWindowInsets
            ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        val px = if (radius > 0) radius.toFloat() else with(density) { 28.dp.toPx() }
        RoundedCornerShape(px).also { if (view.rootWindowInsets != null) shape = it }
    }
}

/**
 * Wraps one NavHost destination: gives it an opaque background (a shrunk card must not show the
 * screen behind through it), the list edge fade, and the motion described at the top of this file.
 */
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
            // The scrim covers the whole host, not just this card, like the system's scrim layer.
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
