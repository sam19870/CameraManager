package com.cameramanager.app.ui.tunnel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cameramanager.app.CameraApp
import com.cameramanager.app.data.model.Tunnel
import com.cameramanager.app.databinding.ActivityTunnelManageBinding
import com.cameramanager.app.databinding.DialogTunnelBinding
import com.cameramanager.app.databinding.ItemTunnelBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 内网穿透通道管理：自由增删改查多个通道，并显示连接状态（启用开关）。
 *
 * 每条通道只是「公网入口 host:port」的描述（frp / ngrok / 端口转发 / ZeroTier
 * 均可），不区分底层实现 —— 因为 App 拿到 host:port 就能直接 RTSP/ONVIF 连过去。
 *
 * 设备在 [com.cameramanager.app.ui.scan.AddDeviceActivity] 或设备设置里绑定其中
 * 一条通道（[Tunnel.id] → [com.cameramanager.app.data.model.Device.tunnelId]），
 * 当手机不在设备内网时 [com.cameramanager.app.net.NetworkRouter] 会自动选用。
 */
class TunnelManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTunnelManageBinding
    private val repo by lazy { CameraApp.get().repository }
    private val adapter = TunnelAdapter(
        onToggle = { t, enabled -> lifecycleScope.launch { repo.setTunnelEnabled(t.id, enabled) } },
        onEdit = { showEditDialog(it) },
        onDelete = { confirmDelete(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTunnelManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabAdd.setOnClickListener { showEditDialog(null) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeTunnels().collectLatest { list ->
                    adapter.submit(list)
                    binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /** editing == null 表示新增，否则编辑现有通道。 */
    private fun showEditDialog(editing: Tunnel?) {
        val dlgBinding = DialogTunnelBinding.inflate(layoutInflater)
        if (editing != null) {
            dlgBinding.editName.setText(editing.name)
            dlgBinding.editHost.setText(editing.host)
            dlgBinding.editPort.setText(editing.port.toString())
            dlgBinding.editOnvifPort.setText(editing.onvifPort.toString())
            dlgBinding.editRemark.setText(editing.remark.orEmpty())
            dlgBinding.switchEnabled.isChecked = editing.enabled
        }
        AlertDialog.Builder(this)
            .setTitle(if (editing == null) "新增穿透通道" else "编辑通道")
            .setView(dlgBinding.root)
            .setPositiveButton("保存") { _, _ ->
                val name = dlgBinding.editName.text.toString().trim()
                val host = dlgBinding.editHost.text.toString().trim()
                val port = dlgBinding.editPort.text.toString().trim().toIntOrNull() ?: 554
                val onvif = dlgBinding.editOnvifPort.text.toString().trim().toIntOrNull() ?: 0
                val remark = dlgBinding.editRemark.text.toString().trim().ifEmpty { null }
                val enabled = dlgBinding.switchEnabled.isChecked
                if (name.isEmpty() || host.isEmpty()) {
                    Toast.makeText(this, "名称和地址不能为空", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                lifecycleScope.launch {
                    if (editing == null) {
                        repo.saveTunnel(Tunnel(
                            name = name, host = host, port = port,
                            onvifPort = onvif, enabled = enabled, remark = remark
                        ))
                        Toast.makeText(this@TunnelManageActivity, "已添加通道", Toast.LENGTH_SHORT).show()
                    } else {
                        repo.updateTunnel(editing.copy(
                            name = name, host = host, port = port,
                            onvifPort = onvif, enabled = enabled, remark = remark
                        ))
                        Toast.makeText(this@TunnelManageActivity, "已更新通道", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(tunnel: Tunnel) {
        AlertDialog.Builder(this)
            .setTitle("删除通道")
            .setMessage("确定删除「${tunnel.name}」吗？绑定该通道的设备将回退到公网/直连。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    repo.deleteTunnel(tunnel)
                    Toast.makeText(this@TunnelManageActivity, "已删除", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, TunnelManageActivity::class.java)
    }
}

/** 通道列表适配器。开关即时反映启用状态（即「连接状态/开关显示」）。 */
class TunnelAdapter(
    private val onToggle: (Tunnel, Boolean) -> Unit,
    private val onEdit: (Tunnel) -> Unit,
    private val onDelete: (Tunnel) -> Unit
) : RecyclerView.Adapter<TunnelAdapter.VH>() {

    private val items = mutableListOf<Tunnel>()

    fun submit(list: List<Tunnel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemTunnelBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTunnelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        with(holder.b) {
            tunnelName.text = t.name
            tunnelHost.text = "RTSP：${t.host}:${t.port}"
            tunnelOnvif.text = "ONVIF 端口：${if (t.onvifPort > 0) t.onvifPort else "无"}"
            if (!t.remark.isNullOrEmpty()) {
                tunnelRemark.visibility = View.VISIBLE
                tunnelRemark.text = "备注：${t.remark}"
            } else {
                tunnelRemark.visibility = View.GONE
            }
            // 避免开关回调时再次触发监听
            tunnelSwitch.setOnCheckedChangeListener(null)
            tunnelSwitch.isChecked = t.enabled
            tunnelSwitch.setOnCheckedChangeListener { _, isChecked -> onToggle(t, isChecked) }
            btnEdit.setOnClickListener { onEdit(t) }
            btnDelete.setOnClickListener { onDelete(t) }
        }
    }

    override fun getItemCount(): Int = items.size
}
