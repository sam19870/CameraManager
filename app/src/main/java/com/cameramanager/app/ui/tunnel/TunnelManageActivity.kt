package com.cameramanager.app.ui.tunnel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
 * 内网穿透通道管理。
 *
 * 设计完全按用户家里的结构 + SakuraFrp 官方文档：
 *   - 老毛子路由器已经在跑 frpc 了，APP 不负责启动 frpc 进程
 *   - 老毛子 frpc 把内网 (192.168.1.x/24) 映射到 SakuraFrp 的公网入口
 *   - 这里只填公网入口连接信息：host(server_addr) / port(remote_port) / onvifPort
 *     可选认证：token / 账号 / 密码
 *     可选路由信息：lanCidr(内网网段) / lanGateway
 *
 * 只要填了公网入口信息，NetworkRouter 就会根据：
 *   - WiFi SSID 是否与设备绑定相同 → 走内网直连
 *   - 否则目标 IP 是否在 lanCidr 内 → 走这个通道的 host:port
 * 这样用户添加设备就可以直接填家里内网摄像头 192.168.1.108，无需关心内外网切换。
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
        runCatching {
            binding = ActivityTunnelManageBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = "内网穿透通道"

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
        }.onFailure { t ->
            Log.e(TAG, "onCreate crashed: ${t.message}", t)
            Toast.makeText(this, "穿透页加载失败: ${t.message}", Toast.LENGTH_SHORT).show()
            finish()
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
            dlgBinding.editToken.setText(editing.token.orEmpty())
            dlgBinding.editAuthUser.setText(editing.authUser.orEmpty())
            dlgBinding.editAuthPass.setText(editing.authPass.orEmpty())
            dlgBinding.editLanCidr.setText(editing.lanCidr.orEmpty())
            dlgBinding.editLanGateway.setText(editing.lanGateway.orEmpty())
            dlgBinding.editRemark.setText(editing.remark.orEmpty())
            dlgBinding.switchEnabled.isChecked = editing.enabled
        }

        AlertDialog.Builder(this)
            .setTitle(if (editing == null) "新增穿透通道" else "编辑通道")
            .setView(dlgBinding.root)
            .setPositiveButton("保存") { _, _ ->
                val name = dlgBinding.editName.text.toString().trim()
                val host = dlgBinding.editHost.text.toString().trim()
                val port = dlgBinding.editPort.text.toString().trim().toIntOrNull() ?: 0
                val onvifPort = dlgBinding.editOnvifPort.text.toString().trim().toIntOrNull() ?: 0
                val token = dlgBinding.editToken.text.toString().trim().ifEmpty { null }
                val authUser = dlgBinding.editAuthUser.text.toString().trim().ifEmpty { null }
                val authPass = dlgBinding.editAuthPass.text.toString().trim().ifEmpty { null }
                val lanCidr = dlgBinding.editLanCidr.text.toString().trim().ifEmpty { null }
                val lanGateway = dlgBinding.editLanGateway.text.toString().trim().ifEmpty { null }
                val remark = dlgBinding.editRemark.text.toString().trim().ifEmpty { null }
                val enabled = dlgBinding.switchEnabled.isChecked

                when {
                    name.isEmpty() -> toast("通道名称不能为空")
                    host.isEmpty() -> toast("入口域名/IP 不能为空（server_addr）")
                    port < 1 || port > 65535 -> toast("入口端口必须在 1~65535")
                    onvifPort < 0 || onvifPort > 65535 -> toast("ONVIF 端口必须在 0~65535（0=不单独映射）")
                    lanCidr != null && !isValidCidr(lanCidr) -> toast("内网网段 CIDR 格式不正确，例：192.168.1.0/24")
                    else -> {
                        lifecycleScope.launch {
                            val entity = Tunnel(
                                id = editing?.id ?: 0,
                                name = name,
                                host = host,
                                port = port,
                                onvifPort = onvifPort,
                                token = token,
                                authUser = authUser,
                                authPass = authPass,
                                lanCidr = lanCidr,
                                lanGateway = lanGateway,
                                enabled = enabled,
                                remark = remark
                            )
                            if (editing == null) {
                                repo.saveTunnel(entity)
                                toast("已添加通道")
                            } else {
                                repo.updateTunnel(entity)
                                toast("已更新通道")
                            }
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 宽松校验 CIDR 格式："a.b.c.d/xx" */
    private fun isValidCidr(cidr: String): Boolean {
        val parts = cidr.split('/')
        if (parts.size != 2) return false
        val mask = parts[1].toIntOrNull() ?: return false
        if (mask !in 0..32) return false
        val segs = parts[0].split('.')
        if (segs.size != 4) return false
        return segs.all { s -> s.all { it.isDigit() } && (s.toIntOrNull() ?: -1) in 0..255 }
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val TAG = "TunnelMgr"
        fun intent(context: Context): Intent =
            Intent(context, TunnelManageActivity::class.java)
    }
}

/** 通道列表适配器。开关即时反映启用状态。 */
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
            // 公网入口 + 内网网段映射信息
            tunnelHost.text = buildString {
                append(t.host).append(':').append(t.port)
                if (t.onvifPort > 0 && t.onvifPort != t.port) append("   ONVIF：").append(t.onvifPort)
            }
            tunnelOnvif.text = buildString {
                append("内网网段：")
                append(t.lanCidr ?: "<未填>")
                if (!t.lanGateway.isNullOrEmpty()) append("  ·  网关 ").append(t.lanGateway)
                if (!t.token.isNullOrBlank()) append("  ·  有 Token")
            }
            val rem = t.remark
            if (!rem.isNullOrEmpty()) {
                tunnelRemark.visibility = View.VISIBLE
                tunnelRemark.text = "备注：$rem"
            } else {
                tunnelRemark.visibility = View.GONE
            }
            // 避免开关回调时再次触发监听
            tunnelSwitch.setOnCheckedChangeListener(null)
            tunnelSwitch.isChecked = t.enabled
            tunnelSwitch.text = if (t.enabled) "● 启用中" else "○ 已关闭"
            tunnelSwitch.setOnCheckedChangeListener { _, isChecked -> onToggle(t, isChecked) }
            btnEdit.setOnClickListener { onEdit(t) }
            btnDelete.setOnClickListener { onDelete(t) }
        }
    }

    override fun getItemCount(): Int = items.size
}
