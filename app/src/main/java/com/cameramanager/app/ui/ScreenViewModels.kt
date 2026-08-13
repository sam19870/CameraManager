package com.cameramanager.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cameramanager.app.data.Repository
import com.cameramanager.app.data.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PreviewViewModel(private val repo: Repository) : ViewModel() {

    private var _device = MutableStateFlow<Device?>(null)
    val device: StateFlow<Device?> = _device.asStateFlow()

    fun load(deviceId: Long) {
        viewModelScope.launch {
            repo.observeDevice(deviceId).collect { _device.value = it }
        }
    }

    fun updateRotation(degrees: Int) {
        val d = _device.value ?: return
        val newRot = ((degrees % 360) + 360) % 360
        viewModelScope.launch { repo.updateDevice(d.copy(rotation = newRot)) }
    }

    fun toggleMirror() {
        val d = _device.value ?: return
        viewModelScope.launch { repo.updateDevice(d.copy(mirrored = !d.mirrored)) }
    }

    fun setStreamProfile(profile: Int) {
        val d = _device.value ?: return
        viewModelScope.launch { repo.updateDevice(d.copy(streamProfile = profile)) }
    }
}

class ScanViewModel(private val repo: Repository) : ViewModel() {
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    suspend fun add(device: Device): Long = repo.addDevice(device)
}

class PlaybackViewModel(private val repo: Repository) : ViewModel() {
    private var _device = MutableStateFlow<Device?>(null)
    val device: StateFlow<Device?> = _device.asStateFlow()

    fun load(deviceId: Long) {
        viewModelScope.launch { repo.observeDevice(deviceId).collect { _device.value = it } }
    }

    suspend fun recordingsForDay(deviceId: Long, dayStart: Long, dayEnd: Long) =
        repo.getRecordingsForDay(deviceId, dayStart, dayEnd)
}

class SettingsViewModel(private val repo: Repository) : ViewModel() {
    fun rules(deviceId: Long) = repo.observeRules(deviceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun alarms(deviceId: Long) = repo.observeAlarms(deviceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun recentAlarms() = repo.observeRecentAlarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveRule(rule: com.cameramanager.app.data.model.DetectionRule) =
        viewModelScope.launch { repo.saveRule(rule) }

    fun deleteRule(rule: com.cameramanager.app.data.model.DetectionRule) =
        viewModelScope.launch { repo.deleteRule(rule) }

    fun acknowledgeAlarm(id: Long) = viewModelScope.launch { repo.acknowledgeAlarm(id) }
}
