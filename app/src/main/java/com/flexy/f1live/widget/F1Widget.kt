package com.flexy.f1live.widget

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color as AndroidColor
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LocalAppWidgetOptions
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.flexy.f1live.MainActivity
import com.flexy.f1live.R
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.settings.ThemeMode
import com.flexy.f1live.ui.components.formatCountdown
import com.flexy.f1live.ui.components.formatDateRange
import com.flexy.f1live.ui.components.formatDayTime
import com.flexy.f1live.ui.components.formatTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Base of the home-screen widget receivers, all running the same size-adaptive [F1Widget]. */
abstract class F1WidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = F1Widget()
}

/**
 * The one "Laply" entry in the widget picker: 4x2 by default, resizable from 2x1 up, and laid
 * out for whatever size it is given. (The class keeps its first name so widgets already placed
 * with it survive app updates.)
 */
class NextSessionWidgetReceiver : F1WidgetReceiver()

/**
 * Size classes, as the smallest size each layout needs. The values follow the platform sizing
 * table (a cell is roughly 73 dp wide in portrait and 66-118 dp tall): 2 cells are >= 110 dp wide,
 * 4 cells >= 250 dp; 1 row is >= 40 dp tall, 2 rows >= 110 dp. The widget itself is composed for
 * its exact size ([SizeMode.Exact]) and picks its layout with these thresholds; the widget-picker
 * previews, which have no exact size, are rendered at these sizes.
 */
object WidgetSizes {
    val Small = DpSize(110.dp, 40.dp)
    val SmallTall = DpSize(110.dp, 72.dp)
    val Medium = DpSize(110.dp, 110.dp)
    val MediumTall = DpSize(110.dp, 160.dp)
    val Wide = DpSize(250.dp, 40.dp)
    val Large = DpSize(250.dp, 110.dp)
    val LargeTall = DpSize(250.dp, 160.dp)
    val LargeTaller = DpSize(250.dp, 200.dp)
    val LargeTallest = DpSize(250.dp, 250.dp)

    val all: Set<DpSize> = setOf(
        Small, SmallTall, Medium, MediumTall, Wide, Large, LargeTall, LargeTaller, LargeTallest,
    )
}

class F1Widget : GlanceAppWidget() {

    /**
     * Exact: [LocalSize] is the real size of the widget (portrait and landscape, on every resize).
     * Type stays the same size everywhere, like any Material widget; what adapts to the launcher's
     * cells is how many sessions are listed and how the height is shared between them.
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    /** Previews have no exact size (there is no widget yet): the picker gets the size classes. */
    override val previewSizeMode = SizeMode.Responsive(WidgetSizes.all)

    /** Nothing per-widget to store: every instance shows the same calendar. */
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetUpdater.current(context)
        provideContent {
            // A refresh while this session is still running arrives through the flow.
            val latest by WidgetUpdater.snapshot.collectAsState()
            WidgetContent(latest ?: initial)
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val snapshot = WidgetUpdater.previewSnapshot(context)
        provideContent { WidgetContent(snapshot) }
    }
}

// ================================================================== layouts

private val FlagSmall = DpSize(18.dp, 13.dp)
private val FlagMedium = DpSize(24.dp, 17.dp)

/** Session tiles of the lists: two-line and one-line height, and the gap between tiles. */
private val RowHeightTwoLine = 46.dp
private val RowHeightOneLine = 34.dp
private val RowGap = 3.dp

/** Most tiles one list shows: a Glance Column holds at most 10 children. */
private const val MAX_LIST_ROWS = 10

/** The leftover height is shared out between the tiles, up to this much space between two. */
private val RowGapMax = 12.dp



