package com.cameramanager.app.rtsp

import android.util.Log
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.data.model.ScannedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal ONVIF client implementing:
 *  - WS-Discovery (UDP multicast probe) for LAN device discovery.
 *  - PTZ ContinuousMove / Stop for pan-tilt-zoom control.
 *
 * ONVIF uses SOAP/HTTP. The envelopes here cover the common subset of
 * Profile S devices. Auth (WS-UsernameToken) is applied for PTZ commands.
 */
object OnvifClient {

    private const val TAG = "OnvifClient"
    private const val MULTICAST_ADDR = "239.255.255.250"
    private const val MULTICAST_PORT = 3702
    private const val DISCOVERY_TIMEOUT_MS = 2500

    /** 设备真实 PTZ/媒体 profile token 的内存缓存（按设备 id），避免每次云台都重新 GetProfiles。 */
    private val ptzProfileCache = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * 解析设备真实 media profile token（标准 ONVIF GetProfiles 第2步）。
     * 先查缓存；未命中则调 GetProfiles 取第一个 token 并缓存；全部失败回退 "Profile_1"。
     * 替代之前硬编码 Profile_1（很多厂家 token 名不同，导致云台/预置位命令送不进去）。
     */
    suspend fun resolveProfileToken(device: Device): String = withContext(Dispatchers.IO) {
        ptzProfileCache[device.id]?.let { return@withContext it }
        val token = fetchProfileToken(device)
        if (!token.isNullOrBlank()) {
            ptzProfileCache[device.id] = token
            return@withContext token
        }
        "Profile_1"
    }

    private suspend fun fetchProfileToken(device: Device): String? {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return null
        val body = """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>"""
        val act = "http://www.onvif.org/ver10/media/wsdl/GetProfiles"
        val resp = soapRequest(device, OnvifService.MEDIA, act, body)
            ?: soapRequestToPath(device, "/onvif/device_service", act, body)
            ?: return null
        return Regex("Profiles[^>]*token=\"([^\"]+)\"").find(resp)?.groupValues?.getOrNull(1)
    }

    /** ONVIF 服务类型（用于按 GetCapabilities 的 XAddr 动态定位服务地址）。 */
    private enum class OnvifService { DEVICE, MEDIA, PTZ, IMAGING, EVENTS }

    /** 按设备 id 缓存的动态服务地址（路径部分，来自 GetCapabilities 的 XAddr）。 */
    private class OnvifEndpoints(
        val media: String? = null,
        val ptz: String? = null,
        val imaging: String? = null
    )

    private val endpointCache = java.util.concurrent.ConcurrentHashMap<Long, OnvifEndpoints>()

    /**
     * 解析设备各 ONVIF 服务地址（标准流程：GetCapabilities -> 各 XAddr）。
     * 失败字段为 null，调用方回退默认路径。带内存缓存，避免每次请求都重新 GetCapabilities。
     */
    private suspend fun resolveEndpoints(device: Device): OnvifEndpoints {
        endpointCache[device.id]?.let { return it }
        val eps = fetchEndpoints(device) ?: OnvifEndpoints()
        endpointCache[device.id] = eps
        return eps
    }

    private fun fetchEndpoints(device: Device): OnvifEndpoints? {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return null
        val body = """<tds:GetCapabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><tds:Category>All</tds:Category></tds:GetCapabilities>"""
        val resp = soapRequestToPath(device, "/onvif/device_service",
            "http://www.onvif.org/ver10/device/wsdl/GetCapabilities", body) ?: return null
        return OnvifEndpoints(
            media = xaddrPath(resp, "Media"),
            ptz = xaddrPath(resp, "PTZ"),
            imaging = xaddrPath(resp, "Imaging")
        )
    }

