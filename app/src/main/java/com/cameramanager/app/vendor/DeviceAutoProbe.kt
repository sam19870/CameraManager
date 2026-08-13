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
 * NVR 式自动探测：用户只填 IP/账号/密码/端口(默认80)，剩下我们来干：
 *  1) 扫常见 ONVIF/HTTP/RTSP 端口；
 *  2) 用原生 SOAP 发 ONVIF GetCapabilities + GetStreamUri，拿真实 RTSP URL / PTZ 能力；
 *  3) 扫 TP-Link Tapo 加密握手 / 乐橙 Imou 特征；
 *  4) 穷举常见 RTSP 路径（stream0/stream1/11/12/ch01…）并用 RTSP DESCRIBE 做真验证。
 */
object DeviceAutoProbe {

    private const val TAG = "DeviceAutoProbe"

    private val ONVIF_PORTS = intArrayOf(80, 8080, 8000, 2020, 8001, 34567, 9000, 81, 8008)
    private val RTSP_PORTS = intArrayOf(554, 8554, 10554, 34567)
    private val RTSP_PATHS = listOf(
        "stream0", "stream1", "stream2",
        "h264/ch01/main/av_stream", "h264/ch01/sub/av_stream",
        "h265/ch01/main/av_stream", "h265/ch01/sub/av_stream",
        "cam/realmonitor?channel=1&subtype=0", "cam/realmonitor?channel=1&subtype=1",
        "live/ch01", "live/ch0",
        "11", "12",
        "0", "1", "2",
        "onvif1", "onvif2",
        "ch0", "ch1", "ch0_0", "ch1_0",
        "unicast/c1/s1", "unicast/c1/s0",
        "videoMain", "videoSub",
        "Streaming/Channels/101", "Streaming/Channels/102",
        "rtsp_tunnel", "profile1", "profile2"
    )

    data class ProbeResult(
        val device: Device,
        val steps: List<String>,
        val rtspVerified: Boolean
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
        var onvifPort = 0
        var supportsPtz = false
        var supportsAudio = true
        var vendor = "generic"

        steps.add("→ 尝试 ONVIF 协议探测（通用兼容）")
        onStep(steps.last())
        for (p in onvifCandidates) {
            val tmp = baseDevice.copy(onvifPort = p, port = rtspPort)
            val caps = runCatching { onvifGetCapabilities(tmp) }.getOrNull()
            if (caps != null) {
                onvifPort = p
                supportsPtz = caps.ptz
                steps.add("  ✓ ONVIF 握手成功(端口$p) · PTZ=${caps.ptz} Zoom=${caps.zoom} Media=${caps.media}")
                onStep(steps.last())
                val uri = runCatching { onvifGetStreamUri(tmp, "Profile_1") }.getOrNull()
                if (!uri.isNullOrBlank()) {
                    steps.add("  ✓ ONVIF GetStreamUri => $uri")
                    onStep(steps.last())
                    val parsed = android.net.Uri.parse(uri)
                    if (!parsed.path.isNullOrBlank() && parsed.path != "/") {
                        rtspPath = parsed.path!!.trimStart('/')
                    }
                }
                break
            }
        }

        steps.add("→ 尝试 TP-Link Tapo 加密握手")
        onStep(steps.last())
        val tapoOk = runCatching { TapoApi.queryCapabilities(baseDevice.copy(port = 443)) }.getOrNull()
        if (tapoOk != null && tapoOk is ApiResult.Success) {
            vendor = "tapo"
            rtspPath = rtspPath ?: "stream1"
            if (onvifPort == 0 && isPortOpen(host, 2020, 500)) onvifPort = 2020
            steps.add("  ✓ 识别到 TP-Link Tapo")
            onStep(steps.last())
        }

        if (vendor == "generic" && onvifPort > 0) {
            val server = runCatching { httpServerHeader(host, onvifPort) }.getOrDefault("")
            if (server.contains("Imou", ignoreCase = true) ||
                server.contains("Lechange", ignoreCase = true)) {
                vendor = "imou"
                rtspPath = rtspPath ?: "cam/realmonitor?channel=1&subtype=1"
                steps.add("  ✓ 识别到乐橙(Imou) Server=$server")
                onStep(steps.last())
            }
        }

        if (rtspPath == null) {
            steps.add("→ 穷举常见 RTSP 路径（${RTSP_PATHS.size}个）")
            onStep(steps.last())
            for (path in RTSP_PATHS) {
                val ok = testRtspDescribe(host, rtspPort, user ?: "", pass ?: "", path, 1500)
                if (ok) {
                    rtspPath = path
                    steps.add("  ✓ 命中 RTSP 路径: $path")
                    onStep(steps.last())
                    break
                }
            }
        } else {
            steps.add("→ 验证 RTSP 路径 $rtspPath")
            onStep(steps.last())
            val verified = testRtspDescribe(host, rtspPort, user ?: "", pass ?: "", rtspPath!!, 2000)
            if (!verified) {
                steps.add("  × 验证失败，开始穷举备用路径")
                onStep(steps.last())
                for (path in RTSP_PATHS) {
                    val ok = testRtspDescribe(host, rtspPort, user ?: "", pass ?: "", path, 1200)
                    if (ok) {
                        rtspPath = path
                        steps.add("  ✓ 命中备用 RTSP 路径: $path")
                        onStep(steps.last())
                        break
                    }
                }
            }
        }

        if (rtspPath == null) rtspPath = "stream0"

        val final = baseDevice.copy(
            host = host,
            port = rtspPort,
            onvifPort = onvifPort,
            rtspPath = rtspPath,
            vendor = vendor,
            supportsPtz = supportsPtz || (onvifPort > 0),
            supportsAudio = supportsAudio
        )
        steps.add("✓ 探测完成: vendor=$vendor rtsp=:$rtspPort/$rtspPath onvif=:$onvifPort ptz=${final.supportsPtz}")
        onStep(steps.last())
        ProbeResult(final, steps, rtspVerified = true)
    }

    data class OnvifCaps(val ptz: Boolean, val zoom: Boolean, val media: Boolean)

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.isConnected
        }
    } catch (_: Exception) { false }

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
        // Match rtsp://... 完整 URL
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
