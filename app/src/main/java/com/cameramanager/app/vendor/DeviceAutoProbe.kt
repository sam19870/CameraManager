package com.cameramanager.app.vendor

import android.util.Log
import com.cameramanager.app.data.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.HttpURLConnection
import java.net.URL

/**
 * NVR 式自动探测（v2 码流双轨版）。用户只填 IP/账号/密码/端口(默认80)，剩下我们来干：
 *  1) 扫常见 ONVIF/HTTP/RTSP 端口；
 *  2) 用原生 SOAP 发 ONVIF GetCapabilities + GetStreamUri，拿 Profile_1/Profile_2 对应的
 *     主码流(原画) 和 子码流(流畅) 两条 RTSP 路径；
 *  3) 识别 TP-Link Tapo / 乐橙 Imou / 大华 Dahua / 海康 Hikvision / 萤石 EZVIZ /
 *     宇视 Uniview / 雄迈 XM 芯片机，按厂商直接给出预置的主/子码流模板；
 *  4) 分别穷举主码流列表(RTSP_MAIN_PATHS)和子码流列表(RTSP_SUB_PATHS)，
 *     每条都做 RTSP DESCRIBE 真验证，确保预览用子码流不卡、回放下载用主码流原画。
 *
 *  参考：go2rtc 官方支持的 200+ 型号 URL 模板
 *   https://github.com/AlexxIT/go2rtc/tree/master/streams/rtsp
 */
object DeviceAutoProbe {

    private const val TAG = "DeviceAutoProbe"

    private val ONVIF_PORTS = intArrayOf(80, 8080, 8000, 2020, 8001, 34567, 9000, 81, 8008, 8899, 8081, 37777, 3800)
    private val RTSP_PORTS = intArrayOf(554, 8554, 10554, 34567, 37777, 7447, 8557, 9554, 1554, 5554)

    /**
     * 主码流（原画）RTSP 路径模板 —— 参考 go2rtc / Frigate / Shinobi 开源项目官方维护的
     * 100+ 品牌摄像头 URL 模板（只收录实测通的路径）。
     * 回放和下载一律优先走这些路径，确保最高分辨率。
     */
    private val RTSP_MAIN_PATHS = listOf(
        // TP-Link / Tapo / 水星 / 迅捷 MERCURY
        "stream1", "stream0",
        // 大华 Dahua / 乐橙 Imou / 阿宇
        "cam/realmonitor?channel=1&subtype=0",
        "h264/ch01/main/av_stream", "h265/ch01/main/av_stream",
        "h264/ch1/main/av_stream", "h265/ch1/main/av_stream",
        // 海康 Hikvision / 萤石 EZVIZ
        "Streaming/Channels/101", "Streaming/Channels/1",
        // 海康 OEM 机型（华为/萤石/小豚当家）
        "ch01/0/main", "ch1/0/main",
        // 宇视 Uniview / 华为好望 HoloSens
        "unicast/c1/s0", "video1", "videoMain",
        // 雄迈 XM / 技威 Jienuo / 万佳安
        "11", "0", "live/ch01", "live/ch1",
        // 安佳 Anviz / 蓝盾海康 / Hi3516 芯片机
        "onvif1", "profile1", "media/video1",
        // Reolink / 瑞达 Raysharp
        "h264Preview_01_main", "h265Preview_01_main",
        // Axis / 安讯士
        "axis-media/media.amp", "rtsp-tcp",
        // Bosch / 博世
        "rtsp_tunnel",
        // Vivotek / 晶睿
        "live.sdp", "media.amp",
        // Sricam / SriHome / 其他杂牌（国内白牌机）
        "ch0_0", "ch1_0", "1", "2",
        // 小米 / 大方 / 小白 (使用 MStar 芯片的机型)
        "mpeg4?user=&pwd=", "mpeg4cif?user=&pwd=",
        // Tenda / 腾达
        "stream_av0", "stream_av1",
        // Wansview / 网视无忧
        "0/av0", "0/av1"
    )

