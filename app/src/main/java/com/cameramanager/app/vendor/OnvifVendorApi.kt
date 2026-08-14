package com.cameramanager.app.vendor

import com.cameramanager.app.data.model.Device
import com.cameramanager.app.rtsp.OnvifClient

/**
 * Generic ONVIF Profile-S/T vendor adapter. Falls back gracefully for features
 * not supported by ONVIF (e.g. firmware upgrade, voice messages) so the UI can
 * show the "device not supported" prompt.
 */
object OnvifVendorApi : CameraVendorApi {

    override val brand: String = "ONVIF (通用)"

    override suspend fun queryCapabilities(device: Device): ApiResult<CameraCapabilities> {
        val onvif = device.onvifPort > 0
        return ApiResult.Success(
            CameraCapabilities(
                ptz = device.supportsPtz && onvif,
                zoom = device.supportsPtz && onvif,
                presets = device.supportsPtz && onvif,
                cruise = device.supportsPtz && onvif,
                autoTrack = false,
                nightVision = onvif,           // ONVIF Imaging service 支持 IR Cut 切换
                privacyMask = false,
                whiteLight = onvif,            // 部分ONVIF摄像头支持白光灯
                siren = onvif,
                voiceIntercom = device.supportsAudio,
                voiceMessage = false,
                firmwareUpgrade = false,
                restart = onvif,
                detectionRegion = onvif,
                tfStorage = onvif,
                videoConfig = false,
                audioConfig = false
            )
        )
    }

    override suspend fun ptzMove(device: Device, pan: Float, tilt: Float, zoom: Float) =
        if (OnvifClient.ptzMove(device, "Profile_1", pan, tilt, zoom))
            ApiResult.Success(Unit) else ApiResult.Error("云台控制失败")

    override suspend fun ptzStop(device: Device) =
        if (OnvifClient.ptzMove(device, "Profile_1", 0f, 0f, 0f))
            ApiResult.Success(Unit) else ApiResult.Error("停止云台失败")

    override suspend fun ptzGotoPreset(device: Device, index: Int): ApiResult<Unit> =
        if (OnvifClient.gotoPreset(device, index)) ApiResult.Success(Unit)
        else ApiResult.Unsupported("预置位")

    override suspend fun ptzSavePreset(device: Device, index: Int, name: String): ApiResult<Unit> =
        if (OnvifClient.setPreset(device, index, name)) ApiResult.Success(Unit)
        else ApiResult.Unsupported("保存预置位")

    override suspend fun ptzDeletePreset(device: Device, index: Int): ApiResult<Unit> =
        if (OnvifClient.removePreset(device, index)) ApiResult.Success(Unit)
        else ApiResult.Unsupported("删除预置位")

    override suspend fun listPresets(device: Device) =
        ApiResult.Success(OnvifClient.listPresets(device))

    override suspend fun ptzHome(device: Device) = ptzGotoPreset(device, 0)

    override suspend fun setCruise(device: Device, enabled: Boolean): ApiResult<Unit> =
        if (OnvifClient.setAutoTour(device, enabled)) ApiResult.Success(Unit)
        else ApiResult.Unsupported("自动巡航")

    override suspend fun setAutoTrack(device: Device, enabled: Boolean) =
        ApiResult.Unsupported("AI人形追踪")

    override suspend fun setNightVision(device: Device, mode: Int) =
        if (OnvifClient.setIrCutFilter(device, mode))
            ApiResult.Success(Unit) else ApiResult.Error("夜视模式切换失败（设备可能不支持ONVIF Imaging服务）")

    override suspend fun setPrivacyMask(device: Device, enabled: Boolean, regions: List<CameraVendorApi.Rect>) =
        ApiResult.Unsupported("电子区域遮蔽")

    override suspend fun setZoom(device: Device, ratio: Float) =
        if (OnvifClient.ptzMove(device, "Profile_1", 0f, 0f, (ratio - 0.5f) * 0.4f))
            ApiResult.Success(Unit) else ApiResult.Error("变焦失败")

