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
        soapRequest(device, "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove", body) != null
    }

    /**
     * Trigger a remote sound/light deterrence via the event service (best-effort).
     */
    suspend fun triggerDeterrence(device: Device): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0) return@withContext false
        val body = """<tev:TriggerAlarm>
                       <tev:Alarm>1</tev:Alarm>
                     </tev:TriggerAlarm>"""
        soapRequest(device, "http://www.onvif.org/ver10/events/wsdl/TriggerAlarm", body) != null
    }

    /** Go to a saved PTZ preset by index. */
    suspend fun gotoPreset(device: Device, index: Int): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        val body = """<tptz:GotoPreset>
                       <tptz:ProfileToken>Profile_1</tptz:ProfileToken>
                       <tptz:PresetToken>$index</tptz:PresetToken>
                     </tptz:GotoPreset>"""
        soapRequest(device, "http://www.onvif.org/ver20/ptz/wsdl/GotoPreset", body) != null
    }

    /** Save / overwrite a PTZ preset at the current position. */
    suspend fun setPreset(device: Device, index: Int, name: String): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        val body = """<tptz:SetPreset>
                       <tptz:ProfileToken>Profile_1</tptz:ProfileToken>
                       <tptz:PresetName>$name</tptz:PresetName>
                       <tptz:PresetToken>$index</tptz:PresetToken>
                     </tptz:SetPreset>"""
        soapRequest(device, "http://www.onvif.org/ver20/ptz/wsdl/SetPreset", body) != null
    }

    /** Remove a saved PTZ preset. */
    suspend fun removePreset(device: Device, index: Int): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        val body = """<tptz:RemovePreset>
                       <tptz:ProfileToken>Profile_1</tptz:ProfileToken>
                       <tptz:PresetToken>$index</tptz:PresetToken>
                     </tptz:RemovePreset>"""
        soapRequest(device, "http://www.onvif.org/ver20/ptz/wsdl/RemovePreset", body) != null
    }

    /** List all saved presets. */
    suspend fun listPresets(device: Device): List<com.cameramanager.app.vendor.Preset> = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext emptyList()
        val body = """<tptz:GetPresets><tptz:ProfileToken>Profile_1</tptz:ProfileToken></tptz:GetPresets>"""
        val resp = soapRequest(device, "http://www.onvif.org/ver20/ptz/wsdl/GetPresets", body) ?: return@withContext emptyList()
        // Parse <tt:Preset token="N"><tt:Name>...</tt:Name>...</tt:Preset>
        Regex("<tt:Preset\\s+token=\"(\\d+)\"[^>]*>(?:.*?<tt:Name>(.*?)</tt:Name>)?", RegexOption.DOT_MATCHES_ALL)
            .findAll(resp).map { m ->
                com.cameramanager.app.vendor.Preset(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "预置位 ${m.groupValues[1]}" })
            }.toList()
    }

    /** Toggle automatic patrol tour (cruise). */
    suspend fun setAutoTour(device: Device, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        val op = if (enabled) "Start" else "Stop"
        val body = """<tptz:${op}Tour><tptz:ProfileToken>Profile_1</tptz:ProfileToken><tptz:TourToken>Tour_1</tptz:TourToken></tptz:${op}Tour>"""
        soapRequest(device, "http://www.onvif.org/ver20/ptz/wsdl/${op}Tour", body) != null
    }

    /** Reboot the device via ONVIF device service. */
    suspend fun reboot(device: Device): Boolean = withContext(Dispatchers.IO) {
        if (device.onvifPort == 0 || device.username.isNullOrEmpty()) return@withContext false
        soapRequest(device, "http://www.onvif.org/ver10/device/wsdl/SystemReboot", "<tds:SystemReboot/>") != null
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
        soapRequestToPath(device, "/onvif/imaging",
            "http://www.onvif.org/ver20/imaging/wsdl/SetImagingSettings", body) != null
    }


    /**
     * Issue a raw SOAP request to the device ONVIF service. Returns the response body
     * or null on failure.
     */
    private fun soapRequest(
        device: Device,
        action: String,
        bodyXml: String
    ): String? = soapRequestToPath(device, "/onvif/device_service", action, bodyXml)

    /** SOAP request to a specific ONVIF service path (e.g., /onvif/imaging, /onvif/ptz). */
    private fun soapRequestToPath(
        device: Device,
        servicePath: String,
        action: String,
        bodyXml: String
    ): String? {
        val url = "http://${device.host}:${device.onvifPort}$servicePath"
        return try {
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
    }
}
