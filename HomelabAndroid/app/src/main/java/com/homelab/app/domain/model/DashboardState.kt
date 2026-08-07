package com.homelab.app.domain.model

import kotlinx.serialization.Serializable

/**
 * The typed, cross-service snapshot the home-screen widget renders.
 *
 * Upstream has no model like this: every `ui/<service>` screen owns its own state and nothing fans
 * in across services. That gap is the reason this fork exists — the widget's whole job is to answer
 * "is anything wrong?" from one glance, which needs one object, not fourteen screens.
 *
 * Kept deliberately free of Android and Glance types so it can be unit-tested on the JVM and reused
 * by anything else that wants a homelab summary.
 */
@Serializable
data class DashboardState(
    val tiles: List<DashboardTile>,
    val generatedAtMillis: Long
) {
    /** True if any reachable service is reporting something the user should look at. */
    val hasProblem: Boolean
        get() = tiles.any { tile ->
            (tile.status as? TileStatus.Ready)
                ?.metrics
                ?.any { it.severity == TileSeverity.DANGER }
                ?: false
        }

    /** True if nothing could be reached at all — usually "off the network", not "everything died". */
    val allUnavailable: Boolean
        get() = tiles.isNotEmpty() && tiles.none { it.status is TileStatus.Loaded }
}

/** Stable identity for a tile, so the widget can order and lay out independently of labels. */
@Serializable
enum class DashboardTileKey {
    KOMODO,
    UPTIME_KUMA,
    PLEX,
    SEERR,
    GRAFANA,
    HOME_ASSISTANT,
    NEXTCLOUD,
    TRANSMISSION,
    CALENDAR
}

@Serializable
data class DashboardTile(
    val key: DashboardTileKey,
    /** The instance's own label where it has one, else the service's display name. */
    val title: String,
    val status: TileStatus
)

@Serializable
sealed interface TileStatus {
    /** Reached the service successfully, whatever shape the result takes. */
    sealed interface Loaded : TileStatus

    /** Fetched successfully. [metrics] may legitimately be empty. */
    @Serializable
    data class Ready(val metrics: List<TileMetric>) : Loaded

    /**
     * A week of scheduled episodes. Separate from [Ready] because a row of days is a different
     * thing to render than a list of label/value pairs, and squeezing it into metrics would mean
     * encoding dates into strings and parsing them back out in the widget.
     */
    @Serializable
    data class Calendar(val days: List<CalendarDay>) : Loaded

    /** No instance of this service is set up — render nothing rather than an error. */
    @Serializable
    data object NotConfigured : TileStatus

    /**
     * Configured but unreachable this cycle. Distinct from [NotConfigured] because the widget
     * should show a stale-but-labelled value here rather than a blank, and blank rather than a
     * spinner: a widget that briefly says "unavailable" is more useful than one that lies.
     */
    @Serializable
    data class Unavailable(val message: String?) : TileStatus
}

/**
 * One day column in the calendar tile.
 *
 * Counts rather than episode lists: the widget shows a week at a glance, and the detail belongs on
 * a screen if it is ever wanted.
 */
@Serializable
data class CalendarDay(
    /** Short weekday name, e.g. "Mon". Today is marked by [isToday], not by its label. */
    val label: String,
    val total: Int,
    val downloaded: Int,
    val isToday: Boolean,
    val entries: List<CalendarEntry> = emptyList()
)

/**
 * One series airing on a given day, collapsed across its episodes.
 *
 * Grouped by series rather than listed per episode because a season drop would otherwise fill the
 * whole widget with eight lines of the same show.
 */
@Serializable
data class CalendarEntry(
    val seriesTitle: String,
    val episodeCount: Int,
    val downloadedCount: Int
) {
    val allDownloaded: Boolean get() = episodeCount > 0 && downloadedCount == episodeCount
}

@Serializable
data class TileMetric(
    val label: String,
    val value: Int,
    val severity: TileSeverity,
    /**
     * 0..100 when the figure is a proportion of something, which lets the widget draw a bar instead
     * of only printing a number. Null when there is no denominator — Nextcloud's free disk space is
     * the case in point: serverinfo reports free bytes but no total, so it can only ever be text.
     */
    val percent: Int? = null,
    /** Optional unit shown after the number, e.g. "GB". */
    val suffix: String? = null
)

@Serializable
enum class TileSeverity {
    /** Nothing to see. */
    GOOD,

    /** Worth noticing, not worth waking up for. */
    WARNING,

    /** The reason you glanced at the widget. */
    DANGER,

    /** Informational — a count with no notion of good or bad, e.g. active streams. */
    NEUTRAL
}
