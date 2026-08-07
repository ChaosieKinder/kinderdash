package com.homelab.app.data.remote.dto.homelabextra

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Home Assistant ──────────────────────────────────────────────────────────────────────────

/** One entity from `GET /api/states`. Attributes are ignored — the widget only needs state. */
@Serializable
data class HomeAssistantState(
    @SerialName("entity_id") val entityId: String = "",
    val state: String = ""
) {
    val domain: String get() = entityId.substringBefore('.', "")

    /**
     * Home Assistant reports a device it can't reach as "unavailable", and one it has never heard
     * from as "unknown". Both mean the integration is not working, which is the health signal.
     */
    val isUnreachable: Boolean
        get() = state.equals("unavailable", ignoreCase = true) ||
            state.equals("unknown", ignoreCase = true)

    val isOn: Boolean get() = state.equals("on", ignoreCase = true)
}

// ─── Nextcloud ───────────────────────────────────────────────────────────────────────────────

/** `GET /ocs/v2.php/apps/serverinfo/api/v1/info?format=json` (the serverinfo app). */
@Serializable
data class NextcloudInfoResponse(val ocs: NextcloudOcs? = null)

@Serializable
data class NextcloudOcs(val data: NextcloudData? = null)

@Serializable
data class NextcloudData(
    val nextcloud: NextcloudSection? = null,
    val activeUsers: NextcloudActiveUsers? = null
)

@Serializable
data class NextcloudSection(
    val system: NextcloudSystem? = null,
    val storage: NextcloudStorage? = null
)

@Serializable
data class NextcloudSystem(
    /**
     * Free bytes on the data directory. serverinfo reports no matching total, so this can only ever
     * be shown as an absolute figure — there is no denominator to make a percentage from.
     */
    val freespace: Long? = null,
    /** Memory totals ARE reported, which is why the bar on the tile is RAM and not disk. */
    @SerialName("mem_total") val memTotal: Long? = null,
    @SerialName("mem_free") val memFree: Long? = null
)

@Serializable
data class NextcloudStorage(
    @SerialName("num_files") val numFiles: Long? = null,
    @SerialName("num_users") val numUsers: Long? = null
)

@Serializable
data class NextcloudActiveUsers(
    val last24hours: Int? = null
)

// ─── Transmission ────────────────────────────────────────────────────────────────────────────

@Serializable
data class TransmissionRpcRequest(
    val method: String,
    val arguments: TransmissionRpcArguments
)

@Serializable
data class TransmissionRpcArguments(val fields: List<String>)

@Serializable
data class TransmissionRpcResponse(
    val result: String = "",
    val arguments: TransmissionTorrents? = null
)

@Serializable
data class TransmissionTorrents(val torrents: List<TransmissionTorrent> = emptyList())

@Serializable
data class TransmissionTorrent(
    /** 0 stopped · 1/2 check · 3/4 download · 5/6 seed. */
    val status: Int = 0,
    /** Non-zero means Transmission itself is reporting a problem with this torrent. */
    val error: Int = 0
) {
    val isActive: Boolean get() = status == STATUS_DOWNLOADING || status == STATUS_SEEDING
    val hasError: Boolean get() = error != 0

    private companion object {
        const val STATUS_DOWNLOADING = 4
        const val STATUS_SEEDING = 6
    }
}
