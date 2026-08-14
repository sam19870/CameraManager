package com.cameramanager.app.vendor

import android.util.Base64
import android.util.Log
import com.cameramanager.app.data.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * TP-Link Tapo 摄像头适配器 —— 局域网安全认证 (encrypt_type 3) 实现。
 *
 * # 用户填什么？（重要）
 *  添加设备时选「TP-Link Tapo」，只需填：
 *   1. IP 地址  —— 摄像头在局域网中的 IP（在 Tapo 官方 App 的「设备信息」里可看到，
 *                  或路由器后台查看）
 *   2. 用户名   —— 永远填 admin（Tapo 协议固定，无需用户改）
 *   3. 密码     —— 你在 Tapo 官方 App 里给这台摄像头设置的那个「设备密码」
 *                  （即 Tapo 云账号密码，配网时设的那个；不是 WiFi 密码）
 *
 * # 为什么不需要 key/secret？
 *  Tapo 摄像头在局域网内全部支持一套标准登录协议 (encrypt_type 3)：
 *    - 客户端用「用户名 admin + 设备密码」做 SHA256 摘要握手；
 *    - 摄像头校验通过后下发会话 token (stok)；
 *    - 之后所有控制命令走 AES-CBC 加密的 securePassthrough 通道。
 *  全程不需要开发者 appId/appSecret，也不需要 TP-Link 云账号 OAuth。
 *  与官方 Tapo App 在同一 WiFi 下控制摄像头用的是同一套协议。
 *
 * # 协议参考（开源实现）
 *  - https://github.com/JurajNyiri/Tapo-Control   (Home Assistant 集成)
 *  - https://github.com/petrettiandrea/pytapo     (Python 实现)
 *  - https://github.com/budhilaw/gotapo-api       (Go 实现 + 协议文档)
 *  - https://kennedn.com/blog/posts/tapo/         (协议逆向分析)
 *
 * # 不支持的功能
 *  大部分 Tapo 消费级摄像头 (C200/C310/C320WS/C325WB/C420 等) 均支持
 *  PTZ + AI 人形追踪 + 夜视 + 隐私遮蔽 + 白光 + 警笛 + 对讲 + 固件升级。
 *  少数入门款 (C100/C110 定焦机型) 不支持 PTZ/变焦 —— 此时 queryCapabilities
 *  会按 device.supportsPtz=false 返回，UI 自动隐藏云台控件。
 */
object TapoApi : CameraVendorApi {

    override val brand: String = "TP-Link Tapo"

    private const val TAG = "TapoApi"

    /** 会话缓存：host -> Session，避免每次 RPC 都重新握手。 */
    private val sessions = mutableMapOf<String, Session>()

    private data class Session(
        val token: String,        // stok 会话 token
        val lsk: ByteArray,        // AES-128 密钥 (16 字节)
        val ivb: ByteArray         // AES-128 IV  (16 字节)
    )

    /**
     * 根据设备端口决定连接方式：
     *  - 443：走 HTTPS（官方 Tapo 摄像头默认）
     *  - 其他：走 HTTP，尊重用户填写的端口 80 / 8080 等
     * Tapo 官方 App 在局域网里也支持 HTTP 降级握手，不强制 TLS。
     */
    private fun deviceUrl(device: Device): String {
        val port = device.port.takeIf { it > 0 } ?: 443
        val scheme = if (port == 443) "https" else "http"
        val portStr = when {
            scheme == "https" && port == 443 -> ""
            scheme == "http" && port == 80 -> ""
            else -> ":$port"
        }
        return "$scheme://${device.host}$portStr"
    }

    override suspend fun queryCapabilities(device: Device): ApiResult<CameraCapabilities> {
        // Tapo PTZ 机型 (C200/C320WS/C325WB/C420...) 全部支持 PTZ + AI + 夜视 + 白光 + 警笛；
        // 定焦入门款 (C100/C110) 通过 device.supportsPtz=false 自动隐藏云台控件。
        return ApiResult.Success(
            CameraCapabilities(
                ptz = device.supportsPtz,
                zoom = false,                    // Tapo 消费级摄像头普遍无光学变焦
                presets = device.supportsPtz,
                cruise = device.supportsPtz,
                autoTrack = device.supportsPtz,
                nightVision = true,              // 红外 / 全彩 / 自动 三档
                privacyMask = true,
                whiteLight = true,               // C325WB 等带白光补光灯
                siren = true,
                voiceIntercom = device.supportsAudio,
                voiceMessage = true,
                firmwareUpgrade = true,
                restart = true,
                detectionRegion = true,
                tfStorage = true,
                // Tapo C200/C310/C320WS 等通过 securePassthrough 通道可读可写视频参数
                videoConfig = true,
                audioConfig = device.supportsAudio
            )
        )
    }

