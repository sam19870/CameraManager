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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * LAN device scanner. Combines:
 *  - ONVIF WS-Discovery (multicast).
 *  - TCP port probing for common camera ports (554 RTSP, 80/8000 ONVIF/HTTP)
 *    across the local /24 subnet.
 */
object NetworkScanner {

    private const val TAG = "NetworkScanner"
    private val CAMERA_PORTS = intArrayOf(554, 80, 8000, 8080, 34567)

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
     */
    suspend fun scan(
        context: Context,
        onProgress: (Int) -> Unit = {}
    ): List<ScannedDevice> = withContext(Dispatchers.IO) {
        val found = linkedSetOf<ScannedDevice>()

        // 1) ONVIF discovery
        runCatching { OnvifClient.discover() }
            .onSuccess { found.addAll(it) }
            .onFailure { Log.w(TAG, "onvif discovery failed: ${it.message}") }

        // 2) Port-scan the /24 subnet
        val base = getLocalSubnet(context) ?: return@withContext found.toList()
        val hosts = (1..254).map { "$base.$it" }
        val total = hosts.size
        var done = 0

        coroutineScope {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    val device = probeHost(host)
                    if (device != null) synchronized(found) { found.add(device) }
                    synchronized(this) {
                        done++
                        onProgress((done * 50 / total) + 50)
                    }
                }
            }.awaitAll()
        }
        found.toList()
    }

    private fun probeHost(host: String): ScannedDevice? {
        for (port in CAMERA_PORTS) {
            if (isPortOpen(host, port, 150)) {
                val onvif = port == 80 || port == 8000 || port == 8080
                val rtsp = port == 554
                if (onvif || rtsp) {
                    return ScannedDevice(
                        host = host,
                        port = if (rtsp) 554 else 80,
                        manufacturer = if (onvif) "ONVIF" else "RTSP",
                        model = "IP Camera",
                        onvif = onvif,
                        rtspSupported = true
                    )
                }
            }
        }
        return null
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

    /** Ping an arbitrary host:port quickly (used when manually adding a device). */
    fun testReachable(host: String, port: Int, timeoutMs: Int = 1000): Boolean =
        try {
            InetAddress.getByName(host).isReachable(timeoutMs) || isPortOpen(host, port, timeoutMs)
        } catch (_: Exception) {
            false
        }
}
