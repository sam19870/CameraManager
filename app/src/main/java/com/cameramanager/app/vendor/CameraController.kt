package com.cameramanager.app.vendor

import com.cameramanager.app.data.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * High-level facade that the UI talks to. Wraps a [CameraVendorApi] for a device
 * and exposes friendly, capability-aware operations. Each method returns a
 * [CameraCommandResult] so callers can show a toast/snackbar without knowing the
 * underlying vendor.
 */
class CameraController(private val device: Device) {

    private val api: CameraVendorApi = CameraVendorApi.forDevice(device)

    private val _capabilities = MutableStateFlow<CameraCapabilities?>(null)
    val capabilities: StateFlow<CameraCapabilities?> = _capabilities.asStateFlow()

    private val _cruising = MutableStateFlow(false)
    val cruising: StateFlow<Boolean> = _cruising.asStateFlow()

    private val _autoTracking = MutableStateFlow(false)
    val autoTracking: StateFlow<Boolean> = _autoTracking.asStateFlow()

    suspend fun refreshCapabilities(): CameraCapabilities? = withContext(Dispatchers.IO) {
        val result = api.queryCapabilities(device)
        val caps = (result as? ApiResult.Success)?.data
        _capabilities.value = caps
        caps
    }

    fun caps(): CameraCapabilities? = _capabilities.value

    // ---- PTZ ----
    suspend fun move(pan: Float, tilt: Float, zoom: Float = 0f): CameraCommandResult =
        guard(CameraCapabilities::ptz) { api.ptzMove(device, pan, tilt, zoom) }

    suspend fun stop(): CameraCommandResult =
        guard(CameraCapabilities::ptz) { api.ptzStop(device) }

    suspend fun gotoPreset(index: Int): CameraCommandResult =
        guard(CameraCapabilities::presets) { api.ptzGotoPreset(device, index) }

    suspend fun savePreset(index: Int, name: String): CameraCommandResult =
        guard(CameraCapabilities::presets) { api.ptzSavePreset(device, index, name) }

    suspend fun deletePreset(index: Int): CameraCommandResult =
        guard(CameraCapabilities::presets) { api.ptzDeletePreset(device, index) }

    suspend fun home(): CameraCommandResult =
        guard(CameraCapabilities::ptz) { api.ptzHome(device) }

    suspend fun toggleCruise(): CameraCommandResult {
        val caps = _capabilities.value
        if (caps?.cruise != true) return CameraCommandResult.Unsupported("自动巡航")
        val next = !_cruising.value
        return when (val r = api.setCruise(device, next)) {
            is ApiResult.Success -> { _cruising.value = next; CameraCommandResult.OkWithMessage(if (next) "已开启自动巡航" else "已停止巡航") }
            is ApiResult.Unsupported -> CameraCommandResult.Unsupported("自动巡航")
            is ApiResult.Error -> CameraCommandResult.Failed(r.message)
        }
    }

    suspend fun toggleAutoTrack(): CameraCommandResult {
        val caps = _capabilities.value
        if (caps?.autoTrack != true) return CameraCommandResult.Unsupported("AI人形追踪")
        val next = !_autoTracking.value
        return when (val r = api.setAutoTrack(device, next)) {
            is ApiResult.Success -> { _autoTracking.value = next; CameraCommandResult.OkWithMessage(if (next) "已开启AI追踪" else "已关闭AI追踪") }
            is ApiResult.Unsupported -> CameraCommandResult.Unsupported("AI人形追踪")
            is ApiResult.Error -> CameraCommandResult.Failed(r.message)
        }
    }

    suspend fun setZoom(ratio: Float): CameraCommandResult =
        guard(CameraCapabilities::zoom) { api.setZoom(device, ratio) }

    // ---- Picture / night / privacy ----
    suspend fun setNightVision(mode: Int): CameraCommandResult =
        guard(CameraCapabilities::nightVision) { api.setNightVision(device, mode) }

    suspend fun setPrivacyMask(enabled: Boolean, regions: List<CameraVendorApi.Rect> = emptyList()): CameraCommandResult =
        guard(CameraCapabilities::privacyMask) { api.setPrivacyMask(device, enabled, regions) }

    // ---- Voice ----
    suspend fun startVoice(): CameraCommandResult =
        guard(CameraCapabilities::voiceIntercom) { api.startVoiceCall(device) }

    suspend fun endVoice(): CameraCommandResult =
        guard(CameraCapabilities::voiceIntercom) { api.endVoiceCall(device) }

    suspend fun uploadVoiceMessage(path: String): CameraCommandResult =
        guard(CameraCapabilities::voiceMessage) { api.uploadVoiceMessage(device, path) }

    // ---- Alarm ----
    suspend fun setWhiteLight(on: Boolean): CameraCommandResult =
        guard(CameraCapabilities::whiteLight) { api.setWhiteLight(device, on) }

    suspend fun triggerSiren(on: Boolean): CameraCommandResult =
        guard(CameraCapabilities::siren) { api.triggerSiren(device, on) }

