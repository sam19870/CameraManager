package com.cameramanager.app.data

import com.cameramanager.app.data.model.AlarmEvent
import com.cameramanager.app.data.model.DetectionRule
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.data.model.Recording
import com.cameramanager.app.data.model.Tunnel
import kotlinx.coroutines.flow.Flow

/**
 * Single repository abstraction over all DAOs. Used by ViewModels.
 */
class Repository(private val db: AppDatabase) {

    // ---- Devices ----
    fun observeDevices(): Flow<List<Device>> = db.deviceDao().observeAll()
    suspend fun getDevices(): List<Device> = db.deviceDao().getAll()
    suspend fun getDevice(id: Long): Device? = db.deviceDao().getById(id)
    fun observeDevice(id: Long): Flow<Device?> = db.deviceDao().observeById(id)
    suspend fun addDevice(device: Device): Long = db.deviceDao().insert(device)
    suspend fun updateDevice(device: Device) = db.deviceDao().update(device)
    suspend fun deleteDevice(device: Device) {
        db.deviceDao().delete(device)
        // cascade cleanup of rules
        db.detectionRuleDao().getActiveForDevice(device.id).forEach {
            db.detectionRuleDao().delete(it)
        }
    }

    // ---- Detection rules ----
    fun observeRules(deviceId: Long): Flow<List<DetectionRule>> =
        db.detectionRuleDao().observeForDevice(deviceId)
    suspend fun getActiveForDevice(deviceId: Long): List<DetectionRule> =
        db.detectionRuleDao().getActiveForDevice(deviceId)
    suspend fun getRule(id: Long): DetectionRule? = db.detectionRuleDao().getById(id)
    suspend fun saveRule(rule: DetectionRule): Long = db.detectionRuleDao().insert(rule)
    suspend fun updateRule(rule: DetectionRule) = db.detectionRuleDao().update(rule)
    suspend fun deleteRule(rule: DetectionRule) = db.detectionRuleDao().delete(rule)

    // ---- Alarms ----
    fun observeAlarms(deviceId: Long): Flow<List<AlarmEvent>> =
        db.alarmDao().observeForDevice(deviceId)
    fun observeRecentAlarms(limit: Int = 100): Flow<List<AlarmEvent>> =
        db.alarmDao().observeRecent(limit)
    suspend fun addAlarm(event: AlarmEvent): Long = db.alarmDao().insert(event)
    suspend fun acknowledgeAlarm(id: Long) = db.alarmDao().acknowledge(id)

    // ---- Recordings ----
    fun observeRecordings(deviceId: Long): Flow<List<Recording>> =
        db.recordingDao().observeForDevice(deviceId)
    suspend fun getRecordingsForDay(deviceId: Long, dayStart: Long, dayEnd: Long): List<Recording> =
        db.recordingDao().getForDay(deviceId, dayStart, dayEnd)
    suspend fun addRecording(recording: Recording): Long = db.recordingDao().insert(recording)
    suspend fun updateRecording(recording: Recording) = db.recordingDao().update(recording)
    suspend fun deleteRecording(recording: Recording) = db.recordingDao().delete(recording)

    // ---- Tunnels (内网穿透通道) ----
    fun observeTunnels(): Flow<List<Tunnel>> = db.tunnelDao().observeAll()
    suspend fun getTunnels(): List<Tunnel> = db.tunnelDao().getAll()
    suspend fun getEnabledTunnels(): List<Tunnel> = db.tunnelDao().getEnabled()
    suspend fun getTunnel(id: Long): Tunnel? = db.tunnelDao().getById(id)
    suspend fun saveTunnel(tunnel: Tunnel): Long = db.tunnelDao().insert(tunnel)
    suspend fun updateTunnel(tunnel: Tunnel) = db.tunnelDao().update(tunnel)
    suspend fun deleteTunnel(tunnel: Tunnel) = db.tunnelDao().delete(tunnel)
    suspend fun setTunnelEnabled(id: Long, enabled: Boolean) = db.tunnelDao().setEnabled(id, enabled)
}

