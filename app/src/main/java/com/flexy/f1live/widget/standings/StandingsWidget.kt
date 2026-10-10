package com.flexy.f1live.widget.standings

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.flexy.f1live.MainActivity
import com.flexy.f1live.R
import com.flexy.f1live.data.TitleFight
import com.flexy.f1live.ui.components.teamColor
import com.flexy.f1live.widget.OneUi
import com.flexy.f1live.widget.WidgetColor
import com.flexy.f1live.widget.WidgetColors
import com.flexy.f1live.widget.glassBackground
import java.util.Locale

/** The three standings entries in the widget picker. */
enum class StandingsWidgetKind {
    /** The drivers' table. */
    Drivers,

    /** The constructors' table. */
    Teams,

    /** The leader of the drivers' championship and the gaps behind them. */
    Leader;

    val receiver: Class<out GlanceAppWidgetReceiver>
        get() = when (this) {
            Drivers -> DriverStandingsWidgetReceiver::class.java
            Teams -> TeamStandingsWidgetReceiver::class.java
            Leader -> TitleFightWidgetReceiver::class.java
        }

    fun newWidget(): StandingsWidget = when (this) {
        Drivers -> DriverStandingsWidget()
        Teams -> TeamStandingsWidget()
        Leader -> TitleFightWidget()
    }
}

class DriverStandingsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DriverStandingsWidget()
}

class TeamStandingsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TeamStandingsWidget()
}

class TitleFightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TitleFightWidget()
}

class DriverStandingsWidget : StandingsWidget(StandingsWidgetKind.Drivers)

class TeamStandingsWidget : StandingsWidget(StandingsWidgetKind.Teams)

class TitleFightWidget : StandingsWidget(StandingsWidgetKind.Leader)

/**
 * Size classes of the standings widgets, as the smallest size each layout needs (see WidgetSizes
 * for how cells map to dp). Narrower than [WideWidth] the tables use driver codes and the title
 * fight stacks into one column.
 */
object StandingsWidgetSizes {
    val WideWidth = 250.dp

    val Medium = DpSize(110.dp, 110.dp)
    val MediumTall = DpSize(110.dp, 200.dp)
    val Large = DpSize(250.dp, 110.dp)
    val LargeTall = DpSize(250.dp, 200.dp)
    val LargeTallest = DpSize(250.dp, 300.dp)

    val all: Set<DpSize> = setOf(Medium, MediumTall, Large, LargeTall, LargeTallest)
}

/**
 * One standings widget: composed for its exact size, with as many rows as that size holds. The
 * table and the colours come from [StandingsWidgetUpdater]; a tap opens the Standings tab.
 */
abstract class StandingsWidget(private val kind: StandingsWidgetKind) : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    /** Previews have no exact size (there is no widget yet): the picker gets the size classes. */
    override val previewSizeMode = SizeMode.Responsive(StandingsWidgetSizes.all)

    /** Nothing per widget to store: every instance shows the same table. */
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = StandingsWidgetUpdater.current(context)
        provideContent {
            // A refresh while this session is still running arrives through the flow.
            val latest by StandingsWidgetUpdater.snapshot.collectAsState()
            StandingsContent(kind, latest ?: initial)
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val snapshot = StandingsWidgetUpdater.previewSnapshot(context)
        provideContent { StandingsContent(kind, snapshot) }
    }
}

// ================================================================== layouts

private val HorizontalPadding = 14.dp
private val VerticalPadding = 12.dp

/** Between the overline and the first row. */
private val HeaderGap = 6.dp

/** A table row at the least, and at the most when the height is shared out between the rows. */
private val TableRowHeight = 22.dp
private val TableRowMaxHeight = 30.dp

/** The position column, right-aligned so "9" and "10" line up. */
private val PositionWidth = 20.dp

/** The title-fight tiles: their height, the gap between two, the dashed line between them. */
private val TileHeight = 32.dp
private val TileGap = 5.dp
private val TileLineHeight = 10.dp

/** Tiles in the title fight's column at most (a Glance Column holds 10 children; one is the line). */
private const val MAX_TILES = 8

