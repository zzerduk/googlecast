# 纯 WebRTC 指定应用投音控制台与交互重构方案

移除冗余的 AAC/HTTP 采集链路及默认系统 AUDIO/VIDEO 分类与硬编码包名预设，将应用精简重构为**纯 WebRTC 跨空间指定应用投音与定向静音控制台**，并打造带真实应用图标、多空间切换与即时搜索的**沉浸式底部抽屉（Modal Bottom Sheet）应用选择器**以及可折叠的真机门禁诊断面板。

## User Review & Critical Decisions

> [!IMPORTANT]
> 以下为您在第一阶段确认的三项核心重构决策。确认后我们将立即执行代码重构、清理无用依赖与单元测试验证。

- **Confirmed Decision 1（移除默认分类与预设映射，统一目标列表）**：彻底删除系统 `AUDIO` / `VIDEO` 分类标签页以及 `com.metrolist.music`、`tv.danmaku.bili` 等硬编码补充清单与预设禁采标签；主界面采用统一的“已添加目标应用”卡片列表，用户完全自主管理要投音并静音的应用实例。
- **Confirmed Decision 2（彻底移除 AAC 采集与传输链路）**：删除 AAC 编码器、Ktor HTTP 流媒体服务、mDNS 局域网扫描、手动 IP 音箱添加弹窗以及传输模式切换器（WebRTC vs AAC），仅保留高可靠、低延迟的 **WebRTC 系统内部音频捕获与官方 Cast SDK 路由**。
- **Confirmed Decision 3（重构主控制台布局与底部抽屉式导入体验）**：
  - **应用导入/选择交互**：采用带应用真实图标（Drawable 渲染）、多空间分段切换（主空间 / 工作资料 / 克隆资料）、实时搜索框、一键勾选/移除及折叠式手动包名兜底输入的 **Material 3 Modal Bottom Sheet**。
  - **主界面结构**：重构为清爽的三段式核心工作台（**顶部连接与投音主控台** → **Shizuku 状态与目标应用管理区** → **底部可折叠真机门禁与 WebRTC 实时遥测抽屉**）。

---

## 1. Overview & Core Concept

- **What It Does**：
  - 专注于 **WebRTC 48kHz 立体声 PCM16** 投音与 **Shizuku 会话级零增益 `VolumeShaper` 定向静音**。
  - 用户通过官方 Cast 按钮连接音箱，在可视化底部抽屉中从主空间、工作资料或克隆资料勾选目标应用，一键开启“远端音箱播放 + 本机目标应用无损静音”。
- **Target Audience / Persona**：需要干净、直观、无冗余配置干扰的多空间 Android 音频投射用户。
- **Key Value**：
  - **零冗余操作流**：去除不再使用的 AAC 码率/预突发缓冲滑块、mDNS 扫描与硬编码分类预设，打开应用即可直接完成“选音箱 → 选目标 App → 开始投音”。
  - **直观的跨空间可视化导入**：直接在底部抽屉浏览各空间已安装应用的图标、名称、包名与实时解析出的完整 UID，支持秒级搜索与多选。

---

## 2. User Experience & Visual Design

- **Key User Flows**：
  1. **Step 1 — 连接音箱与环境就绪（Top Console Header）**：
     - 顶部主控卡片集成连接状态胶囊（`IDLE` / `CAPTURING` / `NEGOTIATING` / `SENDING`）、实时音频电平动态指示条（dBFS 仪表）、官方 Google Cast 路由选择按钮以及醒目的主操作按钮（开始投音 / 停止投音）。
     - 内嵌紧凑型 Shizuku Shell 状态条：未授权或未连接时展示一键“连接 / 授权 Shizuku”胶囊按钮；就绪时显示翠绿色 `Shell Ready (13.1.5)` 状态。
  2. **Step 2 — 管理目标应用（Unified Target Apps Card）**：
     - 主界面展示用户已添加的目标应用实例列表；每个卡片条目展示**真实应用图标**、应用名称、所属空间彩色徽标（`主空间` / `工作资料` / `克隆资料`）、包名与完整 UID、投音启用开关（Checkbox）及移除按钮。
     - 当列表为空时，展示友好的空状态插画引导与醒目的“+ 添加 / 导入目标应用”按钮；在授权或投音进行中，列表自动进入只读锁定态并显示锁形提示。
  3. **Step 3 — 底部抽屉可视化导入（Visual App Picker Bottom Sheet）**：
     - 点击“+ 添加应用”弹出高度自适应的模态底部抽屉（`ModalBottomSheet`）：
       - **顶部固定栏**：标题、已选计数徽章与圆角即时搜索框（支持按应用名或包名过滤）。
       - **空间切换栏（Profile Segmented Chips）**：一键切换查看 `全部空间`、`主空间`、`工作资料`、`克隆资料` 下的已安装应用。
       - **已安装应用列表**：每行展示应用图标、应用名称、空间徽标、包名、完整 UID 以及勾选状态（支持排除系统底层非启动项，并支持切换“显示系统应用”开关）；若应用声明了系统 `allowAudioPlaybackCapture="false"` 则自动显示禁采提示。
       - **折叠式高级手动包名输入**：抽屉底部提供可展开的“手动输入包名 + 空间选择”快捷栏，满足特殊跨空间包名直接录入需求。
  4. **Step 4 — 可折叠真机门禁与诊断抽屉（Collapsible Diagnostics & Gate Telemetry）**：
     - 默认以紧凑的一行状态摘要卡片呈现（显示当前 `getMutedBy()` 确认轨数、新轨静音延迟 `ms` 与发送码率），点击即可平滑展开查看完整白名单 UID、混音器掩码、静音前后 dBFS 对比、WebRTC 传输统计与协议日志。
