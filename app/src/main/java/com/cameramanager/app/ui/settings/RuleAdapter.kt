package com.cameramanager.app.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cameramanager.app.data.model.DetectionRule
import com.cameramanager.app.databinding.ItemRuleBinding

class RuleAdapter(
    private val onClick: (DetectionRule) -> Unit,
    private val onToggle: (DetectionRule, Boolean) -> Unit,
    private val onDelete: (DetectionRule) -> Unit
) : ListAdapter<DetectionRule, RuleAdapter.VH>(DIFF) {

    fun submit(list: List<DetectionRule>) = submitList(list)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemRuleBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(rule: DetectionRule) {
            b.ruleName.text = typeLabel(rule.type)
            b.ruleDesc.text = "灵敏度 ${rule.sensitivity} · ${actionsLabel(rule.actions)}" +
                if (rule.autoTrack) " · 自动追踪" else ""
            b.ruleSwitch.setOnCheckedChangeListener(null)
            b.ruleSwitch.isChecked = rule.enabled
            b.ruleSwitch.setOnCheckedChangeListener { _, checked -> onToggle(rule, checked) }
            b.root.setOnClickListener { onClick(rule) }
            b.btnDelete.setOnClickListener { onDelete(rule) }
        }
    }

    private fun typeLabel(type: String) = when (type) {
        "human" -> "人形检测"
        "motion" -> "移动侦测"
        "face" -> "人脸检测"
        "track" -> "移动追踪"
        else -> type
    }

    private fun actionsLabel(actions: Int): String {
        val parts = mutableListOf<String>()
        if (actions and 1 != 0) parts += "录像"
        if (actions and 2 != 0) parts += "推送"
        if (actions and 4 != 0) parts += "警示音"
        if (actions and 8 != 0) parts += "声光"
        return if (parts.isEmpty()) "无动作" else parts.joinToString("/")
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DetectionRule>() {
            override fun areItemsTheSame(o: DetectionRule, n: DetectionRule) = o.id == n.id
            override fun areContentsTheSame(o: DetectionRule, n: DetectionRule) = o == n
        }
    }
}