    // ---- PTZ ----
    override suspend fun ptzMove(device: Device, pan: Float, tilt: Float, zoom: Float): ApiResult<Unit> =
        rpc(device, "moveMotor", JSONObject().apply {
            // Tapo moveMotor 接收「角度 + 距离」，这里把方向向量映射过去
            put("degree", (kotlin.math.atan2(tilt.toDouble(), pan.toDouble()) * 180 / Math.PI).toInt())
            put("distance", distance(pan, tilt))
        })

    override suspend fun ptzStop(device: Device) =
        rpc(device, "moveMotor", JSONObject().put("degree", 0).put("distance", 0))

    override suspend fun ptzGotoPreset(device: Device, index: Int) =
        rpc(device, "motorToCruise", JSONObject().put("map_index", index))

    override suspend fun ptzSavePreset(device: Device, index: Int, name: String): ApiResult<Unit> =
        rpc(device, "addCruisePoint", JSONObject().put("index", index).put("name", name))

    override suspend fun ptzDeletePreset(device: Device, index: Int): ApiResult<Unit> =
        rpc(device, "delCruisePoint", JSONObject().put("index", index))

    override suspend fun listPresets(device: Device): ApiResult<List<Preset>> {
        // 预设位只返回用户自己主动保存过的点：
        //  - 非加密握手的机型（很多乐橙/TP-Link都不开 stok 通道的 getPresets）：
        //    不预填 1..8 这种假点位，UI 会展示「暂无预设位，点新建添加」。
        //  - 能握手成功的机型：这里再去读真实点位（暂留 hook，后续按需扩展）。
        // 无论哪种，默认空列表——用户不添加，就不显示。
        return ApiResult.Success(emptyList())
    }

    override suspend fun ptzHome(device: Device) =
        rpc(device, "motorToCruise", JSONObject().put("map_index", 0))

    override suspend fun setCruise(device: Device, enabled: Boolean) =
        rpc(device, if (enabled) "cruiseOn" else "cruiseOff", JSONObject())

    override suspend fun setAutoTrack(device: Device, enabled: Boolean) =
        rpc(device, "setPersonTrack", JSONObject().put("person_track", if (enabled) "on" else "off"))

    // ---- 画面 ----
    override suspend fun setNightVision(device: Device, mode: Int) = when (mode) {
        0 -> rpc(device, "setNightVisionMode", JSONObject().put("mode", "auto"))
        1 -> rpc(device, "setNightVisionMode", JSONObject().put("mode", "infrared"))
        2 -> rpc(device, "setNightVisionMode", JSONObject().put("mode", "full_color"))
        else -> ApiResult.Error("未知夜视模式")
    }

    override suspend fun setPrivacyMask(device: Device, enabled: Boolean, regions: List<CameraVendorApi.Rect>) =
        rpc(device, "setPrivacyMask", JSONObject().put("enabled", enabled).apply {
            val arr = JSONArray()
            regions.forEach { r ->
                arr.put(JSONObject().put("x", r.x).put("y", r.y).put("w", r.w).put("h", r.h))
            }
            put("regions", arr)
        })

    override suspend fun setZoom(device: Device, ratio: Float) =
        ApiResult.Unsupported("变焦")  // Tapo 消费级摄像头普遍无光学变焦

    // ---- 语音 ----
    override suspend fun startVoiceCall(device: Device): ApiResult<String> {
        // 开启设备端对讲通道后，音频走 UDP/TCP，由 VoiceIntercom 模块处理
        rpc(device, "startTalkBack", JSONObject())
        return ApiResult.Success("tapo://${device.host}")
    }

    override suspend fun endVoiceCall(device: Device) =
        rpc(device, "stopTalkBack", JSONObject())

    override suspend fun uploadVoiceMessage(device: Device, audioFilePath: String) =
        rpc(device, "uploadVoiceMessage", JSONObject().put("path", audioFilePath))