    suspend fun setDetectionRegion(type: String, regions: List<CameraVendorApi.Rect>): CameraCommandResult =
        guard(CameraCapabilities::detectionRegion) { api.setDetectionRegion(device, type, regions) }

    suspend fun setDetectionSwitch(type: String, enabled: Boolean): CameraCommandResult =
        guard(CameraCapabilities::detectionRegion) { api.setDetectionSwitch(device, type, enabled) }

    // ---- Recording ----
    suspend fun setRecordingMode(mode: String): CameraCommandResult =
        guard(CameraCapabilities::tfStorage) { api.setRecordingMode(device, mode) }

    suspend fun queryTfRecordings(dayStart: Long): List<Pair<Long, Long>> {
        return (api.queryTfRecordings(device, dayStart) as? ApiResult.Success)?.data ?: emptyList()
    }

    suspend fun downloadRecording(start: Long, duration: Long, destPath: String): CameraCommandResult =
        guard(CameraCapabilities::tfStorage) { api.downloadRecording(device, start, duration, destPath) }

    // ---- Device management ----
    suspend fun reboot(): CameraCommandResult =
        guard(CameraCapabilities::restart) { api.reboot(device) }

    suspend fun checkFirmware(): CameraCommandResult {
        val caps = _capabilities.value
        if (caps?.firmwareUpgrade != true) return CameraCommandResult.Unsupported("固件检测升级")
        return when (val r = api.checkFirmware(device)) {
            is ApiResult.Success -> CameraCommandResult.OkWithMessage(
                "当前版本 ${r.data.current}" + if (r.data.upgradeAvailable) "，新版本 ${r.data.latest} 可升级" else "，已是最新版本",
                payload = r.data
            )
            else -> CameraCommandResult.Failed("固件检测失败")
        }
    }

    suspend fun upgradeFirmware(): CameraCommandResult =
        guard(CameraCapabilities::firmwareUpgrade) { api.upgradeFirmware(device) }

    suspend fun selfCheck(): CameraCommandResult {
        return when (val r = api.selfCheck(device)) {
            is ApiResult.Success -> {
                val report = r.data
                val lines = mutableListOf<String>()
                lines += if (report.online) "● 设备在线" else "○ 设备离线"
                lines += if (report.sdCardOk) "● SD卡正常" else "○ SD卡异常"
                lines += "信号强度: ${report.networkRssi} dBm"
                lines += "设备温度: ${report.temperatureC}°C"
                if (report.issues.isNotEmpty()) lines += "问题: ${report.issues.joinToString()}"
                CameraCommandResult.OkWithMessage(lines.joinToString("\n"), payload = report)
            }
            is ApiResult.Unsupported -> CameraCommandResult.Unsupported("设备自检")
            is ApiResult.Error -> CameraCommandResult.Failed(r.message)
        }
    }

    private suspend inline fun guard(
        selector: (CameraCapabilities) -> Boolean,
        crossinline block: suspend () -> ApiResult<Unit>
    ): CameraCommandResult {
        val caps = _capabilities.value ?: refreshCapabilities()
        if (caps?.let(selector) != true) {
            return CameraCommandResult.Unsupported(featureLabel(selector))
        }
        return when (val r = block()) {
            is ApiResult.Success -> CameraCommandResult.Ok()
            is ApiResult.Unsupported -> CameraCommandResult.Unsupported(featureLabel(selector))
            is ApiResult.Error -> CameraCommandResult.Failed(r.message)
        }
    }

    /** Result of a user-facing camera operation. */
    sealed class CameraCommandResult(val message: String?) {
        object Ok : CameraCommandResult(null)
        class OkWithMessage(val text: String, val payload: Any? = null) : CameraCommandResult(text)
        class Unsupported(val feature: String) : CameraCommandResult("设备不支持「$feature」功能")
        class Failed(val reason: String) : CameraCommandResult("操作失败: $reason")
    }

    private fun featureLabel(selector: (CameraCapabilities) -> Boolean): String = when {
        selector == CameraCapabilities::ptz -> "云台控制"
        selector == CameraCapabilities::zoom -> "变焦"
        selector == CameraCapabilities::presets -> "预置位"
        selector == CameraCapabilities::cruise -> "自动巡航"
        selector == CameraCapabilities::autoTrack -> "AI人形追踪"
        selector == CameraCapabilities::nightVision -> "夜视模式"
        selector == CameraCapabilities::privacyMask -> "隐私遮蔽"
        selector == CameraCapabilities::whiteLight -> "白光补光灯"
        selector == CameraCapabilities::siren -> "警笛"
        selector == CameraCapabilities::voiceIntercom -> "语音对讲"
        selector == CameraCapabilities::voiceMessage -> "语音留言"
        selector == CameraCapabilities::firmwareUpgrade -> "固件升级"
        selector == CameraCapabilities::restart -> "远程重启"
        selector == CameraCapabilities::detectionRegion -> "侦测区域"
        selector == CameraCapabilities::tfStorage -> "TF卡录像"
        else -> "此功能"
    }
}
