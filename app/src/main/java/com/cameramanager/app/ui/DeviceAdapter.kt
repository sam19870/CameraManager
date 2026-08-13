package com.cameramanager.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ItemDeviceBinding

class DeviceAdapter(
    private val onClick: (Device) -> Unit,
    private val onLongClick: (Device) -> Unit
) : ListAdapter<Device, DeviceAdapter.VH>(DIFF) {

    fun submit(list: List<Device>) = submitList(list)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnClickListener { onClick(getItem(bindingAdapterPosition)) }
            b.root.setOnLongClickListener {
                onLongClick(getItem(bindingAdapterPosition)); true
            }
        }

        fun bind(device: Device) {
            b.deviceName.text = device.name
            b.deviceHost.text = "${device.host}:${device.port}"
            b.deviceProfile.text = device.profileLabel()
            b.deviceVendor.text = when (device.vendor) {
                "tapo" -> "TP-Link"
                "imou" -> "乐橙"
                else -> "ONVIF"
            }
            b.devicePtz.visibility = if (device.supportsPtz) View.VISIBLE else View.GONE
            b.deviceAudio.visibility = if (device.supportsAudio) View.VISIBLE else View.GONE
            b.statusDot.setBackgroundResource(
                if (device.online) com.cameramanager.app.R.drawable.dot_online
                else com.cameramanager.app.R.drawable.dot_offline
            )
            b.statusText.text = if (device.online) "在线" else "离线"
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Device>() {
            override fun areItemsTheSame(o: Device, n: Device) = o.id == n.id
            override fun areContentsTheSame(o: Device, n: Device) = o == n
        }
    }
}
