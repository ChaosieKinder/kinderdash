package com.homelab.app.data.remote.dto.grafana

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry from Grafana's Alertmanager API
 * (`/api/alertmanager/grafana/api/v2/alerts`), which is the unified-alerting endpoint on
 * Grafana 9+. Verified against a Grafana 13.1.0 instance.
 */
@Serializable
data class GrafanaAlert(
    val labels: Map<String, String> = emptyMap(),
    val status: GrafanaAlertStatus? = null
) {
    /** Grafana's own name for the alert, when it set one. */
    val name: String? get() = labels["alertname"]

    /**
     * "active" means firing right now. Suppressed alerts are silenced or inhibited — deliberately
     * not counted as firing, since silencing one is an explicit statement that you don't want to
     * be told about it.
     */
    val isFiring: Boolean get() = status?.state.equals("active", ignoreCase = true)
}

@Serializable
data class GrafanaAlertStatus(
    val state: String? = null,
    @SerialName("silencedBy") val silencedBy: List<String> = emptyList(),
    @SerialName("inhibitedBy") val inhibitedBy: List<String> = emptyList()
)

/** `/api/health` — unauthenticated, which makes it the right probe for a connection test. */
@Serializable
data class GrafanaHealth(
    val database: String? = null,
    val version: String? = null,
    val commit: String? = null
)