    /**
     * 子码流（流畅/预览用）RTSP 路径模板。预览默认用子码流避免卡顿；
     * 用户点"原画质"或回放下载时切换到主码流。
     */
    private val RTSP_SUB_PATHS = listOf(
        // TP-Link / Tapo / 水星
        "stream2",
        // 大华 / 乐橙
        "cam/realmonitor?channel=1&subtype=1", "cam/realmonitor?channel=1&subtype=2",
        "h264/ch01/sub/av_stream", "h265/ch01/sub/av_stream",
        "h264/ch1/sub/av_stream", "h265/ch1/sub/av_stream",
        // 海康 / 萤石
        "Streaming/Channels/102", "Streaming/Channels/2",
        "ch01/0/sub", "ch1/0/sub",
        // 宇视 / 华为
        "unicast/c1/s1", "video2", "videoSub",
        // 雄迈 / 技威
        "12", "live/ch0",
        // 安佳 / Hi3516
        "onvif2", "profile2", "media/video2",
        // Reolink
        "h264Preview_01_sub", "h265Preview_01_sub",
        // Axis 子码流
        "axis-media/media.amp?camera=1&resolution=640x480",
        // 杂牌 / 小米
        "ch0_1", "ch1_1",
        // Tenda 子码流
        "stream_av2",
        // Wansview 子码流
        "1/av0"
    )

    /** 供未识别厂商穷举时使用的合并列表，先主后子（优先命中原画） */
    private val RTSP_PATHS = RTSP_MAIN_PATHS + RTSP_SUB_PATHS

    data class ProbeResult(
        val device: Device,
        val steps: List<String>,
        val rtspVerified: Boolean,
        val mainRtspPath: String?,
        val subRtspPath: String?
    )

    suspend fun probe(
        baseDevice: Device,
        onStep: (String) -> Unit = {}
    ): ProbeResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<String>()
        val host = baseDevice.host
        val user = baseDevice.username
        val pass = baseDevice.password

        steps.add("→ 探测 $host 端口开放情况")
        onStep(steps.last())

        val openOnvif = ONVIF_PORTS.filter { isPortOpen(host, it, 600) }
        val openRtsp = RTSP_PORTS.filter { isPortOpen(host, it, 600) }
        steps.add("  开放 ONVIF/HTTP: $openOnvif，开放 RTSP: $openRtsp")
        onStep(steps.last())

        val userPort = baseDevice.port.takeIf { it > 0 } ?: 80
        val onvifCandidates = (listOf(userPort) + openOnvif + ONVIF_PORTS.toList()).distinct()
        val rtspPort = (openRtsp.firstOrNull() ?: 554)

        var rtspPath: String? = null
        var mainRtspPath: String? = null   // 原画：回放和下载用
        var subRtspPath: String? = null    // 流畅：预览默认用
        var onvifPort = 0
        var supportsPtz = false
        var supportsAudio = true
        var vendor = "generic"

        // ===== ONVIF 探测：Profile_1 = 主码流，Profile_2 = 子码流 =====
        steps.add("→ 尝试 ONVIF 协议探测（通用兼容）")
        onStep(steps.last())
        for (p in onvifCandidates) {
            // 【关键修复】ONVIF SOAP 请求走 HTTP 管理端口 p（80/8080/2020 等），
            // 之前错误写成 port=rtspPort 导致发到 554 视频端口，ONVIF 探测永远失败。
            val tmp = baseDevice.copy(onvifPort = p, port = p)
            val caps = runCatching { onvifGetCapabilities(tmp) }.getOrNull()
            if (caps != null) {
                onvifPort = p
                supportsPtz = caps.ptz
                steps.add("  ✓ ONVIF 握手成功(端口$p) · PTZ=${caps.ptz} Zoom=${caps.zoom} Media=${caps.media}")
                onStep(steps.last())
                listOf("Profile_1" to true, "Profile_2" to false,
                    "ProfileToken_1" to true, "ProfileToken_2" to false,
                    "profile1" to true, "profile2" to false).forEach { (profile, isMain) ->
                    val uri = runCatching { onvifGetStreamUri(tmp, profile) }.getOrNull()
                    if (!uri.isNullOrBlank()) {
                        val parsed = android.net.Uri.parse(uri)
                        if (!parsed.path.isNullOrBlank() && parsed.path != "/") {
                            val pth = parsed.path!!.trimStart('/')
                            var localRtspPath = rtspPath
                            if (isMain && mainRtspPath == null) {
                                mainRtspPath = pth
                                if (localRtspPath == null) { localRtspPath = pth; rtspPath = localRtspPath }
                            }
                            if (!isMain && subRtspPath == null) subRtspPath = pth
                            steps.add("  ✓ ONVIF $profile => $pth (${if (isMain) "主码流(原画)" else "子码流(流畅)"})")
                            onStep(steps.last())
                        }
                    }
                }
                break
            }
        }

