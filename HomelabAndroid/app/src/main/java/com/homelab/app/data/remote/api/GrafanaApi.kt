package com.homelab.app.data.remote.api

import com.homelab.app.data.remote.dto.grafana.GrafanaAlert
import com.homelab.app.data.remote.dto.grafana.GrafanaHealth
import retrofit2.http.GET
import retrofit2.http.Header

interface GrafanaApi {

    /**
     * Unified alerting (Grafana 9+). One request gives every current alert instance and its state,
     * which is all the widget needs — no second call to enumerate rules.
     */
    @GET("api/alertmanager/grafana/api/v2/alerts")
    suspend fun getAlerts(
        @Header("X-Homelab-Service") service: String = "Grafana",
        @Header("X-Homelab-Instance-Id") instanceId: String
    ): List<GrafanaAlert>

    @GET("api/health")
    suspend fun getHealth(
        @Header("X-Homelab-Service") service: String = "Grafana",
        @Header("X-Homelab-Instance-Id") instanceId: String
    ): GrafanaHealth
}
