package com.cameramanager.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ItemMultiPreviewBinding

class MultiPreviewAdapter(
    private val onClick: (Device) -> Unit
) : ListAdapter<Device, MultiPreviewAdapter.VH>(DIFF) {

    fun submit(list: List<Device>) = submitList(list)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMultiPreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemMultiPreviewBinding) : RecyclerView.ViewHolder(b.root) {
        init { b.root.setOnClickListener { onClick(getItem(bindingAdapterPosition)) } }

        fun bind(device: Device) {
            b.tileName.text = device.name
            // A real implementation attaches an RtspPlayer to b.tileSurface here.
            b.tileStatus.text = if (device.online) "● 在线" else "○ 离线"
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Device>() {
            override fun areItemsTheSame(o: Device, n: Device) = o.id == n.id
            override fun areContentsTheSame(o: Device, n: Device) = o == n
        }
    }
}