- **Visual Identity & Theme**：
  - **Aesthetic Direction**：专业级深色音频工作室（Dark Studio Console）风格，层次分明、克制精炼。
  - **Color Palette & Mood**：深邃蓝黑背景（`StudioBackground`）搭配高对比度卡片容器（`StudioSurface` / `StudioSurfaceVariant`），电光青（`CyanAccent`）标识核心交互与已选目标，翡翠绿（`EmeraldLive`）标识 WebRTC 正在发送与混音层静音确认通过，珊瑚红（`ErrorCoral`）突出异常或禁采告警。
  - **Typography & Hierarchy**：标题与交互正文使用 `Space Grotesk`，UID、包名、dBFS 与毫秒级延迟指标统一使用 `JetBrains Mono` 等宽字体。

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1：彻底移除 AAC/HTTP 与 Ktor 传输栈，统一为单一 WebRTC 管道**
  - *Chosen Approach*：删除 AAC 编码器、Ktor CIO HTTP 服务端、CastV2 裸 TLS 客户端及 mDNS 扫描器，并注释掉不再使用的 Ktor 依赖以减小 APK 体积、加快构建速度。
  - *Why*：WebRTC + 官方 Cast SDK 已能稳定承担全部指定应用投音需求；移除双模式切换与大量 AAC 调参卡片后，代码复杂度与界面视觉噪音大幅下降。
- **Decision 2：以用户自主导入的统一列表替代系统 AUDIO/VIDEO 分类与硬编码包名补充**
  - *Chosen Approach*：移除 `TargetAppCategory` 与 `SupplementaryAppCatalog`，在 `CaptureTargetRepository` 中直接维护持久化的 `importedTargets`（已添加的目标池）与 `selectedTargets`（本次投音勾选激活的子集）。
  - *Why*：不再依赖系统可能不准确的 `ApplicationInfo.category` 或硬编码特定第三方包名；用户通过可视化应用选择器添加一次后即持久保留在主界面，后续可随时勾选单选或多选组合投音。
