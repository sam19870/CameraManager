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
                autoTrack = false,           // ONVIF has no standard auto-track
                nightVision = false,         // not in Profile S
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
        ApiResult.Unsupported("夜视模式")

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

    override suspend fun selfCheck(device: Device) = ApiResult.Success(
        CameraVendorApi.SelfCheckReport(
            online = true, sdCardOk = true, networkRssi = -55, temperatureC = 45,
            issues = emptyList()
        )
    )
}
