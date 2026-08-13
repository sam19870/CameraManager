package com.cameramanager.app.vendor

import com.cameramanager.app.data.model.Device

/**
 * Unified capability-flags structure for a device, queried at runtime so the UI
 * can enable/disable controls and prompt "not supported" gracefully.
 */
data class CameraCapabilities(
    val ptz: Boolean = false,
    val zoom: Boolean = false,
    val presets: Boolean = false,
    val cruise: Boolean = false,
    val autoTrack: Boolean = false,
    val nightVision: Boolean = false,
    val privacyMask: Boolean = false,
    val whiteLight: Boolean = false,
    val siren: Boolean = false,
    val voiceIntercom: Boolean = false,
    val voiceMessage: Boolean = false,
    val firmwareUpgrade: Boolean = false,
    val restart: Boolean = false,
    val detectionRegion: Boolean = false,
    val tfStorage: Boolean = false
)

/** A saved PTZ preset viewpoint. */
data class Preset(val index: Int, val name: String, val enabled: Boolean = true)

/** Result of a vendor API call. */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Unsupported(val feature: String) : ApiResult<Nothing>()
    data class Error(val message: String, val code: Int = -1) : ApiResult<Nothing>()
}

/**
 * Unified vendor-agnostic camera API. Each vendor (TP-Link Tapo, 乐橙 Imou,
 * generic ONVIF) provides an implementation. Methods that the device does not
 * support return [ApiResult.Unsupported], allowing the UI to surface a friendly
 * prompt instead of failing silently.
 *
 * Reference:
 *  - Tapo: https://github.com/pckbls/TapoAPI  (AES-RSA handshake, RPC over HTTP)
 *  - Imou: https://open.imoulife.com  (HTTP OpenAPI with device serial + appId)
 *  - ONVIF: Profile S / T (PTZ, Events, Media)
 */
interface CameraVendorApi {

    /** Brand label shown in UI. */
    val brand: String

    /** Probe the device and return its capability set. */
    suspend fun queryCapabilities(device: Device): ApiResult<CameraCapabilities>

    // ---- PTZ ----
    suspend fun ptzMove(device: Device, pan: Float, tilt: Float, zoom: Float = 0f): ApiResult<Unit>
    suspend fun ptzStop(device: Device): ApiResult<Unit>
    suspend fun ptzGotoPreset(device: Device, index: Int): ApiResult<Unit>
    suspend fun ptzSavePreset(device: Device, index: Int, name: String): ApiResult<Unit>
    suspend fun ptzDeletePreset(device: Device, index: Int): ApiResult<Unit>
    suspend fun listPresets(device: Device): ApiResult<List<Preset>>
    /** One-key return to home position (0,0). */
    suspend fun ptzHome(device: Device): ApiResult<Unit>
    /** Start/stop automatic patrol cruise. */
    suspend fun setCruise(device: Device, enabled: Boolean): ApiResult<Unit>
    /** AI human auto-tracking on/off. */
    suspend fun setAutoTrack(device: Device, enabled: Boolean): ApiResult<Unit>

    // ---- Real-time picture control ----
    /** Set night-vision mode: 0=auto, 1=ir, 2=color. */
    suspend fun setNightVision(device: Device, mode: Int): ApiResult<Unit>
    /** Toggle electronic privacy masking region overlay. */
    suspend fun setPrivacyMask(device: Device, enabled: Boolean, regions: List<Rect> = emptyList()): ApiResult<Unit>
    /** Lens zoom adjustment for zoom-capable models. ratio in (0,1]. */
    suspend fun setZoom(device: Device, ratio: Float): ApiResult<Unit>

    // ---- Voice intercom ----
    /** Open a duplex audio session token used by [VoiceIntercom]. */
    suspend fun startVoiceCall(device: Device): ApiResult<String>
    suspend fun endVoiceCall(device: Device): ApiResult<Unit>
    /** Upload a voice message to the device for later playback. */
    suspend fun uploadVoiceMessage(device: Device, audioFilePath: String): ApiResult<Unit>

    // ---- Security alarm ----
    suspend fun setWhiteLight(device: Device, on: Boolean): ApiResult<Unit>
    suspend fun triggerSiren(device: Device, on: Boolean): ApiResult<Unit>
    /** Set custom detection regions (human / region-intrusion / motion). */
    suspend fun setDetectionRegion(device: Device, type: String, regions: List<Rect>): ApiResult<Unit>
    suspend fun setDetectionSwitch(device: Device, type: String, enabled: Boolean): ApiResult<Unit>

    // ---- Recording / playback ----
    /** Recording mode: "continuous" (全天持续) or "motion" (移动侦测触发). */
    suspend fun setRecordingMode(device: Device, mode: String): ApiResult<Unit>
    /** Query recordings on the TF card for a day; returns list of (startMs, durationMs). */
    suspend fun queryTfRecordings(device: Device, dayStart: Long): ApiResult<List<Pair<Long, Long>>>
    /** Download a remote recording segment to local file. */
    suspend fun downloadRecording(device: Device, start: Long, duration: Long, destPath: String): ApiResult<Unit>

    // ---- Device management ----
    suspend fun reboot(device: Device): ApiResult<Unit>
    suspend fun checkFirmware(device: Device): ApiResult<FirmwareInfo>
    suspend fun upgradeFirmware(device: Device): ApiResult<Unit>
    suspend fun selfCheck(device: Device): ApiResult<SelfCheckReport>

    /** A 0..1 normalized rectangle on the picture. */
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float)

    data class FirmwareInfo(val current: String, val latest: String, val upgradeAvailable: Boolean)

    data class SelfCheckReport(
        val online: Boolean,
        val sdCardOk: Boolean,
        val networkRssi: Int,
        val temperatureC: Int,
        val issues: List<String>
    )

    companion object {
        /** Factory that picks the right vendor implementation by [Device.vendor]. */
        fun forDevice(device: Device): CameraVendorApi = when (device.vendor) {
            "tapo" -> TapoApi
            "imou" -> ImouApi
            else -> OnvifVendorApi
        }
    }
}