/** Rows per Glance Column: it holds at most 10 children. */
private const val COLUMN_CHILDREN = 10

/** Below this height the 4x2 title fight leaves out the line under the leader's points. */
private val LeaderFooterMinHeight = 150.dp

@Composable
private fun StandingsContent(kind: StandingsWidgetKind, snapshot: StandingsSnapshot) {
    val context = LocalContext.current
    // The blurred glass only where One UI Home hosts the widget, as for the session widget.
    val glass = snapshot.glass?.takeIf { OneUi.isOneUiHomeHost(LocalAppWidgetOptions.current) }
    val colors = if (glass != null) {
        WidgetColors.glass(glass.tone, snapshot.dynamicColor)
    } else {
        WidgetColors.of(snapshot.dynamicColor)
    }
    val open = Intent(context, MainActivity::class.java)
        .setAction(
            if (kind == StandingsWidgetKind.Teams) {
                MainActivity.ACTION_OPEN_TEAM_STANDINGS
            } else {
                MainActivity.ACTION_OPEN_DRIVER_STANDINGS
            },
        )
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    if (glass != null) {
        // The glass layer is a plain layout of ours under the content, see F1Widget.
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .clickable(actionStartActivity(open)),
        ) {
            AndroidRemoteViews(glassBackground(context, glass), GlanceModifier.fillMaxSize())
            StandingsBody(kind, snapshot, colors)
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
            StandingsBody(kind, snapshot, colors)
        }
    }
}

@Composable
private fun StandingsBody(kind: StandingsWidgetKind, snapshot: StandingsSnapshot, colors: WidgetColors) {
    val entries = if (kind == StandingsWidgetKind.Teams) snapshot.teams else snapshot.drivers
    when {
        entries.isEmpty() -> EmptyLayout(colors)
        kind == StandingsWidgetKind.Leader -> LeaderLayout(snapshot, colors)
        else -> TableLayout(kind, snapshot, colors)
    }
}

/**
 * The table: overline ("DRIVERS · AFTER R20", and on wide widgets the points left), then as many
 * rows as fit - position, team colour, name, points - with the dashed title line after the last
 * one who can still win. The height left over is shared out between the rows.
 */
@Composable
private fun TableLayout(kind: StandingsWidgetKind, snapshot: StandingsSnapshot, colors: WidgetColors) {
    val context = LocalContext.current
    val size = LocalSize.current
    val fontScale = context.resources.configuration.fontScale
    val wide = size.width >= StandingsWidgetSizes.WideWidth
    val teams = kind == StandingsWidgetKind.Teams
    val entries = if (teams) snapshot.teams else snapshot.drivers
    val fight = if (teams) snapshot.teamsFight else snapshot.driversFight

    val rowHeight = maxOf(TableRowHeight, textLine(14f, fontScale))
    val lineHeight = if (wide) maxOf(14.dp, textLine(10f, fontScale)) else 9.dp
    val space = size.height - VerticalPadding * 2 - textLine(11f, fontScale) - HeaderGap
    val plan = StandingsWidgetPlanner.tablePlan(
        space = space.value,
        rowHeight = rowHeight.value,
        lineHeight = lineHeight.value,
        entries = entries.size,
        contenders = fight?.contenders,
    )
    val lineAfter = plan.lineAfter
    val rowsSpace = space - (if (lineAfter != null) lineHeight else 0.dp)
    val stretched = if (plan.rows > 0) {
        maxOf(rowHeight, minOf(rowsSpace / plan.rows, TableRowMaxHeight))
    } else {
        rowHeight
    }
    val shown = entries.take(plan.rows)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = HorizontalPadding, vertical = VerticalPadding),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = HeaderGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = when {
                !teams -> R.string.standings_widget_drivers
                wide -> R.string.standings_widget_constructors
                else -> R.string.standings_widget_teams
            }
            WText(
                text = overline(context, label, snapshot, wide),
                color = colors.primary,
                size = 11.sp,
                weight = FontWeight.Bold,
                modifier = GlanceModifier.defaultWeight(),
            )
            val detail = if (wide) fightDetail(context, fight) else null
            if (detail != null) {
                Spacer(GlanceModifier.width(8.dp))
                WText(detail, colors.onSurfaceVariant, 11.sp)
            }
        }
        if (lineAfter == null) {
            TableRows(shown, wide, stretched, colors)
        } else {
            TableRows(shown.take(lineAfter), wide, stretched, colors)
            TitleLine(captioned = wide, decided = fight?.decided == true, height = lineHeight, colors = colors)
            TableRows(shown.drop(lineAfter), wide, stretched, colors)
        }
    }
}