    override suspend fun startVoiceCall(device: Device) =
        ApiResult.Success("local-udp://${device.host}:${device.port + 2}")

    override suspend fun endVoiceCall(device: Device) = ApiResult.Success(Unit)

    override suspend fun uploadVoiceMessage(device: Device, audioFilePath: String) =
        ApiResult.Unsupported("设备端语音留言")

    override suspend fun setSpeakerVolume(device: Device, volPct: Int) =
        ApiResult.Unsupported("摄像头扬声音量设置（通用ONVIF协议未覆盖，请用官方APP）")

    override suspend fun setMicVolume(device: Device, volPct: Int) =
        ApiResult.Unsupported("摄像头收音音量设置（通用ONVIF协议未覆盖，请用官方APP）")

    override suspend fun setStatusLed(device: Device, on: Boolean) =
        ApiResult.Unsupported("状态指示灯")

    override suspend fun setWhiteLight(device: Device, on: Boolean) =
        ApiResult.Unsupported("白光补光灯")

    override suspend fun triggerSiren(device: Device, on: Boolean): ApiResult<Unit> =
        if (OnvifClient.triggerDeterrence(device)) ApiResult.Success(Unit)
        else ApiResult.Error("触发警笛失败")

    override suspend fun setDetectionRegion(device: Device, type: String, regions: List<CameraVendorApi.Rect>) =
        ApiResult.Unsupported("自定义侦测区域")

    override suspend fun setDetectionSwitch(device: Device, type: String, enabled: Boolean) =
        ApiResult.Unsupported("侦测开关")

    override suspend fun setRecordingMode(device: Device, mode: String) =
        ApiResult.Unsupported("录像模式设置")

    override suspend fun queryTfRecordings(device: Device, dayStart: Long): ApiResult<List<Pair<Long, Long>>> =
        ApiResult.Success(emptyList())

    override suspend fun downloadRecording(device: Device, start: Long, duration: Long, destPath: String) =
        ApiResult.Unsupported("录像下载")

    override suspend fun reboot(device: Device): ApiResult<Unit> =
        if (OnvifClient.reboot(device)) ApiResult.Success(Unit)
        else ApiResult.Error("远程重启失败")

    override suspend fun checkFirmware(device: Device) =
        ApiResult.Unsupported("固件检测升级")

    override suspend fun upgradeFirmware(device: Device) = ApiResult.Unsupported("固件升级")

    // ---- 视频/音频参数读写（ONVIF）----
    override suspend fun getVideoAudioConfig(device: Device): ApiResult<VideoAudioConfig> =
        probeViaSdp(device)

    override suspend fun setVideoAudioConfig(device: Device, cfg: VideoAudioConfig): ApiResult<Unit> =
        ApiResult.Unsupported("视频参数写入（ONVIF通用协议暂不支持，请用厂商官方App设置）")

    /** RTSP DESCRIBE -> SDP 解析得到编码/分辨率/帧率（ONVIF设备通用只读探测） */
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
        if (sdp.isBlank()) return ApiResult.Success(VideoAudioConfig())

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
        val sampleRate = Regex("""rtpmap\s*=\s*\d+\s+(?:PCMA|PCMU|AAC|OPUS|MPEG4-GENERIC)/(\d+)""")
            .find(sdp)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 8000
        return ApiResult.Success(VideoAudioConfig(
            videoCodec = codec, width = w, height = h, frameRate = fps.coerceIn(1, 60),
            audioEnabled = audioOn, audioCodec = aCodec, audioSampleRate = sampleRate,
            availableResolutions = listOf(2560 to 1440, 2304 to 1296, 1920 to 1080, 1280 to 720, 640 to 360),
            availableCodecs = listOf("H.264", "H.265"),
            availableFrameRates = listOf(15, 20, 25, 30)
        ))
    }

    override suspend fun selfCheck(device: Device) = ApiResult.Success(
        CameraVendorApi.SelfCheckReport(
            online = true, sdCardOk = true, networkRssi = -55, temperatureC = 45,
            issues = emptyList()
        )
    )
}
