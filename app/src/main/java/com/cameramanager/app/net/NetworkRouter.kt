package com.cameramanager.app.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.cameramanager.app.CameraApp
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.data.model.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 自动选路器：根据「当前 WiFi SSID == 设备绑定的内网 SSID」判断走内网，
 * 否则按「设备绑定的 Tunnel → 设备自带公网地址 → 原始 host」顺序选路。
 *
 * 决策结果用 [RouteResult] 返回，包含 RTSP/ONVIF 实际要连的 host:port 以及
 * 路由类型（LAN / PUBLIC / TUNNEL），UI 可据此显示连接状态徽标。
 *
 * SSID 读取：Android 12+ 需要精确定位权限；权限不足时按「不在内网」处理，
 * 走公网/穿透，避免在内网环境下误判。
 */
object NetworkRouter {

    private const val TAG = "NetworkRouter"
    private const val PROBE_TIMEOUT_MS = 600

    enum class RouteType { LAN, PUBLIC, TUNNEL }

    data class RouteResult(
        val host: String,
        val rtspPort: Int,
        val onvifPort: Int,
        val type: RouteType,
        /** 人类可读的来源标签，用于 UI 显示。 */
        val label: String,
        /** 选路时命中的 Tunnel，若 type != TUNNEL 则为 null。 */
        val tunnel: Tunnel? = null,
        /** 内网 host 是否可快速连通（仅 LAN 时探测）。 */
        val reachable: Boolean = true
    )

    /** 当前 WiFi SSID（已去掉首尾引号），无 WiFi / 权限不足时返回 null。 */
    @SuppressLint("HardwareIds")
    fun currentSsid(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "ACCESS_FINE_LOCATION 未授予，无法读取 SSID")
            return null
        }
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val active = cm.activeNetwork ?: return null
                val caps = cm.getNetworkCapabilities(active) ?: return null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            }
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val raw = wifi.connectionInfo.ssid ?: return null
            // 系统返回的 SSID 形如 "\"MyWiFi\""，去掉首尾引号
            raw.trim().removePrefix("\"").removeSuffix("\"").ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "currentSsid failed: ${e.message}")
            null
        }
    }

    /** 是否当前正连着 WiFi（不论 SSID）。 */
    fun isOnWifi(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    } catch (_: Exception) { false }

    /**
     * 为设备选路。挂起函数：LAN 时会做一次 TCP 可达性探测（端口通即视为内网可达）。
     */
    suspend fun resolve(context: Context, device: Device): RouteResult = withContext(Dispatchers.IO) {
        val currentSsid = currentSsid(context)
        val lanSsid = device.lanSsid?.trim()?.removePrefix("\"")?.removeSuffix("\"")

        // 1) 当前 WiFi 与设备绑定内网 SSID 相同 → 走内网
        val sameSsid = !lanSsid.isNullOrEmpty() &&
            !currentSsid.isNullOrEmpty() &&
            currentSsid.equals(lanSsid, ignoreCase = true)
        if (sameSsid) {
            val reach = isPortOpen(device.host, device.port, PROBE_TIMEOUT_MS)
            return@withContext RouteResult(
                host = device.host,
                rtspPort = device.port,
                onvifPort = device.onvifPort,
                type = RouteType.LAN,
                label = "内网·${currentSsid}",
                reachable = reach
            )
        }

        // 2) 设备绑定了 Tunnel → 走穿透
        if (device.tunnelId > 0) {
            val tunnel = CameraApp.get().repository.getTunnel(device.tunnelId)
            if (tunnel != null && tunnel.enabled) {
                return@withContext RouteResult(
                    host = tunnel.host,
                    rtspPort = tunnel.port,
                    onvifPort = tunnel.onvifPort,
                    type = RouteType.TUNNEL,
                    label = "穿透·${tunnel.name}",
                    tunnel = tunnel
                )
            }
        }

        // 3) 设备自带公网地址 → 走公网
        if (!device.publicHost.isNullOrEmpty() && device.publicPort > 0) {
            return@withContext RouteResult(
                host = device.publicHost,
                rtspPort = device.publicPort,
                onvifPort = device.publicOnvifPort,
                type = RouteType.PUBLIC,
                label = "公网·${device.publicHost}"
            )
        }

        // 4) 兜底：当前在 WiFi 且设备 host 是私网地址 → 试内网；否则用原始 host 直连
        val isPrivateLan = device.host.startsWith("192.168.") ||
            device.host.startsWith("10.") ||
            device.host.startsWith("172.")
        val fallbackLan = isOnWifi(context) && isPrivateLan
        RouteResult(
            host = device.host,
            rtspPort = device.port,
            onvifPort = device.onvifPort,
            type = if (fallbackLan) RouteType.LAN else RouteType.PUBLIC,
            label = if (fallbackLan) "内网·直连" else "直连·${device.host}",
            reachable = true
        )
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.isConnected
        }
    } catch (_: Exception) { false }
}
