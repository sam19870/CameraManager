# 摄像头管家 CameraManager

一款 Android 摄像头管理 App，支持 **TP-Link Tapo**、**乐橙 (Imou)** 及 **第三方 ONVIF 兼容** 摄像头的接入与统一管理。UI 风格参考 TP-Link / 乐橙 官方 App（深色卡片化布局、底部操作条、虚拟方向盘）。

> ✅ **重要：无需任何开发者 key/secret/appId！**
> 所有品牌接入全部走**局域网协议**（Tapo 本地 RSA/AES + 乐橙/通用 ONVIF），用户只需要填摄像头的 IP + 自己设备的 admin 密码，就能完成接入。详见下方「如何添加设备」。

> 所有高级功能均通过统一的 `CameraVendorApi` 抽象层下发。当设备型号不支持某项功能时，App 会弹出「设备不支持「XX」功能」提示，而不会崩溃或静默失败。

## 功能总览

### 一、云台控制（云台机型通用）
- 虚拟方向盘 8 方向控制，点击/长按转动，松开停止
- 多个常用预置位保存与一键切换
- AI 人形自动追踪开关
- 变焦机型远近镜头拉伸（变焦机型）
- 云台一键复位归位
- 云台点位自动巡航循环监测

### 二、实时画面控制
- 多档清晰度：高清(主码流) / 标清(子码流) / 流畅
- 实时截图、手动本地录像（保存到手机）
- 画面翻转、画面镜像（适配吊装/壁挂/倒装）
- 夜视模式：智能夜视 / 红外夜视 / 全彩夜视
- 悬浮窗播放（需 SYSTEM_ALERT_WINDOW 权限）
- 电子区域隐私遮蔽

### 三、语音对讲控制
- 双向实时语音对讲（按住对讲）
- 设备端语音留言、远程语音播放留言（Tapo/Imou）

### 四、安防告警控制
- 远程手动开启/关闭白光补光灯
- 远程手动触发警笛声音威慑
- 人形侦测 / 区域入侵侦测 / 异动侦测开关
- 自定义绘制侦测报警区域（手指绘制多边形）
- 异常事件消息推送开关

### 五、录像与回放控制
- TF 卡本地存储：全天持续录像 / 移动侦测触发录像
- 日历 + 24 小间时间轴双模式检索
- 回放任意时间段录像
- 重要录像片段下载保存至手机

### 六、设备远程管理控制
- 局域网随时随地查看与控制（手机与摄像头需同 WiFi）
- 多设备多画面同屏预览管理
- 远程重启摄像头
- 设备在线固件检测与升级
- 设备状态自检与基础功能调试
- **局域网设备探测**：ONVIF WS-Discovery + /24 端口扫描，发现后即可预览 / 云台 / 语音对接

### 七、内网穿透与智能选路（v2 新增）
- **多通道自由配置**：在首页菜单「内网穿透」里可任意新增/编辑/删除 frp / ngrok / 端口转发 / ZeroTier 等公网入口通道，每条通道带「启用开关」（即连接状态开关），关掉的通道不会被选用。
- **自动选路**：进入预览时 App 读取当前 WiFi SSID，按以下优先级自动选择连接地址：
  1. 当前 WiFi 与设备绑定的「内网 SSID」相同 → 走**内网**直连（最快）。
  2. 否则若设备绑定了穿透通道 → 走**穿透**通道的公网 host:port。
  3. 否则若设备自带公网地址（DDNS/端口转发）→ 走**公网**。
  4. 兜底用设备原始地址直连。
- 预览画面顶部实时显示当前路由标签（内网·xxx / 穿透·xxx / 公网·xxx），一眼看清走的哪条路。
- **防卡死 + 超时重连**：RTSP 启动 12 秒未进入播放、或播放中 20 秒无心跳均判定为超时，累计超时次数显示在画面右下角；自动退避重连最多 3 次，仍失败则弹出「重连」按钮交由用户手动恢复，避免无脑重试把 App 卡死。
- 设备绑定：添加设备时或在「设备设置 → 路由/穿透设置」里，可填写设备所在内网 SSID、绑定一条穿透通道、填写设备公网地址。

> ⚠️ 读取 WiFi SSID 在 Android 12+ 需要精确定位权限，App 会在进入预览时申请；未授权时按「不在内网」处理，走公网/穿透。

## 如何添加设备

> 添加设备界面会根据你选的厂商，自动显示「填什么」提示卡片，照着填就行。

### TP-Link Tapo（C200 / C310 / C320WS / C325WB / C420 等）
1. 设备类型选 **TP-Link Tapo**
2. **IP 地址**：Tapo 官方 App → 摄像头 → 设备信息 → IP 地址 （或查路由器后台）
3. 端口 `554`，RTSP 路径 `stream0`（主码流改 `stream1`）
4. **用户名**：固定 `admin`（App 会帮你预填）
5. **密码**：⚠️ Tapo 官方 App 配网时给摄像头设置的「设备密码」（不是 WiFi 密码！）
6. 云台款勾「支持云台」，ONVIF 端口填 `2020`
7. 测试连接 → 保存

### 乐橙 Imou（TP2 / TP2E / TA3 / DB50 / DB60 等）
1. 设备类型选 **乐橙**
2. **IP 地址**：乐橙 App → 设备设置 → 设备信息 → IP 地址 （或查路由器后台）
3. 端口 `554`，RTSP 路径常见是 `cam/realmonitor?channel=1&subtype=0` 或 `stream0`
4. **用户名**：固定 `admin`（App 会帮你预填）
5. **密码**：⚠️ 摄像头贴纸上的 admin 密码，或乐橙 App「摄像头密码」里改过的那个（不是 WiFi 密码！不是乐橙云账号密码！）
6. 乐橙全部支持 ONVIF → 勾「支持云台」，ONVIF 端口填 `80`
7. 测试连接 → 保存