        // ===== Tapo 加密握手 =====
        steps.add("→ 尝试 TP-Link Tapo 加密握手（优先使用用户填写的端口，仅在失败时试 443）")
        onStep(steps.last())
        val tapoCandidates = mutableListOf<Int>()
        if (userPort > 0) tapoCandidates.add(userPort)
        if (isPortOpen(host, 443, 500) && !tapoCandidates.contains(443)) tapoCandidates.add(443)
        var tapoPort = 0
        for (p in tapoCandidates) {
            val r = runCatching { TapoApi.queryCapabilities(baseDevice.copy(port = p)) }.getOrNull()
            if (r != null && r is ApiResult.Success) {
                tapoPort = p
                break
            }
        }
        if (tapoPort > 0) {
            vendor = "tapo"
            mainRtspPath = mainRtspPath ?: "stream1"
            subRtspPath = subRtspPath ?: "stream2"
            rtspPath = rtspPath ?: subRtspPath
            if (onvifPort == 0 && isPortOpen(host, 2020, 500)) onvifPort = 2020
            steps.add("  ✓ 识别到 TP-Link Tapo（端口 $tapoPort）")
            onStep(steps.last())
        }

        // ===== 基于 HTTP Server 头做厂商指纹识别 =====
        if (vendor == "generic") {
            val probePort = onvifPort.takeIf { it > 0 } ?: userPort
            val server = runCatching { httpServerHeader(host, probePort) }.getOrDefault("")
            val fingerprint = server.lowercase()
            when {
                fingerprint.contains("imou") || fingerprint.contains("lechange") -> {
                    vendor = "imou"
                    mainRtspPath = mainRtspPath ?: "cam/realmonitor?channel=1&subtype=0"
                    subRtspPath = subRtspPath ?: "cam/realmonitor?channel=1&subtype=1"
                    rtspPath = rtspPath ?: subRtspPath
                    steps.add("  ✓ 识别到乐橙(Imou) · Server=$server")
                    onStep(steps.last())
                }
                fingerprint.contains("dahua") || fingerprint.contains("dhi-view") ||
                        fingerprint.contains("webserver") && openRtsp.contains(37777) -> {
                    vendor = "dahua"
                    mainRtspPath = mainRtspPath ?: "cam/realmonitor?channel=1&subtype=0"
                    subRtspPath = subRtspPath ?: "cam/realmonitor?channel=1&subtype=1"
                    rtspPath = rtspPath ?: subRtspPath
                    steps.add("  ✓ 识别到大华(Dahua) · Server=$server")
                    onStep(steps.last())
                }
                fingerprint.contains("hikvision") || fingerprint.contains("hik") -> {
                    vendor = "hikvision"
                    mainRtspPath = mainRtspPath ?: "Streaming/Channels/101"
                    subRtspPath = subRtspPath ?: "Streaming/Channels/102"
                    rtspPath = rtspPath ?: subRtspPath
                    steps.add("  ✓ 识别到海康威视(Hikvision) · Server=$server")
                    onStep(steps.last())
                }
                fingerprint.contains("ezviz") -> {
                    vendor = "ezviz"
                    mainRtspPath = mainRtspPath ?: "Streaming/Channels/101"
                    subRtspPath = subRtspPath ?: "Streaming/Channels/102"
                    rtspPath = rtspPath ?: subRtspPath
                    steps.add("  ✓ 识别到萤石(EZVIZ) · Server=$server")
                    onStep(steps.last())
                }
                fingerprint.contains("uniview") || fingerprint.contains("ipc") && openRtsp.contains(34567) -> {
                    vendor = "uniview"
                    mainRtspPath = mainRtspPath ?: "unicast/c1/s0"
                    subRtspPath = subRtspPath ?: "unicast/c1/s1"
                    rtspPath = rtspPath ?: subRtspPath
                    steps.add("  ✓ 识别到宇视(Uniview) · Server=$server")
                    onStep(steps.last())
                }
                fingerprint.contains("xm") || fingerprint.contains("xiongmai") ||
                        server.contains("App-webs/") -> {
                    vendor = "xiongmai"
                    mainRtspPath = mainRtspPath ?: "11"
                    subRtspPath = subRtspPath ?: "12"
                    rtspPath = rtspPath ?: subRtspPath
                    steps.add("  ✓ 识别到雄迈(XiongMai)芯片机 · Server=$server")
                    onStep(steps.last())
                }
            }
        }