@Composable
private fun WidgetContent(snapshot: WidgetSnapshot) {
    val context = LocalContext.current
    // The blurred glass only where One UI Home hosts this widget: elsewhere it would just be
    // see-through, so any other launcher (and the picker preview) gets the opaque widget.
    val glass = snapshot.glass?.takeIf { OneUi.isOneUiHomeHost(LocalAppWidgetOptions.current) }
    val colors = if (glass != null) {
        WidgetColors.glass(glass.tone, snapshot.dynamicColor)
    } else {
        WidgetColors.of(snapshot.dynamicColor)
    }
    val open = Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_LIVE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    if (glass != null) {
        // One UI Home finds the view with @android:id/background and blurs the wallpaper behind
        // the widget when that view's own background is a translucent *shape* (a flat
        // ColorDrawable only makes it see-through). Glance cannot give a view a drawable
        // background of its own - an image background becomes a separate child view - so the
        // background is a plain layout of ours, the first child, and the content sits on top.
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .clickable(actionStartActivity(open)),
        ) {
            AndroidRemoteViews(glassBackground(context, glass), GlanceModifier.fillMaxSize())
            WidgetBody(snapshot, colors)
        }
    } else {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                // One root background view: a rounded shape drawable tinted with the background
                // colour.
                .background(
                    imageProvider = ImageProvider(R.drawable.widget_background),
                    colorFilter = ColorFilter.tint(colors.background.provider),
                )
                // Also clips the circuit decoration to the rounded corners.
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .clickable(actionStartActivity(open)),
        ) {
            WidgetBody(snapshot, colors)
        }
    }
}

/** Everything inside the widget's background, for whichever root [WidgetContent] chose. */
@Composable
private fun WidgetBody(snapshot: WidgetSnapshot, colors: WidgetColors) {
    val size = LocalSize.current
    val wide = size.width >= WidgetSizes.Wide.width
    val large = wide && size.height >= WidgetSizes.Large.height
    val medium = !wide && size.height >= WidgetSizes.Medium.height
    // The faint circuit is the first child, so it sits under the content without taking part in
    // its layout: top-end in the 2x2 (its inset fills the bottom), bottom-end in the 4x1, and in
    // the 2x1 the whole circuit at its end, clear of the text at the other side. The 4x2 draws
    // the whole circuit in its card instead.
    if (snapshot.entries.isNotEmpty() && !large) {
        when {
            medium -> TrackDecoration(snapshot, DecorationCorner.TopEnd, colors, size.height * 0.5f)
            wide -> TrackDecoration(snapshot, DecorationCorner.BottomEnd, colors, size.height)
            else -> EndOutline(snapshot, colors, size)
        }
    }
    when {
        snapshot.entries.isEmpty() -> EmptyLayout(snapshot, colors)
        large -> LargeLayout(snapshot, colors)
        wide -> WideLayout(snapshot, colors)
        medium -> MediumLayout(snapshot, colors)
        else -> SmallLayout(snapshot, colors)
    }
}

/**
 * The glass background layer: widget_glass_background's rounded shape tinted white (light) or
 * near-black (dark) at the chosen alpha, following the system for [GlassStyle.tone] SYSTEM.
 * Tinting keeps it a shape drawable, which is what One UI blurs behind. Base 255 light / 16 dark
 * and alpha 1..254 as in twidget (MIT, (c) 2026 Josh Skinner). The standings widgets use it too.
 */
internal fun glassBackground(context: Context, glass: GlassStyle): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_glass_background)
    val alpha = glass.alpha.coerceIn(1, 254)
    val light = ColorStateList.valueOf(AndroidColor.argb(alpha, 255, 255, 255))
    val dark = ColorStateList.valueOf(AndroidColor.argb(alpha, 16, 16, 16))
    val id = android.R.id.background
    when (glass.tone) {
        ThemeMode.LIGHT -> views.setColorStateList(id, "setBackgroundTintList", light)
        ThemeMode.DARK -> views.setColorStateList(id, "setBackgroundTintList", dark)
        ThemeMode.SYSTEM -> views.setColorStateList(id, "setBackgroundTintList", light, dark)
    }
    return views
}