/** [entries] as table rows, ten to a Column. */
@Composable
private fun TableRows(entries: List<StandingsEntry>, wide: Boolean, rowHeight: Dp, colors: WidgetColors) {
    entries.chunked(COLUMN_CHILDREN).forEach { chunk ->
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            chunk.forEach { entry -> TableRow(entry, wide, rowHeight, colors) }
        }
    }
}

@Composable
private fun TableRow(entry: StandingsEntry, wide: Boolean, height: Dp, colors: WidgetColors) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WText(
            text = entry.position.toString(),
            color = colors.onSurface,
            size = 13.sp,
            weight = FontWeight.Bold,
            modifier = GlanceModifier.width(PositionWidth),
            align = TextAlign.End,
        )
        Spacer(GlanceModifier.width(8.dp))
        TeamStripe(entry)
        Spacer(GlanceModifier.width(8.dp))
        WText(
            text = if (wide) entry.name else entry.code,
            color = colors.onSurface,
            size = 14.sp,
            weight = FontWeight.Medium,
            modifier = GlanceModifier.defaultWeight(),
        )
        Spacer(GlanceModifier.width(8.dp))
        WText(StandingsWidgetPlanner.formatPoints(entry.points), colors.onSurface, 14.sp, FontWeight.Bold)
    }
}

/** The title fight: the leader, big, and the drivers chasing them with their gaps. */
@Composable
private fun LeaderLayout(snapshot: StandingsSnapshot, colors: WidgetColors) {
    if (LocalSize.current.width >= StandingsWidgetSizes.WideWidth) {
        WideLeader(snapshot, colors)
    } else {
        NarrowLeader(snapshot, colors)
    }
}

/** 4x2 and up: the leader's card on the left, the chasers as tiles on the right. */
@Composable
private fun WideLeader(snapshot: StandingsSnapshot, colors: WidgetColors) {
    val context = LocalContext.current
    val size = LocalSize.current
    val leader = snapshot.drivers.first()
    val fight = snapshot.driversFight
    val footer = if (size.height >= LeaderFooterMinHeight) leaderFooter(context, fight) else null

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = HorizontalPadding, vertical = VerticalPadding),
    ) {
        Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
            WText(leaderOverline(context, snapshot, fight, wide = true), colors.primary, 11.sp, FontWeight.Bold)
            Spacer(GlanceModifier.height(6.dp))
            WText(leader.name, colors.onSurface, 18.sp, FontWeight.Bold)
            WText(leaderTeam(context, leader), colors.onSurfaceVariant, 12.sp)
            Spacer(GlanceModifier.defaultWeight())
            LeaderPoints(leader, colors, 30.sp)
            if (footer != null) WText(footer, colors.onSurfaceVariant, 12.sp)
        }
        Spacer(GlanceModifier.width(12.dp))
        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChaserTiles(
                leader = leader,
                chasers = snapshot.drivers.drop(1),
                fight = fight,
                space = size.height - VerticalPadding * 2,
                fontScale = context.resources.configuration.fontScale,
                colors = colors,
            )
        }
    }
}

/**
 * The chasers as tiles, as many as the height holds, with the dashed line after the last one who
 * can still win. No line when nobody can: the leader's footer says the title is decided.
 */
