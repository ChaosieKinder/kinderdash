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
        val snapshot = runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                .dashboardSnapshotStore()
                .load()
        }.getOrNull()

        provideContent {
            GlanceTheme {
                DashboardBody(snapshot)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun DashboardBody(state: DashboardState?) {
        // Two columns whenever there is room; one only on a genuinely narrow phone page.
        val twoColumn = LocalSize.current.width >= WIDE.width

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Surface)
                .cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(actionRunCallback<RefreshWidgetAction>())
        ) {
            Header(state)
            Spacer(GlanceModifier.size(10.dp))

            if (state == null || state.tiles.isEmpty()) {
                Text(
                    text = "Tap to load",
                    style = TextStyle(color = ColorProvider(Muted), fontSize = 14.sp())
                )
                return@Column
            }

            if (twoColumn) {
                state.tiles.chunked(2).forEach { pair ->
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        pair.forEachIndexed { index, tile ->
                            TileCard(tile, GlanceModifier.defaultWeight())
                            if (index == 0 && pair.size > 1) Spacer(GlanceModifier.size(8.dp))
                        }
                        // Keep a lone trailing tile at half width rather than letting it stretch.
                        if (pair.size == 1) {
                            Spacer(GlanceModifier.size(8.dp))
                            Spacer(GlanceModifier.defaultWeight())
                        }
                    }
                    Spacer(GlanceModifier.size(8.dp))
                }
            } else {
                state.tiles.forEach { tile ->
                    TileCard(tile, GlanceModifier.fillMaxWidth())
                    Spacer(GlanceModifier.size(8.dp))
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Header(state: DashboardState?) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Homelab",
                style = TextStyle(
                    color = ColorProvider(Foreground),
                    fontSize = 16.sp(),
                    fontWeight = FontWeight.Bold
                ),
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
}