    /** 从 GetCapabilities 应答里提取指定 section（Media/PTZ/Imaging）的 XAddr 的 path 部分。 */
    private fun xaddrPath(resp: String, section: String): String? {
        val sec = Regex("""<[\w:]*$section[\s>](.*?)</[\w:]*$section>""", RegexOption.DOT_MATCHES_ALL)
            .find(resp)?.groupValues?.getOrNull(1) ?: return null
        val xaddr = Regex("""<[\w:]*XAddr[^>]*>(.*?)</[\w:]*XAddr>""", RegexOption.DOT_MATCHES_ALL)
            .find(sec)?.groupValues?.getOrNull(1)?.trim() ?: return null
        return runCatching { java.net.URI(xaddr).path?.takeIf { !it.isNullOrBlank() } }.getOrNull()
    }

    private val PROBE_MESSAGE = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
 xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
 xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
 xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
 <s:Header>
  <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>
  <a:MessageID>urn:uuid:cm-${System.currentTimeMillis()}</a:MessageID>
  <a:ReplyTo><a:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>
  <a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>
 </s:Header>
 <s:Body>
  <d:Probe>
   <d:Types>dn:NetworkVideoTransmitter</d:Types>
  </d:Probe>
 </s:Body>
</s:Envelope>""".trimIndent()

    /**
     * Probe the LAN for ONVIF devices. Returns a list of discovered devices with
     * their service URLs.
     *
     * 实现要点（参考 ONVIF Core Specification v22.12 + 开源 onvif-java 实现）：
     *  - 必须使用 MulticastSocket 并 joinGroup(239.255.255.250)，否则很多支持 IGMP
     *    snooping 的交换机会把组播包丢弃，摄像头收不到探测、App 也收不到响应；
     *  - 连续发送 3 次 Probe（间隔 300ms），防止丢包；
     *  - 超时 2500ms；
     *  - 需要 WiFi MulticastLock（由调用方 DeviceScanActivity 持有）。
     */
    suspend fun discover(): List<ScannedDevice> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, ScannedDevice>()
        var socket: java.net.MulticastSocket? = null
        val groupAddr = InetAddress.getByName(MULTICAST_ADDR)
        try {
            socket = java.net.MulticastSocket().apply {
                soTimeout = DISCOVERY_TIMEOUT_MS
                broadcast = true
                reuseAddress = true
                // 关键：加入组播组才能收到摄像头返回的响应
                runCatching { joinGroup(groupAddr) }
                    .onFailure { Log.w(TAG, "joinGroup failed: ${it.message}") }
            }
            val msg = PROBE_MESSAGE.toByteArray()
            // 连发 3 次防丢包
            repeat(3) {
                runCatching {
                    val packet = DatagramPacket(msg, msg.size, groupAddr, MULTICAST_PORT)
                    socket.send(packet)
                }
                try { kotlinx.coroutines.delay(300L) } catch (_: Exception) {}
            }

            val buf = ByteArray(8192)
            val deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val response = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(response)
                    val text = String(response.data, 0, response.length)
                    parseProbeResponse(text, response.address.hostAddress ?: "")
                        ?.let { dev ->
                            // dedupe by host
                            results.putIfAbsent(dev.host, dev)
                        }
                } catch (_: SocketTimeoutException) {
                    break
                } catch (e: Exception) {
                    Log.v(TAG, "receive loop: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "discover failed: ${e.message}")
        } finally {
            runCatching { socket?.leaveGroup(groupAddr) }
            runCatching { socket?.close() }
        }
        results.values.toList()
    }

    private fun parseProbeResponse(text: String, host: String): ScannedDevice? {
        // Extract XAddrs (service URLs) and scopes (manufacturer/model)
        val xaddr = Regex("XAddrs>(.*?)<").find(text)?.groupValues?.getOrNull(1)
            ?.trim()?.split(" ")?.firstOrNull()
        val mfr = Regex("onvif://www.onvif.org/hardware/(.*?)\\s|<")
            .find(text)?.groupValues?.getOrNull(1)?.trim() ?: "ONVIF"
        val onvifPort = if (xaddr != null) {
            Regex(":(\\d+)/").find(xaddr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 80
        } else 80
        return ScannedDevice(
            host = host,
            port = onvifPort,
            manufacturer = mfr,
            model = mfr,
            onvif = true,
            rtspSupported = true
        )
    }

    /**
     * Send a PTZ direction command. [pan] and [tilt] are in [-1, 1], [zoom] in [-1, 1].
     * A value of 0 on all axes means stop.
     */
    suspend fun ptzMove(
        device: Device,
        profileToken: String,
        pan: Float,
        tilt: Float,
        zoom: Float = 0f
    ): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        val body = if (pan == 0f && tilt == 0f && zoom == 0f) {
            """<tptz:Stop>
                 <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                 <tptz:PanTilt>true</tptz:PanTilt>
                 <tptz:Zoom>true</tptz:Zoom>
               </tptz:Stop>"""
        } else {
            """<tptz:ContinuousMove>
                 <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                 <tptz:Velocity>
                   <tt:PanTilt x="$pan" y="$tilt" xmlns:tt="http://www.onvif.org/ver10/schema"/>
                   <tt:Zoom x="$zoom" xmlns:tt="http://www.onvif.org/ver10/schema"/>
                 </tptz:Velocity>
               </tptz:ContinuousMove>"""
        }
        soapRequest(device, OnvifService.PTZ, "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove", body) != null
    }

    /**
     * Trigger a remote sound/light deterrence via the event service (best-effort).
     */
    suspend fun triggerDeterrence(device: Device): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0) return@withContext false
        val body = """<tev:TriggerAlarm>
                       <tev:Alarm>1</tev:Alarm>
                     </tev:TriggerAlarm>"""
        soapRequest(device, OnvifService.EVENTS, "http://www.onvif.org/ver10/events/wsdl/TriggerAlarm", body) != null
    }

    /** Go to a saved PTZ preset by index. */
    suspend fun gotoPreset(device: Device, profileToken: String, index: Int): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        val body = """<tptz:GotoPreset>
                       <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                       <tptz:PresetToken>$index</tptz:PresetToken>
                     </tptz:GotoPreset>"""
        soapRequest(device, OnvifService.PTZ, "http://www.onvif.org/ver20/ptz/wsdl/GotoPreset", body) != null
    }

    /** Save / overwrite a PTZ preset at the current position. */
    suspend fun setPreset(device: Device, profileToken: String, index: Int, name: String): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        val body = """<tptz:SetPreset>
                       <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                       <tptz:PresetName>$name</tptz:PresetName>
                       <tptz:PresetToken>$index</tptz:PresetToken>
                     </tptz:SetPreset>"""
        soapRequest(device, OnvifService.PTZ, "http://www.onvif.org/ver20/ptz/wsdl/SetPreset", body) != null
    }

    /** Remove a saved PTZ preset. */
    suspend fun removePreset(device: Device, profileToken: String, index: Int): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        val body = """<tptz:RemovePreset>
                       <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                       <tptz:PresetToken>$index</tptz:PresetToken>
                     </tptz:RemovePreset>"""
        soapRequest(device, OnvifService.PTZ, "http://www.onvif.org/ver20/ptz/wsdl/RemovePreset", body) != null
    }

    /** List all saved presets. */
    suspend fun listPresets(device: Device, profileToken: String): List<com.cameramanager.app.vendor.Preset> = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext emptyList()
        val body = """<tptz:GetPresets><tptz:ProfileToken>$profileToken</tptz:ProfileToken></tptz:GetPresets>"""
        val resp = soapRequest(device, OnvifService.PTZ, "http://www.onvif.org/ver20/ptz/wsdl/GetPresets", body) ?: return@withContext emptyList()
        // Parse <tt:Preset token="N"><tt:Name>...</tt:Name>...</tt:Preset>
        Regex("<tt:Preset\\s+token=\"(\\d+)\"[^>]*>(?:.*?<tt:Name>(.*?)</tt:Name>)?", RegexOption.DOT_MATCHES_ALL)
            .findAll(resp).map { m ->
                com.cameramanager.app.vendor.Preset(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "预置位 ${m.groupValues[1]}" })
            }.toList()
    }

    /** Toggle automatic patrol tour (cruise). */
    suspend fun setAutoTour(device: Device, profileToken: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        val op = if (enabled) "Start" else "Stop"
        val body = """<tptz:${op}Tour><tptz:ProfileToken>$profileToken</tptz:ProfileToken><tptz:TourToken>Tour_1</tptz:TourToken></tptz:${op}Tour>"""
        soapRequest(device, OnvifService.PTZ, "http://www.onvif.org/ver20/ptz/wsdl/${op}Tour", body) != null
    }

    /** Reboot the device via ONVIF device service. */
    suspend fun reboot(device: Device): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        soapRequest(device, OnvifService.DEVICE, "http://www.onvif.org/ver10/device/wsdl/SystemReboot", "<tds:SystemReboot/>") != null
    }

    /** Set night vision (IR cut filter) mode via ONVIF Imaging service.
     *  mode: 0=Auto, 1=ON(IR), 2=OFF(Color).
     *  参考: ONVIF Imaging Service Specification v20.06 */
    suspend fun setIrCutFilter(device: Device, mode: Int): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        val irCutMode = when (mode) {
            1 -> "ON"     // 红外夜视
            2 -> "OFF"    // 全彩/白光
            else -> "AUTO" // 自动
        }
        val body = """<timg:SetImagingSettings>
            <timg:VideoSourceToken>VideoSource_1</timg:VideoSourceToken>
            <timg:ImagingSettings>
                <tt:IrCutFilter xmlns:tt="http://www.onvif.org/ver10/schema">$irCutMode</tt:IrCutFilter>
            </timg:ImagingSettings>
        </timg:SetImagingSettings>"""
        soapRequest(device, OnvifService.IMAGING,
            "http://www.onvif.org/ver20/imaging/wsdl/SetImagingSettings", body) != null
    }

    /**
     * 获取设备真实抓拍图 URL（ONVIF Media GetSnapshotUri）。
     * 返回该 Profile 的 JPEG 抓拍地址；失败返回 null。
     * 参考：ONVIF Media Service GetSnapshotUri —— 部分设备需要用该 URL 直接 GET 得到 JPEG。
     */
    suspend fun getSnapshotUrl(device: Device, profileToken: String): String? = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext null
        val body = """<trt:GetSnapshotUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl"><trt:ProfileToken>$profileToken</trt:ProfileToken></trt:GetSnapshotUri>"""
        val act = "http://www.onvif.org/ver10/media/wsdl/GetSnapshotUri"
        val resp = soapRequest(device, OnvifService.MEDIA, act, body) ?: return@withContext null
        Regex("<[\\w:]*Uri[^>]*>([^<]+)<").find(resp)?.groupValues?.getOrNull(1)?.trim()
    }

    /**
     * 下载抓拍图字节（HTTP GET，带 ONVIF 鉴权）。返回 JPEG 字节；失败返回 null。
     * 抓拍 URL 通常形如 http://host:port/onvif/snapshot?profile=...
     */
    suspend fun fetchSnapshotBytes(device: Device, url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val parsed = java.net.URI(url)
            val host = parsed.host ?: device.host
            val port = if (parsed.port > 0) parsed.port else device.onvifPort
            val path = (parsed.path ?: "/") + (parsed.query?.let { "?$it" } ?: "")
            val conn = (java.net.URL("http://$host:$port$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (!device.username.isNullOrEmpty()) {
                val auth = "Basic " + android.util.Base64.encodeToString(
                    "${device.username}:${device.password.orEmpty()}".toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                conn.setRequestProperty("Authorization", auth)
            }
            val code = conn.responseCode
            if (code in 200..299) conn.inputStream.use { it.readBytes() }
            else { Log.w(TAG, "snapshot GET failed: HTTP $code"); null }
        } catch (e: Exception) {
            Log.w(TAG, "snapshot fetch error: ${e.message}")
            null
        }
    }

    /** 读取设备信息（厂商/型号/固件/序列号/硬件ID），GetDeviceInformation。 */
    suspend fun getDeviceInformation(device: Device): Map<String, String> = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext emptyMap()
        val body = """<tds:GetDeviceInformation xmlns:tds="http://www.onvif.org/ver10/device/wsdl"/>"""
        val resp = soapRequest(device, OnvifService.DEVICE,
            "http://www.onvif.org/ver10/device/wsdl/GetDeviceInformation", body)
            ?: return@withContext emptyMap()
        fun txt(name: String) = Regex("<[\\w:]*$name[^>]*>([^<]+)<")
            .find(resp)?.groupValues?.getOrNull(1)?.trim() ?: ""
        mapOf(
            "Manufacturer" to txt("Manufacturer"),
            "Model" to txt("Model"),
            "FirmwareVersion" to txt("FirmwareVersion"),
            "SerialNumber" to txt("SerialNumber"),
            "HardwareId" to txt("HardwareId")
        )
    }

    /** 从 GetProfiles 应答解析 VideoSourceToken（无则回退 "VideoSource_1"）。 */
    private suspend fun resolveVideoSourceToken(device: Device): String {
        val body = """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>"""
        val act = "http://www.onvif.org/ver10/media/wsdl/GetProfiles"
        val resp = soapRequest(device, OnvifService.MEDIA, act, body) ?: return "VideoSource_1"
        return Regex("<[\\w:]*VideoSourceToken[^>]*>([^<]+)<").find(resp)?.groupValues?.getOrNull(1)?.trim()
            ?: "VideoSource_1"
    }

    /** 读取摄像头图像参数（亮度/对比度/饱和度/锐度，ONVIF Imaging GetImagingSettings）。 */
    suspend fun getImageSettings(device: Device): com.cameramanager.app.vendor.ImageSettings = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext com.cameramanager.app.vendor.ImageSettings()
        val token = resolveVideoSourceToken(device)
        val body = """<timg:GetImagingSettings xmlns:timg="http://www.onvif.org/ver20/imaging/wsdl"><timg:VideoSourceToken>$token</timg:VideoSourceToken></timg:GetImagingSettings>"""
        val resp = soapRequest(device, OnvifService.IMAGING,
            "http://www.onvif.org/ver20/imaging/wsdl/GetImagingSettings", body)
            ?: return@withContext com.cameramanager.app.vendor.ImageSettings()
        fun pct(name: String) = Regex("<[\\w:]*$name[^>]*>(\\d+)<")
            .find(resp)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) ?: 50
        com.cameramanager.app.vendor.ImageSettings(
            brightness = pct("Brightness"),
            contrast = pct("Contrast"),
            saturation = pct("ColorSaturation"),
            sharpness = pct("Sharpness")
        )
    }

    /** 写入摄像头图像参数（ONVIF Imaging SetImagingSettings）。各值范围 0~100。 */
    suspend fun setImageSettings(device: Device, s: com.cameramanager.app.vendor.ImageSettings): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        val token = resolveVideoSourceToken(device)
        val body = """<timg:SetImagingSettings>
  <timg:VideoSourceToken>$token</timg:VideoSourceToken>
  <timg:ImagingSettings>
    <tt:Brightness xmlns:tt="http://www.onvif.org/ver10/schema">${s.brightness}</tt:Brightness>
    <tt:Contrast xmlns:tt="http://www.onvif.org/ver10/schema">${s.contrast}</tt:Contrast>
    <tt:ColorSaturation xmlns:tt="http://www.onvif.org/ver10/schema">${s.saturation}</tt:ColorSaturation>
    <tt:Sharpness xmlns:tt="http://www.onvif.org/ver10/schema">${s.sharpness}</tt:Sharpness>
  </timg:ImagingSettings>
</timg:SetImagingSettings>"""
        soapRequest(device, OnvifService.IMAGING,
            "http://www.onvif.org/ver20/imaging/wsdl/SetImagingSettings", body) != null
    }


    /**
     * 按服务类型动态定位路径发送 SOAP，多路径回退：
     *  优先 GetCapabilities 解析出的 XAddr 路径，其次标准路径，最后 /onvif/device_service。
     * 返回 response body，全部失败返回 null。标准流程见飞扬青云视频监控系统 ONVIF 模块。
     */
    private suspend fun soapRequest(
        device: Device,
        service: OnvifService,
        action: String,
        bodyXml: String
    ): String? {
        val eps = resolveEndpoints(device)
        val paths = when (service) {
            OnvifService.DEVICE -> listOf("/onvif/device_service")
            OnvifService.MEDIA -> listOfNotNull(eps.media, "/onvif/Media", "/onvif/device_service").distinct()
            OnvifService.PTZ -> listOfNotNull(eps.ptz, "/onvif/ptz_service", "/onvif/device_service").distinct()
            OnvifService.IMAGING -> listOfNotNull(eps.imaging, "/onvif/imaging", "/onvif/device_service").distinct()
            OnvifService.EVENTS -> listOf("/onvif/event_service", "/onvif/device_service").distinct()
        }
        for (p in paths) {
            val r = soapRequestToPath(device, p, action, bodyXml)
            if (r != null) return r
        }
        return null
    }

    /** SOAP request to a specific ONVIF service path (e.g., /onvif/imaging, /onvif/ptz). */
    private fun soapRequestToPath(
        device: Device,
        servicePath: String,
        action: String,
        bodyXml: String
    ): String? {
        val url = "http://${device.host}:${device.onvifPort}$servicePath"
        // 尝试1：标准 WS-UsernameToken PasswordDigest
        val r1 = try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            }
            val nonce = (1..16).map { (it * 7 % 256).toByte() }.toByteArray()
            val created = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date())
            val digest = android.util.Base64.encodeToString(
                java.security.MessageDigest.getInstance("SHA-1")
                    .digest(nonce + created.toByteArray() + device.password!!.toByteArray()),
                android.util.Base64.NO_WRAP
            )
            val envelope = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
 xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
 xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
 xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"
 xmlns:tev="http://www.onvif.org/ver10/events/wsdl">
 <s:Header>
  <wsa:Action xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing">$action</wsa:Action>
  <wsse:Security>
   <wsse:UsernameToken>
     <wsse:Username>${device.username}</wsse:Username>
     <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</wsse:Password>
     <wsse:Nonce>${android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)}</wsse:Nonce>
     <wsu:Created>$created</wsu:Created>
   </wsse:UsernameToken>
  </wsse:Security>
 </s:Header>
 <s:Body>$bodyXml</s:Body>
</s:Envelope>""".trimIndent()
            conn.outputStream.use { it.write(envelope.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
            else { Log.w(TAG, "soap $action failed: HTTP $code"); null }
        } catch (e: Exception) {
            Log.w(TAG, "soap $action error: ${e.message}")
            null
        }
        if (r1 != null) return r1

        // 尝试2：HTTP Basic 鉴权（兼容某些只认 Basic 的 ONVIF 设备）
        if (device.username.isNullOrEmpty()) return null
        val basicAuth = "Basic " + android.util.Base64.encodeToString(
            "${device.username}:${device.password.orEmpty()}".toByteArray(),
            android.util.Base64.NO_WRAP
        )
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
                setRequestProperty("Authorization", basicAuth)
            }
            val envelope = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
 <s:Header>
  <wsa:Action xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing">$action</wsa:Action>
 </s:Header>
 <s:Body>$bodyXml</s:Body>
</s:Envelope>""".trimIndent()
            conn.outputStream.use { it.write(envelope.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
            else { Log.w(TAG, "soap $action Basic failed: HTTP $code"); null }
        } catch (e: Exception) {
            Log.w(TAG, "soap $action Basic error: ${e.message}")
            null
        }
    }
}
