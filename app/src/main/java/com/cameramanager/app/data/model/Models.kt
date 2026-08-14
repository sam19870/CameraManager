package com.cameramanager.app.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

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
@Parcelize
@Entity(
    tableName = "devices",
    indices = [Index(value = ["host", "port"], unique = true)]
)
data class Device(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** LAN IP or remote host. */
    val host: String,
    /**
     * 管理端口（HTTP/HTTPS/ONVIF 控制通道端口）：
     *   - 用户添加时填的端口（默认 80）
     *   - Tapo 握手、ONVIF SOAP、厂商 API 调用一律使用此端口
     *   - 不再与 RTSP 端口混用（之前混用导致连错端口是核心 bug）
     */
    val port: Int = 80,
    /**
     * RTSP 视频流端口（与管理端口分离）：
     *   - 默认 554（绝大多数摄像头）
     *   - 特殊厂商 34567/37777/8554 等由 DeviceAutoProbe 探测后写入
     *   - RTSP URL 构造、RtspPlayer、回放下载一律使用此端口
     */
    val rtspPort: Int = 554,
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
    /** Vendor: "generic", "tapo", "imou", "dahua", "hikvision", "ezviz", "uniview", "xiongmai". */
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
    val publicOnvifPort: Int = 0,
    /** AI 智能追踪（人形自动跟随）开关。 */
    val autoTrack: Boolean = false,
    /**
     * 主码流（原画）RTSP 路径。
     *  回放和下载一律用此路径，确保最高分辨率（摄像头里设置的录制分辨率）。
     *  空时回退到 [rtspPath]。
     */
    val mainRtspPath: String? = null,
    /**
     * 子码流（流畅）RTSP 路径。
     *  预览默认用此路径，避免卡顿和流量过大。
     *  空时回退到 [rtspPath]。
     */
    val subRtspPath: String? = null
) : Parcelable {
    /** Build the full RTSP URL using the given host/port/path. */
    fun rtspUrl(useHost: String = host, usePort: Int = rtspPort): String {
        return rtspUrlForProfile(streamProfile, useHost, usePort)
    }

    /**
     * 根据 profile 选对应的码流路径生成 URL。
     *  profile=0(高清) 用主码流；=1(标清)/=2(流畅) 用子码流。
     *  回放和下载一律传 profile=0 拿原画。
     *  【重要】端口参数默认用 rtspPort（554 等），不再是管理端口 port（80/443）。
     */
    fun rtspUrlForProfile(profile: Int, useHost: String = host, usePort: Int = rtspPort): String {
        val path = when (profile) {
            0 -> mainRtspPath?.ifBlank { null } ?: rtspPath
            else -> subRtspPath?.ifBlank { null } ?: rtspPath
        }
        val auth = if (!username.isNullOrEmpty()) {
            "$username:${password ?: ""}@"
        } else ""
        return "rtsp://$auth$useHost:$usePort/$path"
    }

    /** Human-readable stream profile label. */
    fun profileLabel(): String = when (streamProfile) {
        0 -> "原画(主码流·最高分辨率)"
        1 -> "标清(子码流·推荐预览)"
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
 *
 * 语义说明（请按用户家里的实际结构来理解）：
 *   ┌───────────┐        公网         ┌──────────┐        内网          ┌───────────────┐
 *   │ 手机 App  │  ───────────────→  │SakuraFrp │  ───────────────→   │ 家里摄像头    │
 *   └───────────┘  host:remotePort   │ 节点入口  │  192.168.1.x/24      │ 192.168.1.108 │
 *                                     └──────────┘                      └───────────────┘
 *            ↑                                     ↑
 *      APP 只要填这个入口信息             这个入口是用户在 natfrp.com 面板分配
 *      = 路由器上 frpc.toml 里的:        给老毛子路由器的，路由器上 frpc
 *      server_addr + remote_port         已经跑起来了，APP 不管理 frpc
 *
 * 这意味着：
 *   - APP 不需要填写 local_ip / local_port / tunnelType 等 frpc 内部参数（那是路由器配置的）
 *   - APP 只需要 SakuraFrp 公网入口：host（节点域名） + remotePort（公网端口） + 认证（token）
 *   - 连通之后，APP 添加摄像头就可以像在同一 WiFi 下一样输入 192.168.1.x 内网 IP
 *   - 当网络不是内网 WiFi 时，NetworkRouter 会把目标内网 IP 的连接路由到 host:remotePort
 */
@Entity(tableName = "tunnels")
data class Tunnel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 通道名称（用户自定义，不参与连接） */
    val name: String,
    /** 公网入口 host（节点域名或公网 IP），对应老毛子 frpc server_addr 和面板分配的 */
    val host: String,
    /** 公网入口 port（remotePort，SakuraFrp 面板分配给路由器映射内网的端口）。App 就是用这个 host:port 作为代理或直接转发进入家里内网。 */
    val port: Int,
    /** ONVIF 入口 port（如果 SakuraFrp 上单独映射了一条 ONVIF 到内网，填这里；0 表示不单独映射就用上面 port 也行）。 */
    val onvifPort: Int = 0,

    // ---- 认证（SakuraFrp/自搭 frp 支持，不一定全填） ----
    /** SakuraFrp 访问密钥 token（如果你 frp 服务端开了 token 认证才填）。 */
    val token: String? = null,
    /** 用户名（极少数自搭 frp 用 token + user 双认证，多数留空）。 */
    val authUser: String? = null,
    /** 密码（同上，多数留空）。 */
    val authPass: String? = null,

    // ---- 内网网段信息 ----
    /** 通道覆盖的内网网段 CIDR，例如 "192.168.1.0/24"、"10.0.0.0/8"。用于 NetworkRouter 自动匹配设备内网 IP。 */
    val lanCidr: String? = null,
    /** 内网默认网关 IP（可选填，例如 192.168.1.1，用于 NetworkRouter 做连通性探测）。 */
    val lanGateway: String? = null,

    /** 是否启用。关掉的通道不会被 NetworkRouter 选上。 */
    val enabled: Boolean = true,
    /** 备注，例如 "老毛子路由器 / 樱花日本节点 / RTSP入口" 。 */
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