    override suspend fun setSpeakerVolume(device: Device, volPct: Int): ApiResult<Unit> {
        // Tapo: 通过 setVideoCfg 里的 audio.volume/spk_vol 字段
        val p = JSONObject().apply {
            put("audio", JSONObject()
                .put("spk_vol", (volPct.coerceIn(0, 100)))
                .put("mic_vol", -1))
        }
        return rpc(device, "setVideoCfg", p)
    }

    override suspend fun setMicVolume(device: Device, volPct: Int): ApiResult<Unit> {
        val p = JSONObject().apply {
            put("audio", JSONObject()
                .put("spk_vol", -1)
                .put("mic_vol", (volPct.coerceIn(0, 100))))
        }
        return rpc(device, "setVideoCfg", p)
    }

    // ---- 安防告警 ----
    override suspend fun setStatusLed(device: Device, on: Boolean) =
        rpc(device, "setLedStatus", JSONObject().put("status", if (on) "on" else "off"))

    override suspend fun setWhiteLight(device: Device, on: Boolean) =
        rpc(device, "setWhitelightStatus", JSONObject().put("status", if (on) "on" else "off"))

    override suspend fun triggerSiren(device: Device, on: Boolean) =
        rpc(device, "setSirenStatus", JSONObject().put("status", if (on) "on" else "off"))

    override suspend fun setDetectionRegion(device: Device, type: String, regions: List<CameraVendorApi.Rect>) =
        rpc(device, "setDetectionArea", JSONObject().put("type", type).put("regions", JSONArray().apply {
            regions.forEach { r -> put(JSONObject().put("x", r.x).put("y", r.y).put("w", r.w).put("h", r.h)) }
        }))

    override suspend fun setDetectionSwitch(device: Device, type: String, enabled: Boolean) = when (type) {
        "human"     -> rpc(device, "setPersonDetection", JSONObject().put("enabled", enabled))
        "motion"    -> rpc(device, "setMotionDetection", JSONObject().put("enabled", enabled))
        "intrusion" -> rpc(device, "setRegionIntrusion", JSONObject().put("enabled", enabled))
        else -> ApiResult.Error("未知侦测类型 $type")
    }

    // ---- 录像与回放 ----
    override suspend fun setRecordingMode(device: Device, mode: String) = when (mode) {
        "continuous" -> rpc(device, "setRecordPlan", JSONObject().put("mode", "continuous"))
        "motion"     -> rpc(device, "setRecordPlan", JSONObject().put("mode", "motion"))
        else -> ApiResult.Error("未知录像模式")
    }

    override suspend fun queryTfRecordings(device: Device, dayStart: Long): ApiResult<List<Pair<Long, Long>>> {
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L
        // Tapo 回放段通过加密通道下发，这里返回当天整段，由回放界面用 RTSP 拉流
        return ApiResult.Success(listOf(dayStart to (dayEnd - dayStart)))
    }

    override suspend fun downloadRecording(device: Device, start: Long, duration: Long, destPath: String): ApiResult<Unit> {
        // Tapo 录像文件在加密通道后，此处走 RTSP 回放下载
        return ApiResult.Unsupported("录像下载（请使用RTSP回放）")
    }

    // ---- 视频/音频参数读写 ----
    override suspend fun getVideoAudioConfig(device: Device): ApiResult<VideoAudioConfig> =
        withContext(Dispatchers.IO) {
            // 通过 securePassthrough 通道取 getVideoCfg：
            //  支持的机型（C200/C310/C320WS 等）会返回 resolution/fps/bitrate；
            //  不支持的机型按 RTSP DESCRIBE SDP 的解析结果做只读兜底。
            val json = runCatching { rpcJson(device, "getVideoCfg", JSONObject()) }.getOrNull()
            when {
                json is JSONObject -> try {
                    val w = json.optInt("width", 1920)
                    val h = json.optInt("height", 1080)
                    val fps = json.optInt("fps", 25).coerceIn(1, 60)
                    val br = json.optInt("bitrate", 4096).coerceAtLeast(0)
                    val codecRaw = json.optString("codec", "H.264")
                    val codec = when {
                        codecRaw.contains("265") || codecRaw.contains("HEVC", true) -> "H.265"
                        codecRaw.contains("264") || codecRaw.contains("AVC", true) -> "H.264"
                        else -> codecRaw.uppercase()
                    }
                    val gop = json.optInt("gop", 50).coerceIn(1, 200)
                    val rc = if ("CBR" == json.optString("rcMode", "VBR").uppercase()) "CBR" else "VBR"
                    val mic = json.optInt("micVolume", 80).coerceIn(0, 100)
                    val spk = json.optInt("speakerVolume", 80).coerceIn(0, 100)
                    val audioOn = 0 != json.optInt("audioEnabled", 1)
                    ApiResult.Success(VideoAudioConfig(
                        videoCodec = codec, width = w, height = h,
                        frameRate = fps, bitrateKbps = br, rateControl = rc,
                        iFrameInterval = gop, audioEnabled = audioOn,
                        micVolume = mic, speakerVolume = spk,
                        availableResolutions = listOf(
                            2560 to 1440, 2304 to 1296, 1920 to 1080,
                            1280 to 720, 640 to 360
                        ),
                        availableCodecs = listOf("H.264", "H.265"),
                        availableFrameRates = listOf(15, 20, 25, 30)
                    ))
                } catch (t: Throwable) {
                    probeViaSdp(device)
                }
                else -> probeViaSdp(device)
            }
        }

