package com.homelab.app.data.repository

import com.homelab.app.data.remote.TlsClientSelector
import com.homelab.app.data.remote.api.GrafanaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The cheap, typed slice of Grafana the widget needs — same `getSummary()` convention as
 * KomodoRepository, UptimeKumaRepository and PlexRepository.
 */
data class GrafanaSummary(
    /** Alerts firing right now. Silenced and inhibited ones are excluded — see [GrafanaAlert.isFiring]. */
    val firingAlerts: Int,
    /** Every alert instance Alertmanager currently knows about, whatever its state. */
    val totalAlerts: Int
)

@Singleton
class GrafanaRepository @Inject constructor(
    private val api: GrafanaApi,
    private val tlsClientSelector: TlsClientSelector
) {

    /**
     * Validates a service-account token by calling an endpoint that actually requires it.
     *
     * `/api/health` is unauthenticated in Grafana, so testing against it would happily accept a
     * completely wrong token — the connection test has to hit the alerts endpoint to mean anything.
     */
    suspend fun authenticate(url: String, token: String, allowSelfSigned: Boolean = false) {
        withContext(Dispatchers.IO) {
            val clean = url.trimEnd('/')
            val request = Request.Builder()
                .url("$clean/api/alertmanager/grafana/api/v2/alerts")
                .addHeader("Authorization", "Bearer ${token.trim()}")
                .addHeader("Accept", "application/json")
                .build()

            tlsClientSelector.forAllowSelfSigned(allowSelfSigned).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        when (response.code) {
                            401, 403 -> "Grafana rejected the token. Use a service account token with Viewer access."
                            404 -> "Grafana alerting API not found — unified alerting needs Grafana 9 or newer."
                            else -> "Grafana returned HTTP ${response.code}."
                        }
                    )
                }
            }
        }
    }

    suspend fun getSummary(instanceId: String): GrafanaSummary {
        val alerts = api.getAlerts(instanceId = instanceId)
        return GrafanaSummary(
            firingAlerts = alerts.count { it.isFiring },
            totalAlerts = alerts.size
        )
    }

    suspend fun getVersion(instanceId: String): String? =
        api.getHealth(instanceId = instanceId).version
}
