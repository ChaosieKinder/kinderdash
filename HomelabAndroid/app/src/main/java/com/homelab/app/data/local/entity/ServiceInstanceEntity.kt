package com.homelab.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "service_instances")
data class ServiceInstanceEntity(
    @PrimaryKey val id: String,
    val type: String,
    val label: String,
    val url: String,
    val token: String,
    val proxmoxCsrfToken: String? = null,
    val proxmoxOtp: String? = null,
    val username: String?,
    val apiKey: String?,
    val piholePassword: String?,
    val piholeAuthMode: String?,
    val fallbackUrl: String?,
    @ColumnInfo(defaultValue = "0")
    val allowSelfSigned: Boolean,
    val password: String? = null,
    /**
     * Total storage in GB, entered by hand. Nextcloud only, and only because serverinfo reports
     * free space with no matching total. 0 means unset. Not a credential, so it is not encrypted.
     */
    @ColumnInfo(defaultValue = "0")
    val storageCapacityGb: Int = 0
)