/**
 * The whole circuit, muted, at the end of the 2x1, vertically centred with a margin all round: never cut, and kept to the end [ONE_ROW_OUTLINE_WIDTH] of the width so it
 * stays clear of the session and countdown text at the start. Nothing when the circuit
 * background is off or the widget is too short for it.
 */
@Composable
private fun EndOutline(snapshot: WidgetSnapshot, colors: WidgetColors, widget: DpSize) {
    val outline = snapshot.outline ?: return
    if (snapshot.decorations.isEmpty()) return // "Circuit background" off
    val aspect = outline.width.toFloat() / outline.height.coerceAtLeast(1)
    val margin = 8.dp
    val height = minOf(widget.height - margin * 2, widget.width * ONE_ROW_OUTLINE_WIDTH / aspect)
    if (height < 40.dp) return
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Image(
            provider = ImageProvider(outline),
            contentDescription = null,
            alpha = colors.decorationAlpha,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(colors.decoration.provider),
            modifier = GlanceModifier.padding(end = margin + 4.dp).size(height * aspect + margin + 4.dp, height),
        )
    }
}

/** Share of the 2x1's width the end outline may take. */
private const val ONE_ROW_OUTLINE_WIDTH = 0.4f

/**
 * The faint circuit behind the content, pinned to [corner] and running off its two edges (the
 * bitmap is pre-cropped that way, see [WidgetUpdater]): [height] tall, or less when that would make
 * it wider than [maxWidth]. Nothing when the setting is off or the circuit has no outline.
 */
@Composable
private fun TrackDecoration(
    snapshot: WidgetSnapshot,
    corner: DecorationCorner,
    colors: WidgetColors,
    height: Dp,
    maxWidth: Dp = LocalSize.current.width,
    alpha: Float = colors.decorationAlpha,
) {
    val bitmap = snapshot.decorations[corner] ?: return
    // Both sides explicit: with a wrapped width the ImageView would take the bitmap's own pixel
    // width and draw the circuit small, centred in a tall empty box.
    val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
    val drawHeight = minOf(height, maxWidth / aspect)
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = when (corner) {
            DecorationCorner.BottomEnd -> Alignment.BottomEnd
            DecorationCorner.TopEnd -> Alignment.TopEnd
        },
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            alpha = alpha,
            modifier = GlanceModifier.size(drawHeight * aspect, drawHeight),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(colors.decoration.provider),
        )
    }
}

/**
 * 2x1: flag and session over the countdown, as one block centred vertically - with the day and
 * time between them when the cell is tall enough (Pixel), two lines on One UI's short 2x1.
 */
@Composable
private fun SmallLayout(snapshot: WidgetSnapshot, colors: WidgetColors) {
    val context = LocalContext.current
    val now = snapshot.nowMillis
    val hero = heroEntry(snapshot)
    val tall = LocalSize.current.height >= WidgetSizes.SmallTall.height
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Flag(snapshot, hero.weekend, FlagSmall)
            WText(
                text = hero.session.name,
                color = colors.onSurface,
                size = 14.sp,
                weight = FontWeight.Bold,
            )
        }
        if (tall) {
            WText(
                text = whenLabel(context, hero.startUtcMillis, now),
                color = colors.onSurfaceVariant,
                size = 12.sp,
            )
            Spacer(GlanceModifier.height(4.dp))
        } else {
            Spacer(GlanceModifier.height(2.dp))
        }
        HeroCountdown(snapshot, hero, colors, if (tall) 22.sp else 16.sp)
    }
}

