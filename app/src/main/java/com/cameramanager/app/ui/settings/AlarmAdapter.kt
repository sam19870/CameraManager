package com.cameramanager.app.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cameramanager.app.data.model.AlarmEvent
import com.cameramanager.app.databinding.ItemAlarmBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 告警列表适配器。item 结构参考 TP-LINK / 乐橙官方 App。
 */
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
            val (label, tint) = typeInfo(e.type)
            b.alarmType.text = label
            b.alarmUnreadDot.isVisible = !e.acknowledged
            b.alarmDevice.text = e.message.ifBlank { label }
            b.alarmTime.text = sdf.format(Date(e.timestamp))
            if (e.acknowledged) {
                b.alarmChip.text = "已处理"
            } else {
                b.alarmChip.text = "待处理"
            }
            // 图标背景色按类型着色
            runCatching {
                val bg = b.alarmIcon.background
                if (bg is android.graphics.drawable.GradientDrawable) {
                    bg.setColor(tint)
                }
            }
            // 缩略图（如果有快照路径 App 后续接入 Glide 显示；这里先保持占位）
            b.alarmThumb.isVisible = !e.snapshotPath.isNullOrBlank()
            // 点击确认
            b.root.setOnClickListener { if (!e.acknowledged) onAck(e.id) }
        }
    }

    private data class TypeInfo(val label: String, val tint: Int)

    private fun typeInfo(type: String): TypeInfo = when (type) {
        "human"   -> TypeInfo("人形告警", 0xFFEF4444.toInt())
        "motion"  -> TypeInfo("移动侦测", 0xFFF59E0B.toInt())
        "track"   -> TypeInfo("自动追踪", 0xFF8B5CF6.toInt())
        "offline" -> TypeInfo("设备离线", 0xFF6B7280.toInt())
        "alarm"   -> TypeInfo("声光告警", 0xFFDC2626.toInt())
        else      -> TypeInfo("告警",     0xFF3B82F6.toInt())
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AlarmEvent>() {
            override fun areItemsTheSame(o: AlarmEvent, n: AlarmEvent) = o.id == n.id
            override fun areContentsTheSame(o: AlarmEvent, n: AlarmEvent) = o == n
        }
    }
}
