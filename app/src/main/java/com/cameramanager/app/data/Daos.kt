package com.cameramanager.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.cameramanager.app.data.model.AlarmEvent
import com.cameramanager.app.data.model.DetectionRule
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.data.model.Recording
import com.cameramanager.app.data.model.Tunnel
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<Device>>

    @Query("SELECT * FROM devices ORDER BY createdAt ASC")
    suspend fun getAll(): List<Device>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun getById(id: Long): Device?

    @Query("SELECT * FROM devices WHERE id = :id")
    fun observeById(id: Long): Flow<Device?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: Device): Long

    @Update
    suspend fun update(device: Device)

    @Delete
    suspend fun delete(device: Device)
}

@Dao
interface DetectionRuleDao {
    @Query("SELECT * FROM detection_rules WHERE deviceId = :deviceId")
    fun observeForDevice(deviceId: Long): Flow<List<DetectionRule>>

    @Query("SELECT * FROM detection_rules WHERE deviceId = :deviceId AND enabled = 1")
    suspend fun getActiveForDevice(deviceId: Long): List<DetectionRule>

    @Query("SELECT * FROM detection_rules WHERE id = :id")
    suspend fun getById(id: Long): DetectionRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: DetectionRule): Long

    @Update
    suspend fun update(rule: DetectionRule)

    @Delete
    suspend fun delete(rule: DetectionRule)
}

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    fun observeForDevice(deviceId: Long): Flow<List<AlarmEvent>>

    @Query("SELECT * FROM alarms ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AlarmEvent>>

    @Insert
    suspend fun insert(event: AlarmEvent): Long

    @Query("UPDATE alarms SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    @Query("DELETE FROM alarms WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings WHERE deviceId = :deviceId ORDER BY startTime DESC")
    fun observeForDevice(deviceId: Long): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE deviceId = :deviceId AND startTime >= :dayStart AND startTime < :dayEnd ORDER BY startTime ASC")
    suspend fun getForDay(deviceId: Long, dayStart: Long, dayEnd: Long): List<Recording>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)
}

@Dao
interface TunnelDao {
    @Query("SELECT * FROM tunnels ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<Tunnel>>

    @Query("SELECT * FROM tunnels ORDER BY createdAt ASC")
    suspend fun getAll(): List<Tunnel>

    @Query("SELECT * FROM tunnels WHERE enabled = 1 ORDER BY createdAt ASC")
    suspend fun getEnabled(): List<Tunnel>

    @Query("SELECT * FROM tunnels WHERE id = :id")
    suspend fun getById(id: Long): Tunnel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tunnel: Tunnel): Long

    @Update
    suspend fun update(tunnel: Tunnel)

    @Delete
    suspend fun delete(tunnel: Tunnel)

    @Query("UPDATE tunnels SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}