/** 4x1: flag, session and GP on one line, countdown pill at the end. */
@Composable
private fun WideLayout(snapshot: WidgetSnapshot, colors: WidgetColors) {
    val context = LocalContext.current
    val hero = heroEntry(snapshot)
    Row(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Flag(snapshot, hero.weekend, FlagMedium, gap = 12.dp)
        Column(modifier = GlanceModifier.defaultWeight()) {
            WText(hero.session.name, colors.onSurface, 14.sp, FontWeight.Bold)
            WText(
                text = WidgetPlanner.shortName(hero.weekend.name) + " · " +
                    whenLabel(context, hero.startUtcMillis, snapshot.nowMillis),
                color = colors.onSurfaceVariant,
                size = 12.sp,
            )
        }
        Spacer(GlanceModifier.width(8.dp))
        CountdownPill(snapshot, hero, colors)
    }
}

/**
 * 2x2: overline, flag + Grand Prix, then the next session with a big countdown - in an inset like
 * the in-app card when there is room, flat when the widget is only two short rows tall.
 *
 * Narrow but tall (2x3, 3x4 on 5-column grids) it also lists the sessions after that one below
 * the inset, as many whole rows as the height holds.
 */
@Composable
private fun MediumLayout(snapshot: WidgetSnapshot, colors: WidgetColors) {
    val context = LocalContext.current
    val now = snapshot.nowMillis
    val hero = heroEntry(snapshot)
    val height = LocalSize.current.height
    val roomy = height >= WidgetSizes.MediumTall.height
    val padding = (if (roomy) 14.dp else 12.dp)
    // Room left under the roomy card (padding, header, inset), in whole list rows.
    val cardHeight = MEDIUM_CARD_HEIGHT
    val listRows = if (height >= MEDIUM_LIST_MIN_HEIGHT) {
        rowsThatFit(height - cardHeight - padding, RowHeightTwoLine)
            .coerceAtMost(snapshot.entries.size - 1)
    } else {
        0
    }
    Column(modifier = GlanceModifier.fillMaxSize().padding(padding)) {
        Overline(context, hero.weekend, now, colors, modifier = GlanceModifier.padding(start = 2.dp))
        Spacer(GlanceModifier.height(4.dp))
        Row(
            modifier = GlanceModifier.padding(start = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Flag(snapshot, hero.weekend, FlagMedium, gap = 8.dp)
            WText(WidgetPlanner.shortName(hero.weekend.name), colors.onSurface, 16.sp, FontWeight.Bold)
        }
        if (listRows > 0) Spacer(GlanceModifier.height(10.dp)) else Spacer(GlanceModifier.defaultWeight())
        if (roomy) {
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(colors.inset.provider)
                    .cornerRadius(android.R.dimen.system_app_widget_inner_radius)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                WText(hero.session.name, colors.onSurface, 13.sp, FontWeight.Bold)
                WText(whenLabel(context, hero.startUtcMillis, now), colors.onSurfaceVariant, 12.sp)
                Spacer(GlanceModifier.height(4.dp))
                HeroCountdown(snapshot, hero, colors, 24.sp)
            }
        } else {
            WText(
                text = hero.session.name + " · " + whenLabel(context, hero.startUtcMillis, now),
                color = colors.onSurfaceVariant,
                size = 12.sp,
                modifier = GlanceModifier.padding(start = 2.dp),
            )
            HeroCountdown(snapshot, hero, colors, 22.sp, GlanceModifier.padding(start = 2.dp))
        }
        if (listRows > 0) {
            Spacer(GlanceModifier.height(6.dp))
            SessionList(
                snapshot = snapshot,
                entries = snapshot.entries.drop(1).take(listRows),
                cardWeekend = hero.weekend,
                // The inset already counts down to the first upcoming session.
                countdownTarget = WidgetPlanner.countdownTarget(snapshot.entries, now).takeIf { it != hero },
                twoLine = true,
                space = height - cardHeight - padding,
                colors = colors,
            )
        }
    }
}

/** From this height a narrow widget lists more sessions under the 2x2 card (about 3 rows). */
private val MEDIUM_LIST_MIN_HEIGHT = 300.dp