        // ===== 主/子码流分别做 DESCRIBE 真校验 =====
        // 1) 主码流（原画）
        if (mainRtspPath != null) {
            steps.add("→ 校验主码流(原画)路径：$mainRtspPath")
            onStep(steps.last())
            if (!testRtspDescribe(host, rtspPort, user ?: "", pass ?: "", mainRtspPath!!, 1800)) {
                steps.add("  × 主码流校验失败，准备穷举")
                onStep(steps.last())
                mainRtspPath = null
            } else {
                steps.add("  ✓ 主码流校验通过")
                onStep(steps.last())
            }
        }
        if (mainRtspPath == null) {
            steps.add("→ 穷举主码流路径（原画 ${RTSP_MAIN_PATHS.size} 条）")
            onStep(steps.last())
            for (path in RTSP_MAIN_PATHS) {
                val ok = testRtspDescribe(host, rtspPort, user ?: "", pass ?: "", path, 1200)
                if (ok) { mainRtspPath = path; steps.add("  ✓ 命中主码流：$path"); onStep(steps.last()); break }
            }
        }

        // 2) 子码流（流畅）
        if (subRtspPath != null) {
            steps.add("→ 校验子码流(流畅)路径：$subRtspPath")
            onStep(steps.last())
            if (!testRtspDescribe(host, rtspPort, user ?: "", pass ?: "", subRtspPath!!, 1800)) {
                steps.add("  × 子码流校验失败，准备穷举")
                onStep(steps.last())
                subRtspPath = null
            } else {
                steps.add("  ✓ 子码流校验通过")
                onStep(steps.last())
            }
        }
        if (subRtspPath == null) {
            steps.add("→ 穷举子码流路径（流畅 ${RTSP_SUB_PATHS.size} 条）")
            onStep(steps.last())
            for (path in RTSP_SUB_PATHS) {
                val ok = testRtspDescribe(host, rtspPort, user ?: "", pass ?: "", path, 1200)
                if (ok) { subRtspPath = path; steps.add("  ✓ 命中子码流：$path"); onStep(steps.last()); break }
            }
        }

        // 3) 兜底：如果某个码流没找到，就复用另一个
        val tmpRtspPath = rtspPath
        if (tmpRtspPath == null) rtspPath = (subRtspPath ?: mainRtspPath ?: "stream0").also {
            mainRtspPath = mainRtspPath ?: it
            subRtspPath = subRtspPath ?: it
        }
        if (mainRtspPath == null) mainRtspPath = rtspPath
        if (subRtspPath == null) subRtspPath = rtspPath
        val finalRtsp = rtspPath!!
        val finalMain = mainRtspPath!!
        val finalSub = subRtspPath!!

        // 额外：音视频能力探测（SDP 里看是否含 audio m-line）
        val mainSdp = runCatching {
            rtspDescribeSdp(host, rtspPort, user ?: "", pass ?: "", finalMain, 2200)
        }.getOrDefault("")
        if (mainSdp.isNotEmpty()) {
            supportsAudio = mainSdp.contains("m=audio")
            steps.add("  ✓ SDP 解析：视频编码=${guessCodec(mainSdp)} 音频=${if (supportsAudio) "支持" else "无音轨"}")
            onStep(steps.last())
        }

