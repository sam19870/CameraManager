package com.cameramanager.app.net

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import com.cameramanager.app.data.model.ScannedDevice
import com.cameramanager.app.rtsp.OnvifClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Semaphore

/**
 * LAN device scanner. Combines:
 *  - ONVIF WS-Discovery (multicast) — 先跑，速度快、能拿到厂商信息
 *  - TCP /24 端口扫描（并发限流，防止句柄耗尽）
 *     探测端口：80/443/8000/8080/2020（HTTP/ONVIF/管理）+ 554/34567/37777（RTSP）
 *     命中后立即发 HTTP GET / 看 Server 头，识别厂商（TP-Link/乐橙/大华/海康/萤石/宇视/雄迈）
 *     大幅提高 DeviceScanActivity 匹配 RTSP 路径的准确率（不用再猜 "ONVIF/RTSP"）。
 */
object NetworkScanner {

    private const val TAG = "NetworkScanner"
    /** HTTP/ONVIF/管理端口（顺序按常见度）。命中后，也会作为 adminPort 返回给上层。 */
    private val ADMIN_PORTS = intArrayOf(80, 443, 8000, 8080, 2020, 8001, 37777, 9000, 81, 8008, 8899, 8081, 3800)
    /** RTSP 视频端口（命中时 rtspSupported=true）。 */
    private val RTSP_PORTS = intArrayOf(554, 8554, 10554, 34567, 37777, 7447, 8557, 9554, 1554, 5554)
    private val ALL_PORTS: IntArray = (ADMIN_PORTS.toList() + RTSP_PORTS.toList()).distinct().toIntArray()

    /** 端口扫描并发上限（Android 默认 FD 上限 1024，保守用 64）。防止同时开 254*5 个 socket。 */
    private const val MAX_PARALLEL = 64
    private const val PORT_TIMEOUT_MS = 400
    private const val HTTP_TIMEOUT_MS = 900

    /** Returns the local IPv4 gateway/subnet base, e.g. "192.168.1". */
    fun getLocalSubnet(context: Context): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = Formatter.formatIpAddress(wifi.connectionInfo.ipAddress)
            val parts = ip.split(".")
            if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}" else null
        } catch (e: Exception) {
            Log.w(TAG, "getLocalSubnet: ${e.message}")
            null
        }
    }

    /**
     * Scan the local subnet for cameras. Reports progress via [onProgress] (0..100).
     * 进度：0-25 = ONVIF WS-Discovery；26-100 = TCP /24 端口扫。
     */
    suspend fun scan(
        context: Context,
        onProgress: (Int) -> Unit = {}
    ): List<ScannedDevice> = withContext(Dispatchers.IO) {
        val found = linkedSetOf<ScannedDevice>()
        onProgress(5)

        // 1) ONVIF discovery (multicast UDP 3702) — 最快，优先跑
        runCatching { OnvifClient.discover() }
            .onSuccess { found.addAll(it) }
            .onFailure { Log.w(TAG, "onvif discovery failed: ${it.message}") }
        onProgress(25)

        // 2) Port-scan the /24 subnet（并发限流）
        val base = getLocalSubnet(context) ?: return@withContext found.toList()
        val hosts = (1..254).map { "$base.$it" }
        val semaphore = Semaphore(MAX_PARALLEL)
        var done = 0

        coroutineScope {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        runCatching {
                            val device = probeHost(host)
                            if (device != null) {
                                synchronized(found) {
                                    if (!found.any { it.host == host }) {
                                        found.add(device)
                                    }
                                }
                            }
                        }.onFailure { e ->
                            Log.v(TAG, "probeHost $host failed: ${e.message}")
                        }
                    } finally {
                        semaphore.release()
                    }
                    synchronized(this) {
                        done++
                        runCatching { onProgress(25 + (done * 75 / hosts.size.coerceAtLeast(1))) }
                    }
                }
            }.awaitAll()
        }
        onProgress(100)
        found.toList()
    }

    /**
     * 扫一个主机：
     *   a) 先试几个常见端口（HTTP/ONVIF + RTSP）
     *   b) 命中任一端口后，立刻发 HTTP GET / 取 Server 头，识别厂商
     *   c) 返回 ScannedDevice（manufacturer 填真实厂商，DeviceScanActivity 就能选对 RTSP 路径）
     */
    private fun probeHost(host: String): ScannedDevice? {
        var openAdminPort = 0
        var openRtspPort = 0
        for (port in ALL_PORTS) {
            if (isPortOpen(host, port, PORT_TIMEOUT_MS)) {
                if (port in RTSP_PORTS || port == 554 || port == 34567 || port == 37777) {
                    if (openRtspPort == 0) openRtspPort = port
                }
                if (port in ADMIN_PORTS) {
                    if (openAdminPort == 0) openAdminPort = port
                }
                // 命中两个以上（既有管理口又有 RTSP 口）就不用再扫了
                if (openAdminPort > 0 && openRtspPort > 0) break
            }
        }
        if (openAdminPort == 0 && openRtspPort == 0) return null

        // 如果只开了 RTSP 口（没开管理口），就用 554 当回显端口，onvif=false
        val onvif = openAdminPort > 0
        val reportPort = if (onvif) openAdminPort else openRtspPort

        // 厂商指纹识别：HTTP GET / 看 Server 头（参考 TP-LINK/乐橙/大华/海康/雄迈/宇视 官方 Server 头）
        var manufacturer = when {
            onvif -> "ONVIF"
            else -> "RTSP"
        }
        var model = "IP Camera"
        if (onvif || openAdminPort > 0) {
            runCatching { httpServerHeader(host, openAdminPort.takeIf { it > 0 } ?: 80) }
                .getOrNull()?.also { server ->
                    val s = server.lowercase()
                    manufacturer = when {
                        s.contains("tp-link") || s.contains("tapo") -> "TPLink"
                        s.contains("imou") || s.contains("lechange") -> "Imou"
                        s.contains("dahua") || s.contains("dhi-view") -> "Dahua"
                        s.contains("hikvision") || s.contains("hik") -> "Hikvision"
                        s.contains("ezviz") -> "Ezviz"
                        s.contains("uniview") -> "Uniview"
                        s.contains("xm") || s.contains("xiongmai") || server.contains("App-webs/") -> "XiongMai"
                        else -> manufacturer
                    }
                }
        }
        return ScannedDevice(
            host = host,
            port = reportPort,
            manufacturer = manufacturer,
            model = model,
            onvif = onvif,
            rtspSupported = openRtspPort > 0
        )
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.isConnected
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun httpServerHeader(host: String, port: Int): String = try {
        val url = URL("http://$host:$port/")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = HTTP_TIMEOUT_MS
        c.readTimeout = HTTP_TIMEOUT_MS
        c.setRequestProperty("User-Agent", "Tapo Camera")
        c.connect()
        c.getHeaderField("Server").orEmpty().also { runCatching { c.disconnect() } }
    } catch (_: Exception) { "" }

    /**
     * 快速检测 IP:Port 是否可达。
     * 只用 TCP connect（socket连接），不用 InetAddress.isReachable()。
     * 原因：Android 上 isReachable() 需要 ICMP（root 权限），非 root 手机必定返回 false，
     * 导致即使 IP 不通也会被 || isPortOpen 的延迟掩盖，或直接误判。
     */
    fun testReachable(host: String, port: Int, timeoutMs: Int = 1000): Boolean =
        try {
            isPortOpen(host, port, timeoutMs)
        } catch (_: Exception) {
            false
        }
}