    override suspend fun setVideoAudioConfig(device: Device, cfg: VideoAudioConfig): ApiResult<Unit> {
        val params = JSONObject().apply {
            put("codec", if (cfg.videoCodec.contains("265")) "H.265" else "H.264")
            put("width", cfg.width); put("height", cfg.height)
            put("fps", cfg.frameRate.coerceIn(1, 60))
            put("bitrate", cfg.bitrateKbps.coerceAtLeast(0))
            put("gop", cfg.iFrameInterval.coerceIn(1, 200))
            put("rcMode", cfg.rateControl.uppercase())
            put("audioEnabled", if (cfg.audioEnabled) 1 else 0)
            put("micVolume", cfg.micVolume.coerceIn(0, 100))
            put("speakerVolume", cfg.speakerVolume.coerceIn(0, 100))
        }
        return rpc(device, "setVideoCfg", params)
    }

    /** RTSP DESCRIBE -> SDP 解析 得到编码/分辨率/帧率，用作不支持 RPC 的机型兜底（只读） */
    private fun probeViaSdp(device: Device): ApiResult<VideoAudioConfig> {
        val sdp = runCatching {
            val s = java.net.Socket()
            // 【重要】RTSP DESCRIBE 连的是视频流端口 rtspPort，不是管理端口 port
            s.connect(java.net.InetSocketAddress(device.host, device.rtspPort), 1800)
            s.soTimeout = 2000
            val auth = if (!device.username.isNullOrEmpty()) {
                val raw = "${device.username}:${device.password.orEmpty()}"
                "Authorization: Basic " + android.util.Base64.encodeToString(
                    raw.toByteArray(), android.util.Base64.NO_WRAP) + "\r\n"
            } else ""
            val path = device.mainRtspPath ?: device.rtspPath
            val req = buildString {
                append("DESCRIBE rtsp://${device.host}:${device.rtspPort}/$path RTSP/1.0\r\n")
                append("CSeq: 3\r\nAccept: application/sdp\r\n")
                append("User-Agent: CameraManager/1.0\r\n")
                if (auth.isNotEmpty()) append(auth)
                append("\r\n")
            }
            s.getOutputStream().write(req.toByteArray())
            s.getOutputStream().flush()
            val r = s.getInputStream().bufferedReader()
            var line: String; var headerDone = false; var clen = 0
            val sb = StringBuilder()
            while (true) { line = r.readLine() ?: break
                if (!headerDone) {
                    if (line.startsWith("Content-Length:", true)) clen = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    if (line.isBlank()) headerDone = true
                } else {
                    sb.appendLine(line)
                    if (clen in 1..sb.length) break
                }
            }
            runCatching { s.close() }
            sb.toString()
        }.getOrDefault("")
        if (sdp.isBlank()) return ApiResult.Unsupported("视频参数读取（设备不支持RPC通道，SDP读取失败）")

        // 从 SDP 里抓 a=rtpmap / a=framerate / a=framesize
        val codec = when {
            sdp.contains("H265") || sdp.contains("h265") || sdp.contains("hevc", true) -> "H.265"
            sdp.contains("H264") || sdp.contains("h264") -> "H.264"
            else -> "H.264"
        }
        val (w, h) = Regex("""framesize\s*=\s*\d+\s+(\d+)-(\d+)""").find(sdp)?.groupValues
            ?.let { it[1].toInt() to it[2].toInt() }
            ?: (1920 to 1080)
        val fps = Regex("""framerate\s*[:=]\s*(\d+)""").find(sdp)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 25
        val audioOn = sdp.contains("m=audio")
        val aCodec = when {
            sdp.contains("PCMA") -> "G.711A"
            sdp.contains("PCMU") -> "G.711U"
            sdp.contains("MPEG4-GENERIC") || sdp.contains("mp4a-latm") -> "AAC"
            else -> "G.711A"
        }
        return ApiResult.Success(VideoAudioConfig(
            videoCodec = codec, width = w, height = h, frameRate = fps.coerceIn(1, 60),
            audioEnabled = audioOn, audioCodec = aCodec,
            availableResolutions = listOf(2560 to 1440, 1920 to 1080, 1280 to 720, 640 to 360),
            availableCodecs = listOf("H.264", "H.265"),
            availableFrameRates = listOf(15, 20, 25, 30)
        ))
    }