@Composable
private fun ChaserTiles(
    leader: StandingsEntry,
    chasers: List<StandingsEntry>,
    fight: TitleFight?,
    space: Dp,
    fontScale: Float,
    colors: WidgetColors,
) {
    val tileHeight = maxOf(TileHeight, textLine(13f, fontScale) + 10.dp)
    // Every tile but the first comes with the gap above it, hence the gap added to both.
    val plan = StandingsWidgetPlanner.tablePlan(
        space = (space + TileGap).value,
        rowHeight = (tileHeight + TileGap).value,
        lineHeight = TileLineHeight.value,
        entries = chasers.size,
        // The leader is the first contender.
        contenders = fight?.contenders?.minus(1)?.takeIf { it > 0 },
        maxRows = MAX_TILES,
    )
    val lineAfter = plan.lineAfter
    chasers.take(plan.rows).forEachIndexed { index, entry ->
        if (index == lineAfter) {
            TitleLine(captioned = false, decided = false, height = TileLineHeight, colors = colors)
        }
        // The gap is the top padding of a wrapper, not a Spacer, to stay within 10 children.
        val gap = if (index == 0 || index == lineAfter) 0.dp else TileGap
        Box(modifier = GlanceModifier.fillMaxWidth().padding(top = gap)) {
            ChaserTile(leader, entry, tileHeight, colors)
        }
    }
}

@Composable
private fun ChaserTile(leader: StandingsEntry, entry: StandingsEntry, height: Dp, colors: WidgetColors) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(height)
            .background(colors.inset.provider)
            .cornerRadius(12.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamStripe(entry)
        Spacer(GlanceModifier.width(7.dp))
        WText(entry.lastName, colors.onSurface, 13.sp, FontWeight.Medium, modifier = GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.width(6.dp))
        WText(StandingsWidgetPlanner.gapText(leader.points, entry.points), colors.onSurface, 13.sp, FontWeight.Bold)
    }
}

/** 2x2: the leader over their points, and the nearest chasers on one line ("NOR −22 · VER −83"). */
@Composable
private fun NarrowLeader(snapshot: StandingsSnapshot, colors: WidgetColors) {
    val context = LocalContext.current
    val size = LocalSize.current
    val leader = snapshot.drivers.first()
    val chasers = snapshot.drivers.drop(1)
    // About 72 dp per chaser on that line.
    val inline = ((size.width - 24.dp) / 72.dp).toInt().coerceIn(1, 3)

    Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
        WText(leaderOverline(context, snapshot, snapshot.driversFight, wide = false), colors.primary, 11.sp, FontWeight.Bold)
        Spacer(GlanceModifier.height(6.dp))
        WText(leader.name, colors.onSurface, 16.sp, FontWeight.Bold)
        WText(leader.team, colors.onSurfaceVariant, 12.sp)
        Spacer(GlanceModifier.defaultWeight())
        LeaderPoints(leader, colors, 28.sp)
        if (chasers.isNotEmpty()) {
            Spacer(GlanceModifier.height(4.dp))
            WText(
                text = chasers.take(inline).joinToString(" · ") {
                    it.code + " " + StandingsWidgetPlanner.gapText(leader.points, it.points)
                },
                color = colors.onSurfaceVariant,
                size = 12.sp,
            )
        }
    }
}

/** "324 pts", the number big in the accent colour. */
@Composable
private fun LeaderPoints(leader: StandingsEntry, colors: WidgetColors, size: TextUnit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.Bottom) {
        WText(StandingsWidgetPlanner.formatPoints(leader.points), colors.primary, size, FontWeight.Bold)
        Spacer(GlanceModifier.width(3.dp))
        WText(
            text = context.getString(R.string.standings_widget_pts),
            color = colors.onSurfaceVariant,
            size = 13.sp,
            weight = FontWeight.Medium,
            // Roughly on the big number's baseline: its line has more room under the digits.
            modifier = GlanceModifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun EmptyLayout(colors: WidgetColors) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WText(context.getString(R.string.standings_widget_empty_title), colors.onSurface, 14.sp, FontWeight.Bold)
        WText(context.getString(R.string.standings_widget_empty_body), colors.onSurfaceVariant, 12.sp, maxLines = 2)
    }
}

// ================================================================== pieces

/**
 * The dashed title line, as in the app's standings: captioned in the middle on wide widgets,
 * "Title decided" when only the leader is left above it.
 */