### 通用 ONVIF（海康/大华/萤石/小米等支持 ONVIF 的第三方）
1. 设备类型选 **通用ONVIF**
2. IP / 端口 / RTSP 路径查该摄像头型号官方文档
3. 用户名/密码填摄像头 ONVIF 账户（通常默认 admin/admin）
4. 云台款勾「支持云台」并填 ONVIF 端口（常见 80/8080/2020）
5. 测试连接 → 保存

## 项目结构

```
app/src/main/java/com/cameramanager/app/
├── CameraApp.kt                 # Application 入口
├── data/                        # Room 数据库、DAO、Repository、数据模型
├── rtsp/                        # RtspPlayer (libVLC，含超时看门狗+自动重连) + OnvifClient (SOAP)
├── net/                         # NetworkScanner 局域网扫描 + NetworkRouter 内网/公网/穿透选路
├── audio/                       # VoiceIntercom 双向语音
├── vendor/                      # 统一厂商 API 抽象（无需开发者凭证）
│   ├── CameraVendorApi.kt       #   统一接口 + 能力集
│   ├── CameraController.kt      #   高层门面（能力感知）
│   ├── OnvifVendorApi.kt        #   通用 ONVIF 实现
│   ├── TapoApi.kt               #   TP-Link Tapo 实现 (encrypt_type 3 + AES securePassthrough)
│   └── ImouApi.kt               #   乐橙 Imou 实现 (复用 ONVIF，无需 appId/appSecret)
├── service/                     # 前台服务：流式/侦测/告警/悬浮窗
├── ui/                          # 各界面 Activity + Adapter + ViewModel
│   ├── MainActivity / MultiPreviewActivity
│   ├── preview/                 #   实时预览 + 云台（接入选路 + 超时重连）
│   ├── scan/                    #   局域网扫描 + 手动添加（带厂商填写指引卡片 + 路由/穿透绑定）
│   ├── playback/                #   回放 + 时间轴
│   ├── settings/                #   设置 + 侦测规则 + 告警日志 + 路由/穿透设置
│   ├── tunnel/                  #   内网穿透通道管理（增删改查 + 启用开关）
│   ├── voice/                   #   语音对讲
│   ├── detection/               #   侦测区域绘制
│   └── manage/                  #   设备管理 (重启/固件/自检)
└── util/                        # 权限、存储、PTZ 方向等工具
```

## 构建与运行

### 环境要求
- Android Studio Hedgehog (2023.1) 或更高
- JDK 17
- Android SDK 34（compileSdk 34 / minSdk 24 / targetSdk 34）
- Gradle 8.5（项目已自带 wrapper）

### 配置 SDK 路径
在项目根目录创建 `local.properties`：
```
sdk.dir=/path/to/Android/Sdk
```

### 构建
```bash
./gradlew assembleDebug
```
产物：`app/build/outputs/apk/debug/app-debug.apk`

### 厂商凭证（不需要！）
- **TP-Link Tapo**：无需任何 key/secret。App 内部用 Tapo 官方 `encrypt_type 3` 握手（SHA256 摘要 + AES securePassthrough），和官方 Tapo App 在同一 WiFi 下控制用的是同一套协议。
- **乐橙 Imou**：无需任何 appId/appSecret。App 内部走局域网 ONVIF，和乐橙官方 App 在 LAN 内控制用的是同一个 SOAP 通道。
- **通用 ONVIF**：填 IP + 摄像头本身的 ONVIF 账户即可。

## 技术栈
- **预览**：libVLC (RTSP 实时低延迟) + ExoPlayer (本地回放)
- **云台/能力**：ONVIF Profile S/T (SOAP/WS-UsernameToken)
- **厂商**：Tapo 本地 RPC（encrypt_type 3 SHA256 摘要 + AES-CBC securePassthrough）/ 乐橙复用 ONVIF（无云 OpenAPI 依赖）
- **存储**：Room (设备/规则/告警/录像)
- **UI**：Material Components + ViewBinding + ViewModel/LiveData + 协程
- **服务**：WorkManager + 前台服务 (流式/侦测/悬浮窗)

## 权限说明
| 权限 | 用途 |
|------|------|
| INTERNET / ACCESS_WIFI_STATE | RTSP 预览、ONVIF/厂商 API |
| CHANGE_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE | ONVIF 局域网发现、WiFi 状态读取 |
| ACCESS_FINE_LOCATION | 读取当前 WiFi SSID 用于内网/穿透自动选路（Android 12+ 强制） |
| RECORD_AUDIO | 双向语音对讲 |
| CAMERA | 对讲时本地采集 |
| POST_NOTIFICATIONS | 告警推送 |
| FOREGROUND_SERVICE | 预览/录像/侦测后台保活 |
| SYSTEM_ALERT_WINDOW | 悬浮窗预览 |
| READ_MEDIA_VIDEO/IMAGES | 读取录像/截图 |

## 说明
本沙箱环境无 Android SDK 与外网，无法在此完成 `assembleDebug` 编译验证；请在装有 Android Studio + SDK 的环境中构建。代码层面已做完整自检（导入、ViewBinding ID、数据类字段一致性、Room 迁移 schema、strings 资源、Listener 实现者一致性）。v2 新增内网穿透选路、超时重连等逻辑已逐文件核对。接入真实设备前无需替换任何内置密钥。