/**
 * Height the roomy 2x2 card takes before the list: top padding, the header, the 10 dp
 * gap, the inset and the 6 dp gap above the list - rounded up so a row is never clipped.
 */
private val MEDIUM_CARD_HEIGHT = 176.dp

/**
 * 4x2: the Grand Prix card (flag, name, circuit, dates, track outline) on the left, the next
 * sessions on the right as a segmented list - the first upcoming one with the countdown, a running
 * one with LIVE. The list holds as many whole rows as the height allows, the leftover height shared
 * between them. The circuit fills what the card's text leaves free: muted in the primary colour
 * when the circuit-background setting is on, the plain tinted outline when it is off.
 */
@Composable
private fun LargeLayout(snapshot: WidgetSnapshot, colors: WidgetColors) {
    val context = LocalContext.current
    val now = snapshot.nowMillis
    val weekend = snapshot.entries.first().weekend
    val size = LocalSize.current
    val height = size.height
    val roomy = height >= WidgetSizes.LargeTall.height
    val twoLineRows = height >= WidgetSizes.LargeTaller.height
    val padding = 12.dp
    val countdownTarget = WidgetPlanner.countdownTarget(snapshot.entries, now)
    val outline = snapshot.outline?.takeIf { roomy }
    val outlineSize = outline?.let {
        largeOutlineSize(size, context.resources.configuration.fontScale, it.width.toFloat() / it.height)
    }
    val muted = snapshot.decorations.isNotEmpty()

    Row(modifier = GlanceModifier.fillMaxSize().padding(padding)) {
        Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
            // Circuit background on but no free room under the text (a short 4x2): the whole
            // circuit, faint, behind the card instead - drawn once either way.
            if (muted && outline != null && outlineSize == null) {
                FaintCardOutline(outline, size, colors)
            }
            Column(modifier = GlanceModifier.fillMaxSize().padding(start = 4.dp, top = 2.dp, end = 2.dp)) {
                Overline(context, weekend, now, colors)
                Spacer(GlanceModifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Flag(snapshot, weekend, FlagMedium, gap = 8.dp)
                    WText(WidgetPlanner.shortName(weekend.name), colors.onSurface, 18.sp, FontWeight.Bold)
                }
                if (roomy) {
                    Spacer(GlanceModifier.height(2.dp))
                    WText(circuitLine(weekend), colors.onSurfaceVariant, 12.sp)
                }
                Spacer(GlanceModifier.height(2.dp))
                WText(weekendDates(weekend), colors.onSurface, 12.sp, FontWeight.Medium)
                if (outline != null && outlineSize != null) {
                    // The free part of the card, the whole circuit centred in it with a margin.
                    Box(
                        modifier = GlanceModifier.defaultWeight().fillMaxWidth().padding(top = 8.dp, bottom = 4.dp, end = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            provider = ImageProvider(outline),
                            contentDescription = null,
                            alpha = if (muted) LARGE_OUTLINE_MUTED_ALPHA else 1f,
                            contentScale = ContentScale.Fit,
                            // One dark line on transparency: the tint repaints it in the theme colour.
                            colorFilter = ColorFilter.tint(
                                if (muted) colors.decoration.provider else colors.onSurfaceVariant.provider,
                            ),
                            modifier = GlanceModifier.size(outlineSize.width, outlineSize.height),
                        )
                    }
                }
            }
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionList(
                snapshot = snapshot,
                entries = snapshot.entries,
                cardWeekend = weekend,
                countdownTarget = countdownTarget,
                twoLine = twoLineRows,
                space = height - padding * 2,
                colors = colors,
            )
        }
    }
}

/**
 * The whole circuit, faint, filling the bottom of the 4x2 card's column behind its text: for a
 * card too short to give the outline a place of its own. As wide as the column, never taller than
 * the widget's inner height.
 */