- **Decision 3：保留全部安全采集与 Shizuku 会话静音门禁保障**
  - *Chosen Approach*：完整保留 `(packageName, userSerialNumber)` 持久化、授权时冻结快照、启动前二次解析完整 UID 与共享 UID 冲突拦截、3 秒 clock-time 租约与 1 秒续期、专属包络 ID 的零增益 `VolumeShaper`、`getMutedBy()` 混音层确认以及不等待 native HAL 的立即恢复机制。
  - *Why*：在大幅简化 UI 与传输模式的同时，确保跨空间隔离采集与会话级无损静音的核心可靠性与真机门禁指标不受任何削弱。

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                      Redesigned UI Layer (Jetpack Compose)                    │
│  ┌─────────────────────────────────────────────────────────────────────────┐  │
│  │ 1. Top WebRTC Cast Console Card                                         │  │
│  │    • Live Stage Pill + dBFS Meter + Official Cast Button + Start/Stop   │  │
│  │    • Integrated Shizuku Shell Status & One-Tap Permission Action        │  │
│  ├─────────────────────────────────────────────────────────────────────────┤  │
│  │ 2. Unified Target Apps Card                                             │  │
│  │    • Added Target Instances (App Icon, Label, Profile Badge, UID, Toggle│  │
│  │    • Opens Visual App Picker Bottom Sheet (+ Add / Manage Apps)         │  │
│  ├─────────────────────────────────────────────────────────────────────────┤  │
│  │ 3. Collapsible Gate & WebRTC Diagnostics Card                           │  │
│  │    • Summary Header (Muted Count, Latency ms, WebRTC Sending Status)    │  │
│  │    • Expandable Drawer (Full UIDs, getMutedBy Mask, dBFS Delta, Logs)   │  │
│  └────────────────────────────────────┬────────────────────────────────────┘  │
│                                       │                                       │
│  ┌────────────────────────────────────▼────────────────────────────────────┐  │
│  │ Visual App Picker ModalBottomSheet                                      │  │
│  │  • Profile Filter Tabs (All / Primary / Work / Clone) + Search Bar      │  │
│  │  • Launchable/System Filter + Real App Icon Loader + Manual Pkg Input   │  │
│  └────────────────────────────────────┬────────────────────────────────────┘  │
└───────────────────────────────────────┼───────────────────────────────────────┘
                                        │
                                        ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                     Coordination & Persistence Layer                          │
│  ┌────────────────────────────────────┐  ┌─────────────────────────────────┐  │
│  │ CaptureTargetRepository (DataStore)│  │ ShizukuSessionMuteController    │  │
│  │  • Imported & Selected Targets     │  │  • Multi-Profile App Scanner    │  │
│  │  • Frozen Snapshot at Auth         │  │  • 1s Renewal / 3s Clock Lease  │  │
│  │  • Full UID & Shared-UID Validator │  │  • Non-Blocking Instant Restore │  │
│  └─────────────────┬──────────────────┘  └────────────────┬────────────────┘  │
└────────────────────┼──────────────────────────────────────┼───────────────────┘
                     │                                      │
                     ▼                                      ▼
┌────────────────────────────────────────┐ ┌────────────────────────────────────┐
│   AudioCastCaptureService (WebRTC Only)│ │ ShizukuAudioMuteUserService (Shell)│
│  • PlaybackCaptureConfigBuilder (UIDs) │ │  • IPackageManager / IUserManager  │
│  • WebRtcAudioSession & AudioCapture   │ │  • Dedicated VolumeShaper (0-Gain) │
│  • Bounded 3s Native Cleanup Isolation │ │  • getMutedBy() Mixer Confirmation │
└────────────────────────────────────────┘ └────────────────────────────────────┘
```

### Data Model & State

- **`CaptureTarget(packageName: String, userSerialNumber: Long)`**：
  - 唯一标识特定空间下的应用实例；`DataStore` 持久化 `importedTargets`（主界面展示的已添加应用列表）与 `selectedTargets`（当前勾选参与投音与静音的目标集合）。
- **`InstalledAppOption`（选择器模型）**：
  - 包含 `target: CaptureTarget`、`appLabel: String`、`profileType: ProfileType`、`profileLabel: String`、`resolvedUid: Int?`、`sharedUidPackages: List<String>`、`isSystemApp: Boolean`、`isCaptureRestrictedByManifest: Boolean`、`statusReason: String?`。
  - 应用图标通过客户端 `LauncherApps` / `PackageManager` 按 `(packageName, UserHandle)` 动态加载并缓存，确保主界面与底部抽屉流畅展示高分辨率图标。

### Interactive Component & State Mapping

- **主界面目标列表交互**：
  - 用户在底部抽屉中勾选任意应用后，该应用自动加入主界面的“已添加目标应用”卡片并默认开启投音勾选；在主界面可直接通过开关临时启用/禁用某个已添加目标，或点击删除图标将其从列表中移除。
- **底部抽屉搜索与过滤**：
  - 默认展示各空间具有启动入口（或用户空间常见应用）的已安装应用，并提供“包含系统组件”切换开关；搜索框支持按应用名称、包名或 UID 实时过滤；底部折叠栏支持直接输入包名添加尚未列出的跨空间实例。
- **可折叠诊断抽屉**：
  - 默认折叠以保持主界面极简清爽；当发生错误、门禁告警或用户主动点击展开时，完整展示 `getMutedBy()` 混音层掩码、新轨 `≤100ms` 确认延迟、静音前后 dBFS 电平及实时 WebRTC 协议日志。
