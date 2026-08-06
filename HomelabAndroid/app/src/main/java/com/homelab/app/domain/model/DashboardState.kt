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
        get() = tiles.isNotEmpty() && tiles.none { it.status is TileStatus.Ready }
}

/** Stable identity for a tile, so the widget can order and lay out independently of labels. */
@Serializable
enum class DashboardTileKey {
    KOMODO,
    UPTIME_KUMA,
    PLEX,
    SEERR
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
    /** Fetched successfully. [metrics] may legitimately be empty. */
    @Serializable
    data class Ready(val metrics: List<TileMetric>) : TileStatus

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

@Serializable
data class TileMetric(
    val label: String,
    val value: Int,
    val severity: TileSeverity
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
