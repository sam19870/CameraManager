package com.cameramanager.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cameramanager.app.CameraApp
import com.cameramanager.app.data.Repository
import com.cameramanager.app.data.model.Device
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DeviceListViewModel(private val repo: Repository) : ViewModel() {

    val devices: StateFlow<List<Device>> = repo.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(device: Device) = viewModelScope.launch { repo.deleteDevice(device) }

    fun addDevice(device: Device) = viewModelScope.launch { repo.addDevice(device) }

    fun updateDevice(device: Device) = viewModelScope.launch { repo.updateDevice(device) }
}

class DeviceViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val repo = CameraApp.get().repository
        return when {
            modelClass.isAssignableFrom(DeviceListViewModel::class.java) ->
                DeviceListViewModel(repo) as T
            modelClass.isAssignableFrom(PreviewViewModel::class.java) ->
                PreviewViewModel(repo) as T
            modelClass.isAssignableFrom(ScanViewModel::class.java) ->
                ScanViewModel(repo) as T
            modelClass.isAssignableFrom(PlaybackViewModel::class.java) ->
                PlaybackViewModel(repo) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(repo) as T
            else -> throw IllegalArgumentException("Unknown ViewModel $modelClass")
        }
    }
}
