package com.homelab.app.data.remote.api

import com.homelab.app.data.remote.dto.homelabextra.HomeAssistantState
import com.homelab.app.data.remote.dto.homelabextra.NextcloudInfoResponse
import com.homelab.app.data.remote.dto.homelabextra.TransmissionRpcRequest
import com.homelab.app.data.remote.dto.homelabextra.TransmissionRpcResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface HomeAssistantApi {
    /**
     * Every entity and its state in one request. There is no narrower endpoint — Home Assistant's
     * REST API has no server-side filtering — so this is as cheap as it gets.
     */
    @GET("api/states")
    suspend fun getStates(
        @Header("X-Homelab-Service") service: String = "HomeAssistant",
        @Header("X-Homelab-Instance-Id") instanceId: String
    ): List<HomeAssistantState>
}

interface NextcloudApi {
    @GET("ocs/v2.php/apps/serverinfo/api/v1/info")
    suspend fun getServerInfo(
        @Header("X-Homelab-Service") service: String = "Nextcloud",
        @Header("X-Homelab-Instance-Id") instanceId: String,
        // Required on every OCS call; without it Nextcloud returns a login page instead of JSON.
        @Header("OCS-APIRequest") ocsApiRequest: String = "true",
        @Header("Accept") accept: String = "application/json",
        @Query("format") format: String = "json"
    ): NextcloudInfoResponse
}

interface TransmissionApi {
    /**
     * Returns [Response] rather than the body because the RPC handshake is driven by the HTTP
     * status and a response header: the first call answers 409 with a session id that every
     * subsequent call must echo. See TransmissionRepository.
     */
    @POST("transmission/rpc")
    suspend fun rpc(
        @Header("X-Homelab-Service") service: String = "Transmission",
        @Header("X-Homelab-Instance-Id") instanceId: String,
        @Header("X-Transmission-Session-Id") sessionId: String?,
        @Body body: TransmissionRpcRequest
    ): Response<TransmissionRpcResponse>
}
