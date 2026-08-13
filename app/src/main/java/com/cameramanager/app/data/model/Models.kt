package com.cameramanager.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A managed camera device. Supports both LAN and remote devices.
 *
 * 路由字段说明（内网穿透相关）：
 *  - [lanSsid]: 设备所在内网的 WiFi 名（带不带引号都行，比较时归一化）。
 *      为空表示不绑定 SSID，永远按填写的 [host]/[port] 直连。
 *  - [tunnelId]: 该设备绑定的内网穿透通道 ID（参见 [Tunnel]）。
 *      0 表示不绑定；当前 WiFi 不是 [lanSsid] 时使用此通道。
 *  - [publicHost]/[publicPort]: 设备自身的公网地址（DDNS/端口转发），
 *      优先级低于 [tunnelId]，但高于内网地址。可空。
 *
 * NetworkRouter 会按「当前 WiFi SSID == lanSsid」判断走内网，
 * 否则按 tunnelId → publicHost → 原始 host 的顺序选路。
 */
@Entity(
    tableName = "devices",
    indices = [Index(value = ["host", "port"], unique = true)]
)
data class Device(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** LAN IP or remote host. */
    val host: String,
    val port: Int = 554,
    /** RTSP path without leading slash, e.g. "stream0" or "live/ch0". */
    val rtspPath: String = "stream0",
    val username: String? = null,
    val password: String? = null,
    /** ONVIF port, 0 if device is not ONVIF / PTZ. */
    val onvifPort: Int = 0,
    /** True if the device supports PTZ control. */
    val supportsPtz: Boolean = false,
    /** True if the device supports two-way audio intercom. */
    val supportsAudio: Boolean = true,
    /** Manual rotation degrees: 0, 90, 180, 270. */
    val rotation: Int = 0,
    /** Horizontal mirror. */
    val mirrored: Boolean = false,
    /** Selected stream profile: 0=主码流, 1=子码流, 2=流畅. */
    val streamProfile: Int = 1,
    /** Vendor: "generic", "tapo", "imou". */
    val vendor: String = "generic",
    /** Night vision mode: 0=auto(smart), 1=ir(infrared), 2=color(full-color). */
    val nightVision: Int = 0,
    /** Privacy masking enabled. */
    val privacyMask: Boolean = false,
    /** Epoch millis, used for display ordering (oldest first). */
    val createdAt: Long = System.currentTimeMillis(),
    /** Transient last-known-online flag, kept so Room uses the same primary constructor. */
    val online: Boolean = false,
    /** 设备所在内网 WiFi SSID（路由判断依据），空表示不绑定。 */
    val lanSsid: String? = null,
    /** 绑定的内网穿透通道 ID，0 表示不绑定。 */
    val tunnelId: Long = 0,
    /** 设备自身公网地址（DDNS/端口转发），可空。 */
    val publicHost: String? = null,
    /** 设备自身公网 RTSP 端口。 */
    val publicPort: Int = 0,
    /** 设备自身公网 ONVIF 端口。 */
    val publicOnvifPort: Int = 0
) {
    /** Build the full RTSP URL using the given host/port/path. */
    fun rtspUrl(useHost: String = host, usePort: Int = port): String {
        val auth = if (!username.isNullOrEmpty()) {
            "$username:${password ?: ""}@"
        } else ""
        return "rtsp://$auth$useHost:$usePort/$rtspPath"
    }

    /** Human-readable stream profile label. */
    fun profileLabel(): String = when (streamProfile) {
        0 -> "高清(主码流)"
        1 -> "标清(子码流)"
        else -> "流畅"
    }
}

/**
 * An intranet-penetration tunnel (frpc / ngrok / 自建端口转发 / ZeroTier 等)。
 *
 * 用户在「内网穿透」页面自由配置多个通道，每条记录一个公网可达的 host:port
 * 映射。设备可绑定其中一条（[Device.tunnelId]），当手机不在设备内网时
 * NetworkRouter 会用该通道的 host:port 替换设备原始内网地址。
 *
 * 通道本身只描述「公网入口」，不区分底层是 frp 还是 ngrok —— 因为对 App
 * 来说，只要能拿到 host:port 就能直接 RTSP/ONVIF 连过去。
 */
@Entity(tableName = "tunnels")
data class Tunnel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 公网/穿透入口 host（域名或 IP）。 */
    val host: String,
    /** 公网/穿透入口 RTSP 端口。 */
    val port: Int = 554,
    /** 公网/穿透入口 ONVIF 端口，0 表示无。 */
    val onvifPort: Int = 0,
    /** 是否启用。关掉的通道不会被选用。 */
    val enabled: Boolean = true,
    /** 备注，例如 "frp 服务器 / 阿里云" 。 */
    val remark: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A detection rule for a device.
 */
@Entity(tableName = "detection_rules")
data class DetectionRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    /** Detection type: "human", "motion", "face". */
    val type: String = "human",
    val enabled: Boolean = true,
    /** Sensitivity 1-5. */
    val sensitivity: Int = 3,
    /** Schedule: list of day-bitmap + time window, simplified to "always" or cron-like. */
    val schedule: String = "always",
    /** Region of interest rectangles as JSON, empty = full frame. */
    val regionsJson: String = "[]",
    /** Trigger actions bitmask: 1=record, 2=notify, 4=sound, 8=light. */
    val actions: Int = 3,
    /** Auto-tracking on/off (PTZ only). */
    val autoTrack: Boolean = false
)

/**
 * An alarm / event log entry.
 */
@Entity(tableName = "alarms")
data class AlarmEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    /** Epoch millis. */
    val timestamp: Long,
    /** "human", "motion", "track", "offline". */
    val type: String,
    val message: String,
    /** Snapshot path if captured. */
    val snapshotPath: String? = null,
    val acknowledged: Boolean = false
)

/**
 * A recording file on the device TF card or local storage.
 */
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    /** Start time epoch millis. */
    val startTime: Long,
    /** End time epoch millis (0 if ongoing). */
    val endTime: Long = 0,
    /** "manual", "motion", "schedule". */
    val trigger: String,
    /** Local file path or remote URI. */
    val filePath: String,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0
)

/**
 * A discovered device on the LAN (transient, not persisted until added).
 */
data class ScannedDevice(
    val host: String,
    val port: Int,
    val manufacturer: String = "Unknown",
    val model: String = "Unknown",
    val onvif: Boolean = false,
    val rtspSupported: Boolean = true
)