@Composable
private fun FaintCardOutline(outline: android.graphics.Bitmap, widget: DpSize, colors: WidgetColors) {
    val aspect = outline.width.toFloat() / outline.height.coerceAtLeast(1)
    val columnWidth = (widget.width - 24.dp - 10.dp) / 2
    val height = minOf(columnWidth / aspect, widget.height - 24.dp)
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Image(
            provider = ImageProvider(outline),
            contentDescription = null,
            alpha = colors.decorationAlpha,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(colors.decoration.provider),
            modifier = GlanceModifier.size(height * aspect, height),
        )
    }
}

/** Opacity of the muted (circuit-background) outline in the 4x2 card: soft, still recognisable. */
private const val LARGE_OUTLINE_MUTED_ALPHA = 0.45f

/**
 * The outline's size in the 4x2 card: as wide as the card column allows, at its own aspect ratio,
 * no taller than the height the text above leaves (estimated conservatively, so it is never cut).
 * Null when too little is left to be worth drawing.
 */
private fun largeOutlineSize(widget: DpSize, fontScale: Float, aspect: Float): DpSize? {
    // Generous line height (Google Sans and One UI fonts run tall), so the estimate errs small.
    fun line(sp: Float) = (sp * 1.5f * fontScale).dp
    // Row padding 12 + column top 2, overline, 6, name (flag 17 dp), circuit, dates, box margins.
    val text = 14.dp + line(11f) + 6.dp + maxOf(line(18f), 17.dp) + 2.dp + line(12f) + 2.dp + line(12f)
    val freeHeight = widget.height - text - 12.dp - 8.dp - 4.dp - 6.dp
    // Half of the row after its padding and the 10 dp gap, minus the column and box end padding.
    val freeWidth = (widget.width - 24.dp - 10.dp) / 2 - 6.dp - 8.dp
    val width = minOf(freeWidth, freeHeight * aspect)
    if (width < 48.dp || width / aspect < 40.dp) return null
    return DpSize(width, width / aspect)
}

/**
 * Session tiles in [space]: as many whole, compact tiles as fit, the height left over shared out
 * evenly between them (up to [RowGapMax]), the list centred - no empty band, no stretched tiles.
 */
@Composable
private fun SessionList(
    snapshot: WidgetSnapshot,
    entries: List<WidgetEntry>,
    cardWeekend: RaceWeekend,
    countdownTarget: WidgetEntry?,
    twoLine: Boolean,
    space: Dp,
    colors: WidgetColors,
) {
    val rowHeight = if (twoLine) RowHeightTwoLine else RowHeightOneLine
    val fit = rowsThatFit(space, rowHeight).coerceIn(1, MAX_LIST_ROWS)
    val rows = entries.take(fit)
    val gap = if (rows.size > 1 && rows.size == fit) {
        ((space - rowHeight * rows.size) / (rows.size - 1)).coerceIn(RowGap, RowGapMax)
    } else {
        RowGap
    }
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        rows.forEachIndexed { index, entry ->
            // The gap is the top padding of a wrapper, not a Spacer: a Glance Column takes at
            // most 10 children, and a tall widget lists up to [MAX_LIST_ROWS] tiles.
            Box(modifier = GlanceModifier.fillMaxWidth().padding(top = if (index > 0) gap else 0.dp)) {
                SessionRow(
                    snapshot = snapshot,
                    entry = entry,
                    cardWeekend = cardWeekend,
                    showCountdown = entry.isLive(snapshot.nowMillis) || entry == countdownTarget,
                    twoLine = twoLine,
                    height = rowHeight,
                    colors = colors,
                )
            }
        }
    }
}

/** Whole list rows of [rowHeight], [RowGap] apart, that fit in [space]. */
private fun rowsThatFit(space: Dp, rowHeight: Dp): Int =
    WidgetPlanner.rowsThatFit(space.value, rowHeight.value, RowGap.value)