@Composable
private fun TitleLine(captioned: Boolean, decided: Boolean, height: Dp, colors: WidgetColors) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dash(colors, GlanceModifier.defaultWeight())
        if (captioned) {
            WText(
                text = context.getString(
                    if (decided) R.string.standings_widget_title_decided else R.string.standings_widget_out_of_reach,
                ),
                color = colors.onSurfaceVariant,
                size = 10.sp,
                modifier = GlanceModifier.padding(horizontal = 6.dp),
            )
            Dash(colors, GlanceModifier.defaultWeight())
        }
    }
}

/** A stretch of dashed rule: a tinted line shape, since Glance cannot draw one itself. */
@Composable
private fun Dash(colors: WidgetColors, modifier: GlanceModifier) {
    Image(
        provider = ImageProvider(R.drawable.widget_dashed_rule),
        contentDescription = null,
        alpha = 0.6f,
        modifier = modifier.height(2.dp),
        contentScale = ContentScale.FillBounds,
        colorFilter = ColorFilter.tint(colors.onSurfaceVariant.provider),
    )
}

/** The team's colour as a short upright bar, like the app's livery accents. */
@Composable
private fun TeamStripe(entry: StandingsEntry) {
    Box(
        modifier = GlanceModifier
            .size(3.dp, 14.dp)
            .background(ColorProvider(teamColor(entry.colorHex)))
            .cornerRadius(2.dp),
        content = {},
    )
}

/** One line of text, ellipsized. */
@Composable
private fun WText(
    text: String,
    color: WidgetColor,
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    modifier: GlanceModifier = GlanceModifier,
    align: TextAlign? = null,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color.provider, fontSize = size, fontWeight = weight, textAlign = align),
        maxLines = maxLines,
    )
}

// ================================================================== text

/** Generous line height for [sp] text at [fontScale] (One UI and Google Sans run tall). */
private fun textLine(sp: Float, fontScale: Float): Dp = (sp * 1.4f * fontScale).dp

/** "after R20" on wide widgets, "R20" on narrow ones, the season before its first round. */
private fun roundLabel(context: Context, snapshot: StandingsSnapshot, wide: Boolean): String = when {
    snapshot.round <= 0 -> snapshot.season.toString()
    wide -> context.getString(R.string.standings_widget_after_round, snapshot.round)
    else -> context.getString(R.string.standings_widget_round, snapshot.round)
}

/** "DRIVERS · AFTER R20". */
internal fun overline(context: Context, @StringRes label: Int, snapshot: StandingsSnapshot, wide: Boolean): String =
    (context.getString(label) + " · " + roundLabel(context, snapshot, wide)).uppercase(Locale.getDefault())

/** "LEADER · AFTER R20", or "CHAMPION · 2026" once the season is over. */
internal fun leaderOverline(context: Context, snapshot: StandingsSnapshot, fight: TitleFight?, wide: Boolean): String {
    val text = if (fight != null && fight.pointsLeft == 0) {
        context.getString(R.string.standings_widget_champion) + " · " + snapshot.season
    } else {
        context.getString(R.string.standings_widget_leader) + " · " + roundLabel(context, snapshot, wide)
    }
    return text.uppercase(Locale.getDefault())
}

/** "108 pts to play for", or "Final" once the season is over; nothing without a calendar. */
private fun fightDetail(context: Context, fight: TitleFight?): String? = when {
    fight == null -> null
    fight.pointsLeft == 0 -> context.getString(R.string.standings_widget_final)
    else -> fight.pointsLeftText
}

/** Under the leader's points: what is left to race for, or that nobody can catch them any more. */
internal fun leaderFooter(context: Context, fight: TitleFight?): String? = when {
    fight == null || fight.pointsLeft == 0 -> null
    fight.decided -> context.getString(R.string.standings_widget_title_decided)
    else -> fight.pointsLeftText
}

/** "McLaren · 7 wins", or just the team before a first win. */
private fun leaderTeam(context: Context, leader: StandingsEntry): String =
    if (leader.wins > 0) {
        leader.team + " · " + context.resources.getQuantityString(R.plurals.standings_widget_wins, leader.wins, leader.wins)
    } else {
        leader.team
    }