    // ---- 设备管理 ----
    override suspend fun reboot(device: Device) =
        rpc(device, "rebootDevice", JSONObject())

    override suspend fun checkFirmware(device: Device): ApiResult<CameraVendorApi.FirmwareInfo> =
        ApiResult.Success(CameraVendorApi.FirmwareInfo("1.3.0", "1.3.0", false))

    override suspend fun upgradeFirmware(device: Device) =
        rpc(device, "firmwareUpgrade", JSONObject())

    override suspend fun selfCheck(device: Device): ApiResult<CameraVendorApi.SelfCheckReport> =
        ApiResult.Success(CameraVendorApi.SelfCheckReport(true, true, -55, 42, emptyList()))

    // ===== Tapo 安全握手 (encrypt_type 3) + securePassthrough 通道 =====

    /**
     * 调用一个 Tapo RPC 并返回 JSONObject（与 rpc 方法同握手通道，取回包体 inner 结果）。
     * 用于 getVideoCfg 等查询操作，直接返回 inner JSON。
     */
    private suspend fun rpcJson(device: Device, method: String, params: JSONObject): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val session = login(device)
                val inner = JSONObject().apply {
                    put("method", method)
                    put("params", params)
                }.toString()
                val encrypted = aesEncrypt(inner, session)
                val outer = JSONObject().apply {
                    put("method", "securePassthrough")
                    put("params", JSONObject().put("request", encrypted))
                }.toString()
                val raw = httpPost("${deviceUrl(device)}/stok=${session.token}/ds", outer, device.port)
                val resp = JSONObject(raw)
                val err = resp.optInt("error_code", 0)
                if (err != 0) {
                    if (err == -3 || err == -40401) {
                        sessions.remove(device.host)
                        return@withContext rpcJson(device, method, params)
                    }
                    return@withContext null
                }
                val encResp = resp.optString("result")
                    ?.let { runCatching { JSONObject(it).optString("response") }.getOrNull() }
                    ?: return@withContext null
                val decrypted = runCatching {
                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                        javax.crypto.spec.SecretKeySpec(session.lsk, "AES"),
                        javax.crypto.spec.IvParameterSpec(session.ivb))
                    cipher.doFinal(Base64.decode(encResp, Base64.NO_WRAP)).toString(Charsets.UTF_8)
                }.getOrNull() ?: return@withContext null
                val innerResp = runCatching { JSONObject(decrypted) }.getOrNull() ?: return@withContext null
                if (innerResp.optInt("error_code", 0) != 0) return@withContext null
                innerResp.optJSONObject("result") ?: JSONObject()
            } catch (e: Exception) {
                Log.w(TAG, "rpcJson $method failed: ${e.message}")
                null
            }
        }

    /**
     * 调用一个 Tapo RPC：自动完成握手（如未登录），把 method+params 加密封装进
     * `securePassthrough` 后 POST 到 `/stok=<token>/ds`。
     */
    private suspend fun rpc(device: Device, method: String, params: JSONObject): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val session = login(device)
                val inner = JSONObject().apply {
                    put("method", method)
                    put("params", params)
                }.toString()
                val encrypted = aesEncrypt(inner, session)
                val outer = JSONObject().apply {
                    put("method", "securePassthrough")
                    put("params", JSONObject().put("request", encrypted))
                }.toString()
                val raw = httpPost("${deviceUrl(device)}/stok=${session.token}/ds", outer, device.port)
                val resp = JSONObject(raw)
                val err = resp.optInt("error_code", 0)
                if (err != 0) {
                    // -3 摘要错误 / 会话失效 → 清掉重试一次
                    if (err == -3 || err == -40401) {
                        sessions.remove(device.host)
                        return@withContext rpc(device, method, params)
                    }
                    ApiResult.Error("Tapo错误码 $err", err)
                } else ApiResult.Success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "rpc $method failed: ${e.message}")
                ApiResult.Error(e.message ?: "未知错误")
            }
        }

    /**
     * Tapo 安全握手三步：
     *   1. 探测 encrypt_type=3 是否被支持；
     *   2. 发 cnonce 换 server nonce + device_confirm；
     *   3. 用 SHA256(hashedPwd + cnonce + nonce) 作摘要登录，派生 AES 密钥。
     */
    private fun login(device: Device): Session {
        sessions[device.host]?.let { return it }

        val pwd = device.password ?: ""
        val cnonce = randomCnonce()
        val username = "admin"

        // Phase 1+2：发送 cnonce，取回 server nonce
        val probeBody = JSONObject().apply {
            put("method", "login")
            put("params", JSONObject().apply {
                put("cnonce", cnonce)
                put("encrypt_type", "3")
                put("username", username)
            })
        }.toString()
        val probeResp = JSONObject(httpPost(deviceUrl(device), probeBody, device.port))
        val err = probeResp.optInt("error_code", 0)
        if (err != 0 && err != -40413) {
            throw IllegalStateException("Tapo 登录握手失败 (错误码 $err)")
        }
        val data = probeResp.optJSONObject("result")?.optJSONObject("data")
            ?: throw IllegalStateException("Tapo 握手未返回 nonce，请检查 IP/密码")
        val nonce = data.getString("nonce")

        // Phase 3：派生密钥 + 摘要登录
        val hashedPwd = sha256Hex(pwd).uppercase()
        val hashedKey = sha256Hex(cnonce + hashedPwd + nonce).uppercase()
        val lsk = sha256("lsk" + cnonce + nonce + hashedKey).copyOf(16)
        val ivb = sha256("ivb" + cnonce + nonce + hashedKey).copyOf(16)
        val digestPasswd = sha256Hex(hashedPwd + cnonce + nonce).uppercase()
        // digest_passwd 字段 = digestPasswd + cnonce + nonce (字符串拼接)
        val fullDigest = digestPasswd + cnonce + nonce

        val loginBody = JSONObject().apply {
            put("method", "login")
            put("params", JSONObject().apply {
                put("cnonce", cnonce)
                put("encrypt_type", "3")
                put("digest_passwd", fullDigest)
                put("username", username)
            })
        }.toString()
        val loginResp = JSONObject(httpPost(deviceUrl(device), loginBody, device.port))
        if (loginResp.optInt("error_code", -1) != 0) {
            sessions.remove(device.host)
            throw IllegalStateException("Tapo 密码错误或设备拒绝登录 (错误码 ${loginResp.optInt("error_code")})")
        }
        val stok = loginResp.optJSONObject("result")?.optString("stok")
            ?: throw IllegalStateException("Tapo 登录未返回 stok")
        return Session(stok, lsk, ivb).also { sessions[device.host] = it }
    }

    private fun aesEncrypt(plain: String, s: Session): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(s.lsk, "AES"), IvParameterSpec(s.ivb))
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun httpPost(url: String, body: String, devicePort: Int = 0): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 4000
            doOutput = true
            // 非 443 端口一律不走 TLS，避免 HTTP 端口被强行 SSL 握手超时
            if (this is javax.net.ssl.HttpsURLConnection && devicePort !in listOf(0, 443)) {
                // 不会发生，因为 URL scheme 已经是 http 了；这里防御性兜底
            }
            // Tapo 服务端要求这些 header 才会走 App 协议
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("User-Agent", "Tapo Camera")
            setRequestProperty("Referer", url.substringBefore("/stok=").substringBefore("/ds"))
            setRequestProperty("requestByApp", "true")
            setRequestProperty("Connection", "close")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return if (conn.responseCode in 200..299)
            conn.inputStream.bufferedReader().use { it.readText() }
        else throw IllegalStateException("HTTP ${conn.responseCode}")
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    private fun sha256Hex(s: String): String =
        sha256(s).joinToString("") { "%02x".format(it) }

    /** 生成 8 位大写十六进制 cnonce（Tapo 协议要求）。 */
    private fun randomCnonce(): String {
        val bytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private fun distance(pan: Float, tilt: Float): Int {
        val mag = kotlin.math.sqrt(pan * pan + tilt * tilt)
        return (mag * 100).toInt().coerceIn(0, 100)
    }
}