/**
 * One session tile: name over day and time (one line on short widgets), and at the end, centred,
 * the countdown pill or LIVE. The text takes the remaining width and only ellipsizes when it
 * genuinely runs out of it.
 */
@Composable
private fun SessionRow(
    snapshot: WidgetSnapshot,
    entry: WidgetEntry,
    cardWeekend: RaceWeekend,
    showCountdown: Boolean,
    twoLine: Boolean,
    height: Dp,
    colors: WidgetColors,
) {
    val context = LocalContext.current
    val otherWeekend = entry.weekend.round != cardWeekend.round || entry.weekend.season != cardWeekend.season
    val whenText = whenLabel(context, entry.startUtcMillis, snapshot.nowMillis)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(height)
            .background(colors.inset.provider)
            .cornerRadius(14.dp)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (otherWeekend) Flag(snapshot, entry.weekend, DpSize(16.dp, 12.dp), gap = 6.dp)
        if (twoLine) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                WText(entry.session.name, colors.onSurface, 14.sp, FontWeight.Bold)
                WText(
                    text = if (otherWeekend) WidgetPlanner.shortName(entry.weekend.name) + " · " + whenText else whenText,
                    color = colors.onSurfaceVariant,
                    size = 12.sp,
                )
            }
            if (showCountdown) {
                Spacer(GlanceModifier.width(8.dp))
                CountdownPill(snapshot, entry, colors, compact = true)
            }
        } else {
            WText(entry.session.name, colors.onSurface, 14.sp, FontWeight.Bold, modifier = GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(8.dp))
            if (showCountdown) {
                CountdownPill(snapshot, entry, colors, compact = true)
            } else {
                WText(shortWhen(entry.startUtcMillis, snapshot.nowMillis), colors.onSurfaceVariant, 12.sp)
            }
        }
    }
}

@Composable
private fun EmptyLayout(snapshot: WidgetSnapshot, colors: WidgetColors) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WText(context.getString(R.string.widget_empty_title), colors.onSurface, 14.sp, FontWeight.Bold)
        if (!snapshot.calendarLoaded && LocalSize.current.height >= WidgetSizes.SmallTall.height) {
            WText(context.getString(R.string.widget_empty_body), colors.onSurfaceVariant, 12.sp, maxLines = 2)
        }
    }
}

// ================================================================== pieces

@Composable
private fun Overline(
    context: Context,
    weekend: RaceWeekend,
    now: Long,
    colors: WidgetColors,
    modifier: GlanceModifier = GlanceModifier,
) {
    val res = if (WidgetPlanner.weekendUnderway(weekend, now)) {
        R.string.widget_this_weekend_overline
    } else {
        R.string.next_round_overline
    }
    WText(
        text = context.getString(res, weekend.round).uppercase(Locale.getDefault()),
        color = colors.primary,
        size = 11.sp,
        weight = FontWeight.Bold,
        modifier = modifier,
    )
}

/** The flag, rounded like the in-app CountryFlag, followed by [gap]; nothing when not loaded. */
@Composable
private fun Flag(snapshot: WidgetSnapshot, weekend: RaceWeekend, size: DpSize, gap: Dp = 6.dp) {
    val bitmap = weekend.countryCode?.lowercase()?.let(snapshot.flags::get) ?: return
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = GlanceModifier.size(size.width, size.height).cornerRadius(3.dp),
    )
    Spacer(GlanceModifier.width(gap))
}

/** The big countdown of the small and medium layouts ("in 16h 41m"), or a LIVE pill. */
@Composable
private fun HeroCountdown(
    snapshot: WidgetSnapshot,
    entry: WidgetEntry,
    colors: WidgetColors,
    size: TextUnit,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    when (val countdown = WidgetPlanner.countdown(entry, snapshot.nowMillis)) {
        Countdown.Live -> Box(modifier) { LivePill(context, colors) }
        is Countdown.Until -> WText(
            text = formatCountdown(context.resources, countdown.units),
            color = colors.primary,
            size = size,
            weight = FontWeight.Bold,
            modifier = modifier,
        )
    }
}

