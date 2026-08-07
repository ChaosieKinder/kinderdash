package com.homelab.app.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.homelab.app.data.local.DashboardSnapshotStore
import com.homelab.app.data.repository.LocalPreferencesRepository
import kotlinx.coroutines.flow.first
import com.homelab.app.domain.model.CalendarDay
import com.homelab.app.domain.model.DashboardState
import com.homelab.app.domain.model.DashboardTile
import com.homelab.app.domain.model.TileMetric
import com.homelab.app.domain.model.TileSeverity
import com.homelab.app.domain.model.TileStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import androidx.compose.ui.graphics.Color

/**
 * The point of this fork: a full-page home-screen widget summarising the homelab.
 *
 * ── Layout: square-first ─────────────────────────────────────────────────────────────────────
 * The instinct with widgets is to design for a tall, narrow phone and scale up. That is backwards
 * for the target hardware. A book-style foldable is ~10:16 folded and ~4:3 open — BOTH states are
 * squarer and roomier than an ordinary phone. So the two-column layout is the primary design and
 * the single-column phone layout is the degraded case.
 *
 * [SizeMode.Responsive] pre-renders one view per declared size and the launcher picks; this is why
 * the sizes must be decided up front rather than retrofitted.
 *
 * ── Renders only, never fetches ──────────────────────────────────────────────────────────────
 * `provideGlance` reads the cached snapshot and returns. Refreshing is [DashboardRefreshWorker]'s
 * job. A widget that blocks its own render on four HTTP calls is a widget that is blank whenever
 * the network is slow.
 */
class KinderDashWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        }.getOrNull()

        val snapshot = runCatching { entryPoint?.dashboardSnapshotStore()?.load() }.getOrNull()
        val title = runCatching { entryPoint?.localPreferencesRepository()?.widgetTitle?.first() }
            .getOrNull()
            ?: LocalPreferencesRepository.DEFAULT_WIDGET_TITLE

        provideContent {
            GlanceTheme {
                DashboardBody(snapshot, title)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun DashboardBody(state: DashboardState?, title: String) {
        // Two columns whenever there is room. Also forced once there are more than four tiles,
        // because a single column of eight does not fit any of the target sizes.
        val tiles = state?.let(::visibleTiles).orEmpty()
        val twoColumn = LocalSize.current.width >= WIDE.width || tiles.size > 4

        // No background on the container: the widget floats its cards directly on the wallpaper.
        // The cards stay opaque so the numbers keep their contrast whatever is behind them — the
        // transparency is meant to remove the slab, not to make the content compete with a photo.
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(14.dp)
                .clickable(actionRunCallback<RefreshWidgetAction>())
        ) {
            Header(state, title)
            Spacer(GlanceModifier.size(10.dp))

            if (state == null || tiles.isEmpty()) {
                Column(
                    modifier = GlanceModifier.background(Card).cornerRadius(14.dp).padding(10.dp)
                ) {
                    Text(
                        text = "Tap to load",
                        style = TextStyle(color = ColorProvider(Muted), fontSize = 14.sp())
                    )
                }
                return@Column
            }

            // LazyColumn, not Column: Glance's Column clips its overflow silently, so adding one
            // service too many would just make a tile vanish. This scrolls instead.
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                val rows = buildRows(tiles, twoColumn)
                rows.forEach { row ->
                    item {
                        Column {
                            Row(modifier = GlanceModifier.fillMaxWidth()) {
                                row.forEachIndexed { index, tile ->
                                    TileCard(tile, GlanceModifier.defaultWeight())
                                    if (index == 0 && row.size > 1) Spacer(GlanceModifier.size(8.dp))
                                }
                                // Pad a lone tile to half width so it doesn't stretch — except a
                                // calendar, which is meant to own the full row.
                                if (twoColumn && row.size == 1 && row[0].status !is TileStatus.Calendar) {
                                    Spacer(GlanceModifier.size(8.dp))
                                    Spacer(GlanceModifier.defaultWeight())
                                }
                            }
                            Spacer(GlanceModifier.size(8.dp))
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Header(state: DashboardState?, title: String) {
        // No card behind the header — it reads as a label on the wallpaper rather than a UI
        // element, which is what makes the widget feel like part of the home screen. The tiles
        // still carry their own backgrounds, so the numbers keep their contrast regardless.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = TextStyle(
                    color = ColorProvider(Foreground),
                    fontSize = 16.sp(),
                    fontWeight = FontWeight.Bold
                ),
                // Pushes the timestamp to the far edge.
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = state?.let { relativeAge(it.generatedAtMillis) } ?: "never",
                style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp())
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun TileCard(tile: DashboardTile, modifier: GlanceModifier) {
        Column(
            modifier = modifier
                .background(Card)
                .cornerRadius(14.dp)
                .padding(10.dp)
        ) {
            Text(
                text = tile.title,
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(Muted),
                    fontSize = 12.sp(),
                    fontWeight = FontWeight.Medium
                )
            )
            Spacer(GlanceModifier.size(6.dp))

            when (val status = tile.status) {
                is TileStatus.Ready -> status.metrics.forEach { MetricRow(it) }
                is TileStatus.Calendar -> CalendarWeek(status.days)
                // Not an error — it just isn't set up. Say so quietly.
                is TileStatus.NotConfigured -> Text(
                    text = "Not set up",
                    style = TextStyle(color = ColorProvider(Muted), fontSize = 13.sp())
                )
                // Deliberately not red: unreachable is usually "phone is off the network", and
                // colouring that as danger trains the eye to ignore real danger.
                is TileStatus.Unavailable -> Text(
                    text = "Unavailable",
                    style = TextStyle(color = ColorProvider(Muted), fontSize = 13.sp())
                )
            }
        }
    }

    /**
     * Seven day columns: yesterday, today, and five ahead.
     *
     * Each column is "airing / downloaded" as a fraction rather than two numbers — at widget scale
     * there is room for one glanceable figure per day, and "2/3" answers both questions at once.
     * A day with nothing scheduled shows a dash, so empty days read as empty rather than as zero
     * of something.
     */
    @androidx.compose.runtime.Composable
    private fun CalendarWeek(days: List<CalendarDay>) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            days.forEach { day ->
                Column(
                    modifier = GlanceModifier.defaultWeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = day.label,
                        maxLines = 1,
                        style = TextStyle(
                            // Today is the column the eye should land on first.
                            color = ColorProvider(if (day.isToday) Foreground else Muted),
                            fontSize = 10.sp(),
                            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                    Spacer(GlanceModifier.size(2.dp))
                    Text(
                        text = if (day.total == 0) "–" else "${day.downloaded}/${day.total}",
                        maxLines = 1,
                        style = TextStyle(
                            color = ColorProvider(
                                when {
                                    day.total == 0 -> Muted
                                    // Everything that aired is on disk — nothing to do.
                                    day.downloaded == day.total -> Good
                                    // Something aired and has not landed. Warning, not danger:
                                    // an episode airing tonight is not a fault.
                                    else -> Warning
                                }
                            ),
                            fontSize = 13.sp(),
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun MetricRow(metric: TileMetric) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = metric.label,
                style = TextStyle(color = ColorProvider(Muted), fontSize = 13.sp()),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = metric.value.toString(),
                style = TextStyle(
                    color = ColorProvider(colorFor(metric.severity)),
                    fontSize = 20.sp(),
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }

    private fun colorFor(severity: TileSeverity): Color = when (severity) {
        TileSeverity.DANGER -> Danger
        TileSeverity.WARNING -> Warning
        TileSeverity.GOOD -> Good
        TileSeverity.NEUTRAL -> Foreground
    }

    private companion object {
        /**
         * Declared smallest-first. The launcher picks the largest that fits.
         *
         * COMPACT is an ordinary phone home-screen page; WIDE is the foldable's ~10:16 cover, which
         * is proportionally wider than a phone despite being the "small" screen; LARGE is the ~4:3
         * inner display, the best canvas of the three.
         */
        val COMPACT = DpSize(240.dp, 380.dp)
        val WIDE = DpSize(380.dp, 460.dp)
        val LARGE = DpSize(540.dp, 520.dp)

        val Surface = Color(0xFF15151A)
        val Card = Color(0xFF20202A)
        val Foreground = Color(0xFFE8E8F0)
        val Muted = Color(0xFF9A9AAB)
        val Good = Color(0xFF4ADE80)
        val Warning = Color(0xFFFACC15)
        val Danger = Color(0xFFF87171)
    }
}

/** Glance's TextStyle wants TextUnit; keeps the call sites readable. */
private fun Int.sp() = androidx.compose.ui.unit.TextUnit(
    this.toFloat(),
    androidx.compose.ui.unit.TextUnitType.Sp
)

/**
 * Groups tiles into rows, giving the calendar a row of its own.
 *
 * Seven day columns inside a half-width tile works out at roughly 17dp per column on a phone —
 * unreadable. Everything else pairs up as normal.
 */
internal fun buildRows(tiles: List<DashboardTile>, twoColumn: Boolean): List<List<DashboardTile>> {
    if (!twoColumn) return tiles.map { listOf(it) }

    val rows = mutableListOf<List<DashboardTile>>()
    var pending: DashboardTile? = null

    tiles.forEach { tile ->
        if (tile.status is TileStatus.Calendar) {
            // Flush whatever was waiting for a partner; it takes half a row on its own.
            pending?.let { rows.add(listOf(it)) }
            pending = null
            rows.add(listOf(tile))
        } else if (pending == null) {
            pending = tile
        } else {
            rows.add(listOf(pending!!, tile))
            pending = null
        }
    }
    pending?.let { rows.add(listOf(it)) }
    return rows
}

/**
 * Which tiles are worth pixels.
 *
 * Services that aren't set up are dropped — with eight supported integrations and only a few
 * configured, a widget mostly reading "Not set up" is noise that crowds out the numbers that
 * matter. The exception is when NOTHING is configured: then the full list is the most useful
 * thing to show, because it tells you what the app can do.
 */
internal fun visibleTiles(state: DashboardState): List<DashboardTile> {
    val configured = state.tiles.filterNot { it.status is TileStatus.NotConfigured }
    return configured.ifEmpty { state.tiles }
}

/** Coarse on purpose — a widget only needs to convey "is this stale?", not a precise duration. */
internal fun relativeAge(generatedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val minutes = (nowMillis - generatedAtMillis).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

/** GlanceAppWidget is constructed by the framework, so Hilt has to be reached rather than injected. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun dashboardSnapshotStore(): DashboardSnapshotStore
    fun localPreferencesRepository(): LocalPreferencesRepository
}
