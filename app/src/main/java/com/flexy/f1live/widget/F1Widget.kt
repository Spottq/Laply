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

abstract class F1WidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = F1Widget()
}

// Keeps its first name so widgets already placed with it survive app updates.
class NextSessionWidgetReceiver : F1WidgetReceiver()

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

    override val sizeMode: SizeMode = SizeMode.Exact

    override val previewSizeMode = SizeMode.Responsive(WidgetSizes.all)

    override val stateDefinition: GlanceStateDefinition<*>? = null

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetUpdater.current(context)
        provideContent {
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

private val RowHeightTwoLine = 46.dp
private val RowHeightOneLine = 34.dp
private val RowGap = 3.dp

private const val MAX_LIST_ROWS = 10

private val RowGapMax = 12.dp



@Composable
private fun WidgetContent(snapshot: WidgetSnapshot) {
    val context = LocalContext.current
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
        // One UI blurs only behind a translucent shape on @android:id/background, which Glance can't make.
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
                .background(
                    imageProvider = ImageProvider(R.drawable.widget_background),
                    colorFilter = ColorFilter.tint(colors.background.provider),
                )
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .clickable(actionStartActivity(open)),
        ) {
            WidgetBody(snapshot, colors)
        }
    }
}

@Composable
private fun WidgetBody(snapshot: WidgetSnapshot, colors: WidgetColors) {
    val size = LocalSize.current
    val wide = size.width >= WidgetSizes.Wide.width
    val large = wide && size.height >= WidgetSizes.Large.height
    val medium = !wide && size.height >= WidgetSizes.Medium.height
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

@Composable
private fun EndOutline(snapshot: WidgetSnapshot, colors: WidgetColors, widget: DpSize) {
    val outline = snapshot.outline ?: return
    if (snapshot.decorations.isEmpty()) return
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

private const val ONE_ROW_OUTLINE_WIDTH = 0.4f

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

@Composable
private fun MediumLayout(snapshot: WidgetSnapshot, colors: WidgetColors) {
    val context = LocalContext.current
    val now = snapshot.nowMillis
    val hero = heroEntry(snapshot)
    val height = LocalSize.current.height
    val roomy = height >= WidgetSizes.MediumTall.height
    val padding = (if (roomy) 14.dp else 12.dp)
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
                countdownTarget = WidgetPlanner.countdownTarget(snapshot.entries, now).takeIf { it != hero },
                twoLine = true,
                space = height - cardHeight - padding,
                colors = colors,
            )
        }
    }
}

private val MEDIUM_LIST_MIN_HEIGHT = 300.dp

private val MEDIUM_CARD_HEIGHT = 176.dp

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
                    Box(
                        modifier = GlanceModifier.defaultWeight().fillMaxWidth().padding(top = 8.dp, bottom = 4.dp, end = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            provider = ImageProvider(outline),
                            contentDescription = null,
                            alpha = if (muted) LARGE_OUTLINE_MUTED_ALPHA else 1f,
                            contentScale = ContentScale.Fit,
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

private const val LARGE_OUTLINE_MUTED_ALPHA = 0.45f

private fun largeOutlineSize(widget: DpSize, fontScale: Float, aspect: Float): DpSize? {
    fun line(sp: Float) = (sp * 1.5f * fontScale).dp
    val text = 14.dp + line(11f) + 6.dp + maxOf(line(18f), 17.dp) + 2.dp + line(12f) + 2.dp + line(12f)
    val freeHeight = widget.height - text - 12.dp - 8.dp - 4.dp - 6.dp
    val freeWidth = (widget.width - 24.dp - 10.dp) / 2 - 6.dp - 8.dp
    val width = minOf(freeWidth, freeHeight * aspect)
    if (width < 48.dp || width / aspect < 40.dp) return null
    return DpSize(width, width / aspect)
}

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
            // Padding, not a Spacer: a Glance Column holds at most 10 children.
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

private fun rowsThatFit(space: Dp, rowHeight: Dp): Int =
    WidgetPlanner.rowsThatFit(space.value, rowHeight.value, RowGap.value)

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

private fun heroEntry(snapshot: WidgetSnapshot): WidgetEntry = snapshot.entries.first()

private val weekdayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

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

private fun weekendDates(weekend: RaceWeekend): String {
    val starts = weekend.sessions.mapNotNull { it.startUtcMillis }
    return formatDateRange(starts.minOrNull(), starts.maxOrNull() ?: weekend.raceStartUtcMillis)
}