/** "in 1d 13h" on primary-container, the in-app card's pill; LIVE on red while it runs. */
@Composable
private fun CountdownPill(snapshot: WidgetSnapshot, entry: WidgetEntry, colors: WidgetColors, compact: Boolean = false) {
    val context = LocalContext.current
    val countdown = WidgetPlanner.countdown(entry, snapshot.nowMillis)
    if (countdown !is Countdown.Until) {
        LivePill(context, colors, compact)
        return
    }
    Box(
        modifier = GlanceModifier
            .background(colors.pill.provider)
            .cornerRadius((if (compact) 11.dp else 14.dp))
            .padding(
                horizontal = (if (compact) 9.dp else 12.dp),
                vertical = (if (compact) 3.dp else 6.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        WText(
            text = formatCountdown(context.resources, countdown.units),
            color = colors.onPill,
            size = if (compact) 12.sp else 13.sp,
            weight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LivePill(context: Context, colors: WidgetColors, compact: Boolean = false) {
    Row(
        modifier = GlanceModifier
            .background(colors.live.provider)
            .cornerRadius((if (compact) 11.dp else 14.dp))
            .padding(
                horizontal = (if (compact) 9.dp else 12.dp),
                vertical = (if (compact) 3.dp else 6.dp),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier.size(6.dp).background(colors.onLive.provider).cornerRadius(3.dp),
            content = {},
        )
        Spacer(GlanceModifier.width(6.dp))
        WText(context.getString(R.string.widget_live), colors.onLive, if (compact) 12.sp else 13.sp, FontWeight.Bold)
    }
}

/** One line of text, ellipsized. */
@Composable
private fun WText(
    text: String,
    color: WidgetColor,
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    modifier: GlanceModifier = GlanceModifier,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color.provider, fontSize = size, fontWeight = weight),
        maxLines = maxLines,
    )
}

// ================================================================== helpers

/** A running session if there is one, else the next: what the small and medium layouts feature. */
private fun heroEntry(snapshot: WidgetSnapshot): WidgetEntry = snapshot.entries.first()

private val weekdayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

/** "Today · 15:00", "Tomorrow · 15:00", "Sat · 15:00", or "Sat 4 Oct · 15:00" further out. */
private fun whenLabel(context: Context, start: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val time = formatTime(start)
    return when (WidgetPlanner.dayLabel(start, now, zone)) {
        DayLabel.TODAY -> context.getString(R.string.widget_when_today, time)
        DayLabel.TOMORROW -> context.getString(R.string.widget_when_tomorrow, time)
        DayLabel.THIS_WEEK -> context.getString(
            R.string.widget_when_weekday,
            time,
            weekdayFormatter.format(Instant.ofEpochMilli(start).atZone(zone)),
        )
        DayLabel.LATER -> formatDayTime(start)
    }
}

/** "Sat 15:00" for the one-line rows, where there is no room for "Tomorrow". */
private fun shortWhen(start: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    return if (WidgetPlanner.dayLabel(start, now, zone) == DayLabel.TODAY) {
        formatTime(start)
    } else {
        weekdayFormatter.format(Instant.ofEpochMilli(start).atZone(zone)) + " " + formatTime(start)
    }
}

private fun circuitLine(weekend: RaceWeekend): String =
    listOf(weekend.circuitName, weekend.locality).filter { it.isNotBlank() }.distinct().joinToString(" · ")

/** First to last known session of the weekend, e.g. "4 – 6 Sep". */
private fun weekendDates(weekend: RaceWeekend): String {
    val starts = weekend.sessions.mapNotNull { it.startUtcMillis }
    return formatDateRange(starts.minOrNull(), starts.maxOrNull() ?: weekend.raceStartUtcMillis)
}