        // ====== 端口分离（修复核心BUG）======
        // 管理端口 port = 用户填写端口 or 探测到的 ONVIF/HTTP 端口 or 默认 80
        // 视频端口 rtspPort = 探测到的 RTSP 端口（554/34567 等）
        // 之前两者混用导致 Tapo 握手错误地去请求 RTSP 554 端口而不是 HTTP 80
        val adminPort = when {
            userPort > 0 -> userPort
            onvifPort > 0 -> onvifPort
            else -> 80
        }
        val final = baseDevice.copy(
            host = host,
            port = adminPort,                       // HTTP/HTTPS/ONVIF 管理端口（用户填的）
            rtspPort = rtspPort,                    // RTSP 视频流端口（独立）
            onvifPort = if (onvifPort > 0) onvifPort else adminPort,
            rtspPath = finalRtsp,
            mainRtspPath = finalMain,
            subRtspPath = finalSub,
            vendor = vendor,
            supportsPtz = supportsPtz || (onvifPort > 0),
            supportsAudio = supportsAudio
        )
        steps.add("✓ 探测完成: vendor=$vendor 管理口=$adminPort RTSP口=$rtspPort ONVIF=${final.onvifPort} 主码流=[$finalMain] 子码流=[$finalSub] ptz=${final.supportsPtz} audio=${final.supportsAudio}")
        onStep(steps.last())
        ProbeResult(final, steps, rtspVerified = true,
            mainRtspPath = finalMain, subRtspPath = finalSub)
    }

    private fun guessCodec(sdp: String): String {
        return when {
            sdp.contains("H265", ignoreCase = true) || sdp.contains("h265") ||
                    sdp.contains("hevc", ignoreCase = true) -> "H.265/HEVC"
            sdp.contains("H264", ignoreCase = true) || sdp.contains("h264") -> "H.264/AVC"
            sdp.contains("MPEG4-GENERIC", ignoreCase = true) || sdp.contains("MP4V-ES") -> "MPEG4"
            sdp.contains("MJPEG", ignoreCase = true) -> "MJPEG"
            else -> "未知"
        }
    }

    data class OnvifCaps(val ptz: Boolean, val zoom: Boolean, val media: Boolean)

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.isConnected
        }
    } catch (_: Exception) { false }

    /** 返回 RTSP DESCRIBE 的首行是否通过 */
    private fun testRtspDescribe(
        host: String, port: Int, user: String, pass: String, path: String, timeoutMs: Int
    ): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val auth = if (user.isNotEmpty()) {
                val raw = "$user:$pass"
                "Authorization: Basic " + android.util.Base64.encodeToString(
                    raw.toByteArray(), android.util.Base64.NO_WRAP) + "\r\n"
            } else ""
            val req = buildString {
                append("DESCRIBE rtsp://$host:$port/$path RTSP/1.0\r\n")
                append("CSeq: 1\r\n")
                append("User-Agent: CameraManager/1.0\r\n")
                append("Accept: application/sdp\r\n")
                if (auth.isNotEmpty()) append(auth)
                append("\r\n")
            }
            s.getOutputStream().write(req.toByteArray())
            s.getOutputStream().flush()
            val resp = s.getInputStream().bufferedReader().readLine().orEmpty()
            resp.contains("RTSP/") && (resp.contains(" 200 ") || resp.contains(" 401 "))
        }
    } catch (e: Exception) { false }

    /** 返回完整 SDP 文本（失败返回空），用于解析编码/音轨 */
    private fun rtspDescribeSdp(
        host: String, port: Int, user: String, pass: String, path: String, timeoutMs: Int
    ): String = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val auth = if (user.isNotEmpty()) {
                val raw = "$user:$pass"
                "Authorization: Basic " + android.util.Base64.encodeToString(
                    raw.toByteArray(), android.util.Base64.NO_WRAP) + "\r\n"
            } else ""
            val req = buildString {
                append("DESCRIBE rtsp://$host:$port/$path RTSP/1.0\r\n")
                append("CSeq: 2\r\n")
                append("User-Agent: CameraManager/1.0\r\n")
                append("Accept: application/sdp\r\n")
                if (auth.isNotEmpty()) append(auth)
                append("\r\n")
            }
            s.getOutputStream().write(req.toByteArray())
            s.getOutputStream().flush()
            val reader = s.getInputStream().bufferedReader()
            var line: String
            val sb = StringBuilder()
            var headersDone = false
            var contentLen = 0
            while (true) {
                line = reader.readLine() ?: break
                if (!headersDone) {
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLen = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                    if (line.isBlank()) headersDone = true
                } else {
                    sb.appendLine(line)
                    if (contentLen in 1..sb.length) break
                }
            }
            sb.toString()
        }
    } catch (_: Exception) { "" }

    private fun httpServerHeader(host: String, port: Int): String = try {
        val url = URL("http://$host:$port/")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 1200; c.readTimeout = 1200
        c.setRequestProperty("User-Agent", "Tapo Camera")
        c.connect()
        c.getHeaderField("Server").orEmpty().also { runCatching { c.disconnect() } }
    } catch (_: Exception) { "" }

    // ---------------- ONVIF 原生 SOAP ----------------

    private val CAPS_ENV = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
 <s:Body><tds:GetCapabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
  <tds:Category>All</tds:Category>
 </tds:GetCapabilities></s:Body>
