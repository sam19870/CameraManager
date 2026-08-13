package com.cameramanager.app.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cameramanager.app.data.model.AlarmEvent
import com.cameramanager.app.databinding.ItemAlarmBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmAdapter(
    private val onAck: (Long) -> Unit
) : ListAdapter<AlarmEvent, AlarmAdapter.VH>(DIFF) {

    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    fun submit(list: List<AlarmEvent>) = submitList(list)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAlarmBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemAlarmBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(e: AlarmEvent) {
            b.alarmType.text = typeLabel(e.type)
            b.alarmMsg.text = e.message
            b.alarmTime.text = sdf.format(Date(e.timestamp))
            b.alarmAck.text = if (e.acknowledged) "已处理" else "点击确认"
            if (!e.acknowledged) {
                b.root.setOnClickListener { onAck(e.id) }
            } else {
                b.root.setOnClickListener(null)
            }
        }
    }

    private fun typeLabel(type: String) = when (type) {
        "human" -> "人形告警"
        "motion" -> "移动告警"
        "track" -> "追踪告警"
        "offline" -> "离线告警"
        else -> "告警"
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AlarmEvent>() {
            override fun areItemsTheSame(o: AlarmEvent, n: AlarmEvent) = o.id == n.id
            override fun areContentsTheSame(o: AlarmEvent, n: AlarmEvent) = o == n
        }
    }
}
