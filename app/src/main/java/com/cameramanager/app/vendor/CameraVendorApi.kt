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
    val tfStorage: Boolean = false,
    /** 是否支持通过协议读写视频编码参数（分辨率/帧率/码率/编码） */
    val videoConfig: Boolean = false,
    /** 是否支持通过协议读写音频输入/输出开关、音量 */
    val audioConfig: Boolean = false
)

/**
 * 视频/音频编码参数配置。
 * 这些参数一律从摄像头里读出来（探测），再在 UI 上给用户改；修改后再写回摄像头。
 * 不支持的摄像头返回 [ApiResult.Unsupported]，UI 自动灰显。
 */
data class VideoAudioConfig(
    // ---- 视频 ----
    /** 视频编码: "H.264" / "H.265" / "MPEG4" / "MJPEG" (探测自 RTSP SDP 或 ONVIF) */
    val videoCodec: String = "H.264",
    /** 分辨率宽 (像素)，例如 1920 */
    val width: Int = 1920,
    /** 分辨率高 (像素)，例如 1080 */
    val height: Int = 1080,
    /** 帧率 fps，常见值: 15 / 25 / 30 / 60 */
    val frameRate: Int = 25,
    /** 比特率 kbps（0=自动/可变码率 VBR） */
    val bitrateKbps: Int = 4096,
    /** 码率控制: "VBR"(动态) / "CBR"(固定) */
    val rateControl: String = "VBR",
    /** I帧间隔(GOP) */
    val iFrameInterval: Int = 50,
    // ---- 流类型（视频/音频/音视频）----
    /** true = 视频流启用 */
    val videoEnabled: Boolean = true,
    /** true = 音频流启用（录像含声音 / 预览有声音） */
    val audioEnabled: Boolean = true,
    // ---- 音频输入 ----
    /** 音频编码: "G.711A" / "G.711U" / "AAC" / "OPUS" */
    val audioCodec: String = "G.711A",
    /** 音频采样率 Hz：8000 / 16000 / 44100 / 48000 */
    val audioSampleRate: Int = 8000,
    /** 摄像机收音音量 0~100 */
    val micVolume: Int = 80,
    /** 摄像机扬声音量 0~100（对讲时） */
    val speakerVolume: Int = 80,
    /** 视频可用分辨率选项（从摄像头读出，用于下拉框） */
    val availableResolutions: List<Pair<Int, Int>> = listOf(2560 to 1440, 1920 to 1080, 1280 to 720, 640 to 360),
    /** 可用编码选项（从摄像头读出，用于下拉框） */
    val availableCodecs: List<String> = listOf("H.264", "H.265"),
    /** 可选帧率（从摄像头读出） */
    val availableFrameRates: List<Int> = listOf(15, 20, 25, 30)
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
    /** 摄像头端扬声音量 0~100（对讲播放时摄像头喇叭的音量） */
    suspend fun setSpeakerVolume(device: Device, volPct: Int): ApiResult<Unit>
    /** 摄像头端收音音量 0~100（环境声采集/监听的麦克风增益） */
    suspend fun setMicVolume(device: Device, volPct: Int): ApiResult<Unit>

    // ---- Security alarm ----
    /** Toggle the device status LED (状态指示灯). */
    suspend fun setStatusLed(device: Device, on: Boolean): ApiResult<Unit>
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

    // ---- 视频/音频参数读写（每个摄像头探测后由用户修改，再写回摄像头）----
    /**
     * 读取摄像头当前的音视频配置（编码/分辨率/帧率/码率/音频开关/音量）。
     * 不支持的摄像头会先尝试通过 RTSP DESCRIBE 的 SDP 做只读探测，无法写回时 UI 提示。
     */
    suspend fun getVideoAudioConfig(device: Device): ApiResult<VideoAudioConfig>
    /**
     * 写入摄像头音视频配置。写入后摄像头会立刻生效，录像/回放将按新参数。
     *  注意：修改分辨率或编码可能导致 RTSP 路径变，UI 上提示用户并刷新设备。
     */
    suspend fun setVideoAudioConfig(device: Device, cfg: VideoAudioConfig): ApiResult<Unit>

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
            "tplink" -> OnvifVendorApi   // TP-LINK 物联摄像头走标准ONVIF
            "imou" -> ImouApi
            else -> OnvifVendorApi
        }
    }
}
