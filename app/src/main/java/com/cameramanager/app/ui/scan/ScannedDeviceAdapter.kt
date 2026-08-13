package com.cameramanager.app.ui.scan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cameramanager.app.data.model.ScannedDevice
import com.cameramanager.app.databinding.ItemScannedDeviceBinding

class ScannedDeviceAdapter(
    private val onClick: (ScannedDevice) -> Unit
) : ListAdapter<ScannedDevice, ScannedDeviceAdapter.VH>(DIFF) {

    fun submit(list: List<ScannedDevice>) = submitList(list)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemScannedDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemScannedDeviceBinding) : RecyclerView.ViewHolder(b.root) {
        init { b.root.setOnClickListener { onClick(getItem(bindingAdapterPosition)) } }
        fun bind(d: ScannedDevice) {
            b.scanName.text = if (d.onvif) "ONVIF 摄像头" else "RTSP 摄像头"
            b.scanHost.text = "${d.host}:${d.port}"
            b.scanMfr.text = "${d.manufacturer} · ${d.model}"
            b.scanBadge.text = if (d.onvif) "云台/ONVIF" else "RTSP"
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ScannedDevice>() {
            override fun areItemsTheSame(o: ScannedDevice, n: ScannedDevice) = o.host == n.host
            override fun areContentsTheSame(o: ScannedDevice, n: ScannedDevice) = o == n
        }
    }
}