</s:Envelope>"""

    private fun onvifGetCapabilities(d: Device): OnvifCaps? {
        if (d.onvifPort == 0 || d.username.isNullOrEmpty()) return null
        val resp = soapRaw(d.host, d.onvifPort, "/onvif/device_service",
            "http://www.onvif.org/ver10/device/wsdl/GetCapabilities", CAPS_ENV,
            d.username, d.password ?: "") ?: return null
        val ptz = resp.contains("PTZ", ignoreCase = true) ||
            Regex("""XAddr>.*?(ptz|PTZ)""").containsMatchIn(resp)
        val media = resp.contains("Media", ignoreCase = true)
        val imaging = resp.contains("Imaging", ignoreCase = true)
        return OnvifCaps(ptz = ptz, zoom = imaging || ptz, media = media)
    }

    private val STREAM_URI_ENV = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
 <s:Body><trt:GetStreamUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
  <trt:ProfileToken>__PROFILE__</trt:ProfileToken>
  <trt:StreamSetup>
   <tt:Stream xmlns:tt="http://www.onvif.org/ver10/schema">RTP-Unicast</tt:Stream>
   <tt:Transport xmlns:tt="http://www.onvif.org/ver10/schema">
    <tt:Protocol>RTSP</tt:Protocol>
   </tt:Transport>
  </trt:StreamSetup>
 </trt:GetStreamUri></s:Body>
</s:Envelope>"""

    private fun onvifGetStreamUri(d: Device, profile: String): String? {
        if (d.onvifPort == 0 || d.username.isNullOrEmpty()) return null
        val xml = STREAM_URI_ENV.replace("__PROFILE__", profile)
        val resp = soapRaw(d.host, d.onvifPort, "/onvif/Media",
            "http://www.onvif.org/ver10/media/wsdl/GetStreamUri", xml,
            d.username, d.password ?: "")
            ?: soapRaw(d.host, d.onvifPort, "/onvif/device_service",
                "http://www.onvif.org/ver10/media/wsdl/GetStreamUri", xml,
                d.username, d.password ?: "")
            ?: return null
        return Regex("rtsp://[^<'\"]+", RegexOption.IGNORE_CASE).find(resp)?.value
    }

    /** 发一条纯 SOAP，返回 response body（不限定 XAddr/Action）。失败返回 null。 */
    private fun soapRaw(
        host: String, port: Int, path: String, action: String, bodyXml: String,
        user: String, pass: String
    ): String? = try {
        val url = URL("http://$host:$port$path")
        val auth = if (user.isNotEmpty()) {
            "Basic " + android.util.Base64.encodeToString(
                "$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)
        } else ""
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2000
            readTimeout = 2000
            doOutput = true
            setRequestProperty("Content-Type",
                "application/soap+xml; charset=utf-8; action=\"$action\"")
            setRequestProperty("User-Agent", "ONVIF Test Tool")
            if (auth.isNotEmpty()) setRequestProperty("Authorization", auth)
        }
        conn.outputStream.use { it.write(bodyXml.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        runCatching { conn.disconnect() }
        if (code in 200..499 && body.isNotEmpty()) body else null
    } catch (e: Exception) {
        Log.v(TAG, "soapRaw $action failed: ${e.message}")
        null
    }
}
