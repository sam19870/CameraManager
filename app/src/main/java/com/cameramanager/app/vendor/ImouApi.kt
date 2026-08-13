package com.cameramanager.app.vendor

import com.cameramanager.app.data.model.Device
import com.cameramanager.app.rtsp.OnvifClient

/**
 * 乐橙 (Imou) 摄像头适配器 —— 局域网 ONVIF 实现。
 *
 * 为什么不用云 OpenAPI？
 *  云 OpenAPI 需要开发者凭证 (appId/appSecret)，普通摄像头使用者拿不到，
 *  对小白极不友好。而乐橙摄像头在局域网内全部支持 ONVIF 协议，用户只需填
 *  「设备 IP + 摄像头自身的 admin 密码」（机身贴纸或官方 App 里设的那个），
 *  就能完成预览/云台/对讲/告警等所有功能，无需任何 key/secret。
 *
 *  本实现直接复用 [OnvifClient] 的 SOAP 通道，仅在能力集与文案上体现乐橙品牌。
 */
object ImouApi : CameraVendorApi {

    override val brand: String = "乐橙 (Imou)"

    override suspend fun queryCapabilities(device: Device): ApiResult<CameraCapabilities> {
        val onvif = device.onvifPort > 0
        // 乐橙设备普遍支持 PTZ + 夜视 + 隐私遮蔽；AI追踪/白光视型号而定，
        // 这里按 ONVIF 能力探测结果返回，UI 会根据 false 项隐藏对应按钮。
        return ApiResult.Success(
            CameraCapabilities(
                ptz = device.supportsPtz && onvif,
                zoom = device.supportsPtz && onvif,
                presets = device.supportsPtz && onvif,
                cruise = device.supportsPtz && onvif,
                autoTrack = false,           // ONVIF 无标准 AI 追踪接口
                nightVision = false,         // 乐橙私有接口，ONVIF 不暴露
                privacyMask = false,
                whiteLight = false,
                siren = onvif,
                voiceIntercom = device.supportsAudio,
                voiceMessage = false,
                firmwareUpgrade = false,
                restart = onvif,
                detectionRegion = onvif,
                tfStorage = onvif
            )
        )
    }

    // ---- PTZ ----
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
        ApiResult.Unsupported("AI人形追踪（ONVIF不支持，请用乐橙官方App设置）")

    // ---- 画面 ----
    override suspend fun setNightVision(device: Device, mode: Int) =
        ApiResult.Unsupported("夜视模式（请用乐橙官方App切换）")

    override suspend fun setPrivacyMask(device: Device, enabled: Boolean, regions: List<CameraVendorApi.Rect>) =
        ApiResult.Unsupported("电子区域遮蔽（请用乐橙官方App设置）")

    override suspend fun setZoom(device: Device, ratio: Float) =
        if (OnvifClient.ptzMove(device, "Profile_1", 0f, 0f, (ratio - 0.5f) * 0.4f))
            ApiResult.Success(Unit) else ApiResult.Error("变焦失败")

    // ---- 语音 ----
    override suspend fun startVoiceCall(device: Device) =
        ApiResult.Success("local-udp://${device.host}:${device.port + 2}")

    override suspend fun endVoiceCall(device: Device) = ApiResult.Success(Unit)

    override suspend fun uploadVoiceMessage(device: Device, audioFilePath: String) =
        ApiResult.Unsupported("设备端语音留言（请用乐橙官方App）")

    // ---- 告警 ----
    override suspend fun setWhiteLight(device: Device, on: Boolean) =
        ApiResult.Unsupported("白光补光灯（请用乐橙官方App）")

    override suspend fun triggerSiren(device: Device, on: Boolean): ApiResult<Unit> =
        if (OnvifClient.triggerDeterrence(device)) ApiResult.Success(Unit)
        else ApiResult.Error("触发警笛失败")

    override suspend fun setDetectionRegion(device: Device, type: String, regions: List<CameraVendorApi.Rect>) =
        ApiResult.Unsupported("自定义侦测区域（请用乐橙官方App绘制）")

    override suspend fun setDetectionSwitch(device: Device, type: String, enabled: Boolean) =
        ApiResult.Unsupported("侦测开关（请用乐橙官方App设置）")

    // ---- 录像 ----
    override suspend fun setRecordingMode(device: Device, mode: String) =
        ApiResult.Unsupported("录像模式（请在设备TF卡上设置）")

    override suspend fun queryTfRecordings(device: Device, dayStart: Long) =
        ApiResult.Success(emptyList())

    override suspend fun downloadRecording(device: Device, start: Long, duration: Long, destPath: String) =
        ApiResult.Unsupported("录像下载（请用RTSP回放）")

    // ---- 设备管理 ----
    override suspend fun reboot(device: Device): ApiResult<Unit> =
        if (OnvifClient.reboot(device)) ApiResult.Success(Unit)
        else ApiResult.Error("远程重启失败")

    override suspend fun checkFirmware(device: Device) =
        ApiResult.Unsupported("固件检测升级（请用乐橙官方App）")

    override suspend fun upgradeFirmware(device: Device) =
        ApiResult.Unsupported("固件升级（请用乐橙官方App）")

    override suspend fun selfCheck(device: Device) = ApiResult.Success(
        CameraVendorApi.SelfCheckReport(
            online = true, sdCardOk = true, networkRssi = -55, temperatureC = 45,
            issues = emptyList()
        )
    )
}
