package com.homelab.app.data.repository

import com.homelab.app.data.remote.TlsClientSelector
import com.homelab.app.data.remote.api.HomeAssistantApi
import com.homelab.app.data.remote.api.NextcloudApi
import com.homelab.app.data.remote.api.TransmissionApi
import com.homelab.app.data.remote.dto.homelabextra.TransmissionRpcArguments
import com.homelab.app.data.remote.dto.homelabextra.TransmissionRpcRequest
import com.homelab.app.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// ─── Home Assistant ──────────────────────────────────────────────────────────────────────────

data class HomeAssistantSummary(
    /** Lights currently on — the "did I leave something on?" number. */
    val lightsOn: Int,
    /** Entities reporting unavailable/unknown, i.e. integrations that have stopped working. */
    val unavailableEntities: Int,
    val totalEntities: Int
)

@Singleton
class HomeAssistantRepository @Inject constructor(
    private val api: HomeAssistantApi,
    private val tlsClientSelector: TlsClientSelector
) {
    suspend fun authenticate(url: String, token: String, allowSelfSigned: Boolean = false) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${url.trimEnd('/')}/api/")
                .addHeader("Authorization", "Bearer ${token.trim()}")
                .build()

            tlsClientSelector.forAllowSelfSigned(allowSelfSigned).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        if (response.code == 401) "Home Assistant rejected the token. Use a long-lived access token."
                        else "Home Assistant returned HTTP ${response.code}."
                    )
                }
            }
        }
    }

    suspend fun getSummary(instanceId: String): HomeAssistantSummary {
        val states = api.getStates(instanceId = instanceId)
        return HomeAssistantSummary(
            lightsOn = states.count { it.domain == "light" && it.isOn },
            unavailableEntities = states.count { it.isUnreachable },
            totalEntities = states.size
        )
    }
}

// ─── Nextcloud ───────────────────────────────────────────────────────────────────────────────

data class NextcloudSummary(
    val freeSpaceBytes: Long,
    val activeUsers24h: Int,
    val numFiles: Long
) {
    val freeSpaceGb: Int get() = (freeSpaceBytes / 1_073_741_824L).toInt()
}

@Singleton
class NextcloudRepository @Inject constructor(
    private val api: NextcloudApi,
    private val tlsClientSelector: TlsClientSelector
) {
    /**
     * The serverinfo endpoint is the one the widget uses, so it is also the one worth testing —
     * `status.php` is public and would accept any token at all.
     */
    suspend fun authenticate(url: String, token: String, allowSelfSigned: Boolean = false) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${url.trimEnd('/')}/ocs/v2.php/apps/serverinfo/api/v1/info?format=json")
                .addHeader("NC-Token", token.trim())
                .addHeader("OCS-APIRequest", "true")
                .addHeader("Accept", "application/json")
                .build()

            tlsClientSelector.forAllowSelfSigned(allowSelfSigned).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        when (response.code) {
                            401, 403 -> "Nextcloud rejected the token. This is the serverinfo token from Settings → Administration → System, not a password or app password."
                            404 -> "serverinfo endpoint not found — is the Server Info app enabled?"
                            else -> "Nextcloud returned HTTP ${response.code}."
                        }
                    )
                }
            }
        }
    }

    suspend fun getSummary(instanceId: String): NextcloudSummary {
        val data = api.getServerInfo(instanceId = instanceId).ocs?.data
        return NextcloudSummary(
            freeSpaceBytes = data?.nextcloud?.system?.freespace ?: 0L,
            activeUsers24h = data?.activeUsers?.last24hours ?: 0,
            numFiles = data?.nextcloud?.storage?.numFiles ?: 0L
        )
    }
}

// ─── Transmission ────────────────────────────────────────────────────────────────────────────

data class TransmissionSummary(
    val activeTorrents: Int,
    val erroredTorrents: Int,
    val totalTorrents: Int
)

@Singleton
class TransmissionRepository @Inject constructor(
    private val api: TransmissionApi,
    private val tlsClientSelector: TlsClientSelector
) {
    /**
     * Transmission's RPC is CSRF-protected: any call without a valid `X-Transmission-Session-Id`
     * is answered 409 with the correct id in a response header, and is expected to be retried.
     * Cached per instance, since the id is stable until the daemon restarts.
     */
    private val sessionIds = ConcurrentHashMap<String, String>()

    suspend fun authenticate(
        url: String,
        username: String?,
        password: String?,
        allowSelfSigned: Boolean = false
    ) {
        withContext(Dispatchers.IO) {
            val client = tlsClientSelector.forAllowSelfSigned(allowSelfSigned)
            val builder = Request.Builder()
                .url("${url.trimEnd('/')}/transmission/rpc")
                .head()
            if (!username.isNullOrBlank()) {
                builder.addHeader("Authorization", Credentials.basic(username, password.orEmpty()))
            }

            client.newCall(builder.build()).execute().use { response ->
                // 409 is the SUCCESS case here: it proves we reached Transmission's RPC and were
                // challenged for a session id, which only happens once auth has already passed.
                if (response.code == 409 || response.isSuccessful) return@use
                throw IllegalStateException(
                    when (response.code) {
                        401 -> "Transmission rejected the credentials."
                        404 -> "No Transmission RPC at that address."
                        else -> "Transmission returned HTTP ${response.code}."
                    }
                )
            }
        }
    }

    suspend fun getSummary(instanceId: String): TransmissionSummary {
        val request = TransmissionRpcRequest(
            method = "torrent-get",
            arguments = TransmissionRpcArguments(fields = listOf("status", "error"))
        )

        var response = api.rpc(
            instanceId = instanceId,
            sessionId = sessionIds[instanceId],
            body = request
        )

        if (response.code() == 409) {
            val fresh = response.headers()["X-Transmission-Session-Id"]
                ?: throw IllegalStateException("Transmission asked for a session id but did not supply one.")
            Logger.d(TAG, "Refreshed Transmission session id")
            sessionIds[instanceId] = fresh
            response = api.rpc(instanceId = instanceId, sessionId = fresh, body = request)
        }

        if (!response.isSuccessful) {
            throw IllegalStateException("Transmission returned HTTP ${response.code()}.")
        }

        val torrents = response.body()?.arguments?.torrents.orEmpty()
        return TransmissionSummary(
            activeTorrents = torrents.count { it.isActive },
            erroredTorrents = torrents.count { it.hasError },
            totalTorrents = torrents.size
        )
    }

    private companion object {
        const val TAG = "TransmissionRepository"
    }
}
