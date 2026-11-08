# 投音备选模式、多组目标组合与音箱控制方案

在保留现有纯 WebRTC 48kHz 立体声 PCM16 采集管线与深色工作室（Dark Studio Console）视觉风格的基础上，新增 **手机输出模式选择（定向静音 / 整体媒体静音 / 仅采集不静音）**、**无 Shizuku 公开 API 独立目标解析与遗留音量安全恢复**、**内嵌于目标卡片顶部的多组命名快捷组合（TargetPreset）**，以及**独立的 Cast 音箱音量与静音控制卡片**。

## User Review & Critical Decisions

> [!IMPORTANT]
> 以下为您在第一阶段确认的三项核心产品与交互决策，确认后我们将立即推进编码实现与单元/Robolectric 测试验证。

- **Confirmed Decision 1（无 Shizuku 启动弹窗双备选开放与兼容门禁）**：当 Shizuku 不可用或未授权时，点击开始投音弹出备选模式选择弹窗，同时提供 **“整体媒体静音（全局 STREAM_MUSIC 归零）”** 与 **“仅采集不静音（本机与音箱同时发声）”** 两种备选并清晰说明各自影响范围；同時支持在本机验证发现整体静音导致回采失效时禁用“整体媒体静音”选项并展示原因说明，仅保留“仅采集不静音”。
- **Confirmed Decision 2（目标应用卡片顶部内嵌快捷组合胶囊栏）**：多组命名目标组合（`TargetPreset`）直接内嵌在主界面“已添加目标应用”卡片顶部的横向快捷胶囊栏（Chip Row），提供“+ 保存当前选择”入口、一键切换组合勾选以及单组删除操作；投放或授权期间自动随目标锁一同禁用。
- **Confirmed Decision 3（独立音箱控制卡片 + 强杀遗留音量启动弹窗提示）**：
  - **独立音箱控制卡片**：在主界面顶部主控卡下方新增独立的 **“音箱音量与静音控制”** 卡片，提供 0–100% 音量滑块（松手提交）与独立静音开关，直接调用官方 Cast SDK 的 `CastSession` 设备音量接口并通过 `Cast.Listener.onVolumeChanged()` 实时同步外部调节。
  - **强杀遗留音量恢复弹窗**：当检测到上次“整体媒体静音”会话因进程强杀遗留了未恢复的音量快照时，在应用重开时弹出模态确认弹窗（**“恢复原媒体音量” / “保留当前设置”**），绝不后台擅自写回覆盖用户当前音量。

---

## 1. Overview & Core Concept

- **What It Does**：
  - 将 Shizuku 从“所有投音模式的强制启动门槛”解耦为“会话级定向静音增强能力”：Shizuku 就绪时默认使用 **`SHIZUKU_SESSION_MUTE`（定向静音）**；Shizuku 未运行或未授权时，用户可在启动弹窗中明确选择 **`GLOBAL_MEDIA_MUTE`（整体媒体静音）** 或 **`CAPTURE_ONLY_UNMUTED`（仅采集不静音）**。
  - 提供基于 Android 公开 API（`LauncherApps`、`UserManager`、`PackageManager`）的独立目标解析器，修正以列表下标推断空间身份的缺陷，严格校验可访问空间、完整 UID 与共享 UID 完备性。
  - 支持将常用多空间目标组合保存为命名的 `TargetPreset`，并支持在主界面独立卡片中实时调节已连接 Cast 音箱的物理音量与静音状态。
- **Target Audience / Persona**：在不同设备环境（已开启 Shizuku 或不便开启 Shizuku 的日常场景）下进行多应用/跨空间音频投射，且希望快速切换常用应用组合并直接控制远端音箱音量的高频用户。
- **Key Value**：
  - **全场景可投音**：即便未启动 Shizuku，也能在明确知晓影响范围的前提下通过公开 API 完成可验证空间的目标 UID 白名单采集。
  - **安全的声音生命周期**：无论是定向模式通过专属 ID 的 `VolumeShaper.Operation` 真正终止包络（移除原先誤用 `REVERSE` 导致的全零曲线反向仍为零的问题），还是整体媒体静音在停止/失败/断线时优先恢复原媒体音量并尊重用户中途手动调高音量的行为，均确保本机声音安全可控。

---

## 2. User Experience & Visual Design

- **Key User Flows**：
  1. **Flow 1 — 输出模式决策与启动弹窗（PhoneOutputMode Selection）**：
     - 当 Shizuku 处于 `READY` 状态时，点击“开始投音”默认冻结为 `SHIZUKU_SESSION_MUTE`（定向静音）直接进入授权与启动流程。
     - 当 Shizuku 处于 `UNAVAILABLE`、`PERMISSION_REQUIRED`、`CONNECTING` 或 `ERROR` 时，点击“开始投音”弹出 **“选择手机端声音输出模式”** 模态弹窗：
       - 展示当前 Shizuku 未就绪原因，并提供快捷“去授权/刷新 Shizuku”入口。
       - 选项 A：**整体媒体静音（Global Media Mute）** — 说明“将手机系统媒体音量（STREAM_MUSIC）临时归零，所有媒体应用在手机端均静音；停止或异常退出时自动恢复原音量”（若标记了本机整体静音回采不兼容，则置灰禁用并展示原因）。
       - 选项 B：**仅采集不静音（Capture Only）** — 说明“仅按 UID 白名单回采目标应用音频发送至音箱，不修改手机音量，手机与音箱将同时发声”。
     - 用户确认某备选模式后，该模式随本次启动请求立即冻结（`frozenOutputMode`），投放期间绝不自动降级或切换模式；若定向静音中途失效，立即停止并恢复本机声音，由用户重新选择备选模式后重试。
  2. **Flow 2 — 强杀遗留媒体音量恢复弹窗（Crash/Kill Recovery Dialog）**：
     - 若上次使用 `GLOBAL_MEDIA_MUTE` 期间应用被强杀，重开应用时检测到持久化快照且当前媒体音量仍处于静音/零值，立即弹出 **“检测到未完成的整体媒体静音记录”** 弹窗，展示记录的原媒体音量格数与原静音状态，提供 **“恢复原音量”** 与 **“保留当前设置”** 两个按钮；用户任选其一后清除遗留快照。
  3. **Flow 3 — 独立音箱音量与静音控制卡片（Cast Speaker Control Card）**：
     - 位于顶部主控卡与目标应用卡片之间，包含：
       - **标题与状态**：显示当前连接音箱名称、实时音量百分比徽章（`0%–100%`）及独立静音状态指示。
       - **交互控件**：左侧/右侧配置独立静音切换按钮（`Switch` / `IconToggleButton`），中间为 `0–100%` 连续滑块（拖动时实时预览百分比，**松手 `onValueChangeFinished` 时提交** `CastSession.setVolume`，且调节音量不自动解除音箱静音）。
       - **状态同步与容错**：未连接音箱或正在连接时控件禁用；连接成功后立即读取 `CastSession.volume` 与 `isMute`，并通过 `Cast.Listener.onVolumeChanged()` 实时同步其他客户端或物理按键的音量变更；单次调节失败仅在卡片底部展示轻量错误提示（`controlError`），不终止正常 WebRTC 投音。
  4. **Flow 4 — 目标应用卡片内嵌快捷组合栏（TargetPreset Bar）**：
     - 在“已添加目标应用”卡片顶部内嵌横向快捷组合区：
       - **当前组合胶囊列表**：每个已保存的 `TargetPreset` 显示组合名称与包含的目标数量（如 `音乐常听 (2)`），点击即用该组合替换当前勾选集合（`selectedTargets`），右侧附带删除小图标（删除组合仅移除预设，不删除已导入的 App）。
       - **“+ 保存当前选择”按钮**：当 `selectedTargets` 非空且未锁定时可点击，弹出轻量输入框输入组合名称（校验非空且不与已有组合重名）。
       - **失效目标保留与提示**：若组合中包含当前不可访问空间、未安装或无法核验身份的目标，仍保留在列表中并以醒目原因标签说明（不静默丢弃、不猜 UID）。
  5. **Flow 5 — 四维状态与通知清晰展示（Granular Status & Notification）**：
     - 顶部主控卡与前台常驻通知同步显示当前冻结的实际输出模式（`定向静音` / `整体媒体静音` / `仅采集不静音`），并以清晰的状态指示区分四项独立事实：
       1. **音箱已连接**（`castStatus.isConnected`）
       2. **流正在发送**（`webRtcStage == SENDING`）
       3. **已有有效音频**（`currentRmsDb > -59.5f`；当处于零电平时，温和提示“当前回采电平为静音，可能目标应用未播放、处于暂停或受系统/第三方回采限制”，不直接武断断言第三方禁采）
       4. **手机已静音**（按模式区分：`SHIZUKU_SESSION_MUTE` 要求 `leaseActive` 且活跃目标轨均经 `getMutedBy()` 确认；`GLOBAL_MEDIA_MUTE` 要求系统媒体音量已归零生效；`CAPTURE_ONLY_UNMUTED` 明确显示“本机未静音”）。
- **Visual Identity & Theme**：
  - 延续深色音频工作台风格（`StudioBackground` + `CyanAccent` + `EmeraldLive` + `ErrorCoral`），标题采用 `Space Grotesk`，音量百分比、UID、dBFS 与状态掩码采用 `JetBrains Mono`。

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1：新增 `PhoneOutputMode` 并在启动请求时冻结，移除“正在投放”对 Shizuku 的硬编码绑定**
  - *Chosen Approach*：定义 `PhoneOutputMode { SHIZUKU_SESSION_MUTE, GLOBAL_MEDIA_MUTE, CAPTURE_ONLY_UNMUTED }`。在 `AudioPipelineTelemetry` 中将 `isStreaming` 解耦为只反映当前冻结模式已就绪且 `webRtcStage == WebRtcStage.SENDING`，同时单独提供 `isPhoneMuted`、`hasValidAudioSignal` 与 `activeOutputMode` 字段。
  - *Why*：保证三种模式在启动后各司其职、互不串扰，且 UI 与通知能诚实反映“流已发送”、“是否有声”、“本机是否已静音”四个独立维度。
- **Decision 2：独立构建无 Shizuku 公开 API 目标解析器，杜绝下标猜测空间身份**
  - *Chosen Approach*：
    - 当 Shizuku `READY` 时，可继续使用 Shell `IPackageManager` / `IUserManager` 枚举与校验全空间目标；
    - 当无 Shizuku 时，通过公开 API `UserManager.userProfiles` / `LauncherApps.profiles` 获取真实 `UserHandle`，用 `UserManager.getSerialNumberForUser(userHandle)` 精确匹配 `target.userSerialNumber`，用 `UserManager.isUserRunning(userHandle)` 与 `isQuietModeEnabled(userHandle)` 校验空间运行态，仅通过 `LauncherApps.getApplicationInfo(pkg, 0, userHandle)`（针对当前进程可访问的 Profile）获取真实 `ApplicationInfo.uid`。
    - 对于当前进程无法通过 `LauncherApps` 访问的子空间/克隆空间、无法枚举同 UID 下全部共享包名的跨空间目标，保持该目标在界面可见并明确标记不可核验原因，绝不用列表下标伪造 `userId` 或猜测 `uid = userId * 100000 + appId`。
    - 备选模式下应用选择器仅展示可启动应用（`LauncherApps.getActivityList`），不将本机 `getInstalledApplications` 的全部非播放底层组件混入默认目录。
  - *Why*：严格遵守零猜测、不静默丢目标、不扩大采集范围的安全边界。
- **Decision 3：整体媒体静音持久化快照与用户调音尊重策略 + 定向模式 `VolumeShaper` 真正撤除修复**
  - *Chosen Approach*：
    - **整体媒体静音（`PhoneGlobalMediaMuteController`）**：声明普通权限 `MODIFY_AUDIO_SETTINGS`。启动静音前，先将当前 `AudioManager.getStreamVolume(STREAM_MUSIC)` 与 `isStreamMute(STREAM_MUSIC)` 同步写入 DataStore/SharedPreferences 持久化快照，再将媒体音量设为 `0`。在正常停止、启动失败、Cast 断线或投屏授权撤销时，**先立即执行手机声音恢复，再等待底层 WebRTC 清理**：若当前系统媒体音量仍为 `0`，则恢复为快照保存的原音量与原静音状态并清除快照；若用户在投放期间已手动将系统媒体音量调为非零（`currentVolume > 0`），则保留用户的新音量设置并清除快照。
    - **定向模式包络撤除修复（`ShizukuAudioMuteUserService`）**：全零曲线 `floatArrayOf(0f, 0f)` 使用 `VolumeShaper.Operation.REVERSE` 时增益依然是 `0f`。修复为按本服务专属 `DEDICATED_SHAPER_ID` 构造替换/终止操作（使用 `VolumeShaper.Configuration.OPTION_PLAY_CREATE_IF_NEEDED` / 专属 ID 替换为单位增益 `1f, 1f` 并调用 `VolumeShaper` 的 `close()` / `replace(DEDICATED_SHAPER_ID, ...)` 终止本服务创建的 shaper），确保释放租约或停止时目标播放器立即恢复 `1.0` 原声增益且 `getMutedBy()` 掩码清零。
  - *Why*：彻底解决全零曲线 `REVERSE` 无法恢复声音的缺陷，并兼顾整体静音在正常退出、中途手动调音与强杀重开全生命周期的安全性。

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌───────────────────────────────────────────────────────────────────────────────────┐
│                         Jetpack Compose Console UI Layer                          │
│  ┌─────────────────────────────────────────────────────────────────────────────┐  │
│  │ 1. Top WebRTC Cast Console Header Card                                      │  │
│  │    • Frozen PhoneOutputMode Badge + 4-State Pills (Connected/Sending/       │  │
│  │      ValidAudio/PhoneMuted) + dBFS Meter + Cast Route + Start/Stop          │  │
│  ├─────────────────────────────────────────────────────────────────────────────┤  │
│  │ 2. Standalone Cast Speaker Control Card                                     │  │
│  │    • 0–100% Volume Slider (commit on release) + Independent Mute Toggle     │  │
│  │    • Real-time Cast.Listener.onVolumeChanged() Sync + Non-fatal Error Text  │  │
│  ├─────────────────────────────────────────────────────────────────────────────┤  │
│  │ 3. Unified Target Apps Card with Embedded TargetPreset Bar                  │  │
│  │    • Top Preset Chip Row (Select Preset / Delete Preset / + Save Selection) │  │
│  │    • Target Instances List (Retains unverified/inaccessible items w/ reason)│  │
│  ├─────────────────────────────────────────────────────────────────────────────┤  │
│  │ 4. Startup Mode Dialog & Crash-Recovery Volume Prompt Dialog                │  │
│  │    • Fallback Mode Dialog (Global Media Mute vs Capture-Only Unmuted)       │  │
│  │    • Leftover Global Mute Recovery Dialog (Restore Saved vs Keep Current)   │  │
│  └──────────────────────────────────────┬──────────────────────────────────────┘  │
└─────────────────────────────────────────┼─────────────────────────────────────────┘
                                          │
                                          ▼
┌───────────────────────────────────────────────────────────────────────────────────┐
│                      Session Coordination & Controllers                           │
│  ┌────────────────────────────────────┐  ┌─────────────────────────────────────┐  │
│  │ CaptureTargetRepository (DataStore)│  │ CastSdkSessionController (Main)     │  │
│  │  • Imported & Selected Targets     │  │  • CastSession Volume & Mute API    │  │
│  │  • Named TargetPreset Persistence  │  │  • Cast.Listener Volume Sync        │  │
│  │  • Public-API & Shizuku Resolvers  │  │  • Stale Session Generation Guard   │  │
│  └─────────────────┬──────────────────┘  └──────────────────┬──────────────────┘  │
│                    │                                        │                     │
│  ┌─────────────────▼──────────────────┐  ┌──────────────────▼──────────────────┐  │
│  │ PhoneGlobalMediaMuteController     │  │ ShizukuSessionMuteController        │  │
│  │  • Persist Pre-Mute Volume/Mute    │  │  • 3s Lease / 1s Renewal            │  │
│  │  • Respect User Non-Zero Override  │  │  • Dedicated Shaper ID Termination  │  │
│  │  • Leftover Crash Prompt State     │  │  • Instant Restore Before Teardown  │  │
│  └────────────────────────────────────┘  └─────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State

- **`PhoneOutputMode`**：
  - `SHIZUKU_SESSION_MUTE`（定向静音：Shizuku 会话级零增益包络，仅静音目标 UID）
  - `GLOBAL_MEDIA_MUTE`（整体媒体静音：`AudioManager.STREAM_MUSIC` 归零，影响整机媒体声）
  - `CAPTURE_ONLY_UNMUTED`（仅采集不静音：不改动本机任何音量，手机与音箱同播）
- **`TargetPreset(val name: String, val targets: Set<CaptureTarget>)`**：
  - 复用 `capture_targets_store` DataStore 持久化名称与 `packageName@userSerialNumber` 集合，绝不持久化固定 UID。
  - 规则：名称 trim 后非空且不重复（区分/忽略大小写重名校验）、`targets` 非空才允许保存；选择组合替换 `selectedTargets` 并自动确保组合内目标加入 `importedTargets`；删除组合不影响 `importedTargets`；投放或授权锁定时禁止保存、切换或删除组合。
- **`CastSdkSessionStatus` 扩展**：
  - 新增 `volume: Double = 0.0`（`0.0..1.0`）、`isMuted: Boolean = false`、`isVolumeControlAvailable: Boolean = false`、`volumeControlError: String? = null`。
- **`PendingGlobalMuteRecovery`（强杀遗留恢复模型）**：
  - 包含 `savedVolume: Int`、`maxVolume: Int`、`wasMuted: Boolean`、`currentVolume: Int`；当进程启动检测到持久化的未清理整体静音记录时生成，驱动启动弹窗由用户选择“恢复”或“保留”。

### Interactive Component & State Mapping

- **启动模式决策流（`ScreenCastViewModel` -> `ScreenCastAudioScreen`）**：
  - 点击“开始投音”时，若 `Shizuku` 已 `READY`，直接以 `PhoneOutputMode.SHIZUKU_SESSION_MUTE` 调用 `prepareCaptureRequest(mode)`。
  - 若 `Shizuku` 未 `READY`，置位 `showOutputModeDialog = true`；用户在弹窗中点击“整体媒体静音”或“仅采集不静音”后，调用 `confirmOutputModeAndPrepare(chosenMode)` 冻结 `frozenOutputMode = chosenMode` 并执行预检与系统投屏授权。
- **独立目标解析与校验流（`PublicApiTargetResolver` vs `ShizukuMuteBackend`）**：
  - 在 `SHIZUKU_SESSION_MUTE` 模式下，要求 Shizuku `READY` 并通过 Shell 核验跨空间目标与共享 UID。
  - 在 `GLOBAL_MEDIA_MUTE` 或 `CAPTURE_ONLY_UNMUTED` 模式下，若 Shizuku 未连接，则调用 `PublicApiTargetResolver`：
    1. 通过 `UserManager.userProfiles` / `LauncherApps.profiles` 获取真实 `UserHandle`，通过 `UserManager.getSerialNumberForUser(handle)` 建立精确的 `serialNumber -> UserHandle` 映射（严禁用列表下标 `idx` 充当 `serialNumber` 或计算 UID）。
    2. 对每个选中目标 `CaptureTarget(pkg, serial)`，若 `serial` 不在可访问的 `UserHandle` 映射中，标记为不可访问（原因：`"无 Shizuku 时无法访问或核验该子空间 (Serial $serial) 的应用身份"`）。
    3. 若空间未运行或开启了安静模式（`!userManager.isUserRunning(handle) || userManager.isQuietModeEnabled(handle)`），标记为不可用。
    4. 通过 `launcherApps.getApplicationInfo(pkg, 0, handle)` 获取真实 `ApplicationInfo` 与 `appInfo.uid`；校验 `isAudioPlaybackCaptureAllowed`。
    5. 共享 UID 校验：对主空间（当前进程所属 `Process.myUserHandle()`），通过 `packageManager.getPackagesForUid(uid)` 完整核验同 UID 包名是否全部被勾选；对非当前进程空间（如工作资料），由于公开 `PackageManager.getPackagesForUid` 仅查询当前进程用户或无法保证跨空间共享 UID 枚举完整性，若 `appInfo.sharedUserId != null`（或无法通过公开 API 完整核验共享 UID 归属），明确拒绝并给出原因，绝不猜测或扩大采集范围。
- **音箱音量与静音控制流（`CastSdkSessionController`）**：
  - 所有 Cast SDK 访问强制在主线程（`Looper.getMainLooper()`）执行。
  - `sessionConnected(session)` 时：向 `session.addCastListener(castListener)` 注册监听，读取 `session.volume` 与 `session.isMute` 更新 `_status`。
  - `castListener.onVolumeChanged()` 回调时：核对 `currentSession === session && session.isConnected`（忽略过期会话回调），实时同步最新 `volume` 与 `isMute` 到 `_status`。
  - `setDeviceVolume(targetVolume: Float)` 与 `setDeviceMuted(muted: Boolean)`：在主线程调用 `session.setVolume(clamped.toDouble())` / `session.isMute = muted`；若抛出 `IOException` 或 `IllegalStateException`，仅将错误写入 `_status.value.volumeControlError` 并记录日志，不触发 `stopCastingSession`。
  - `sessionDisconnected` / `endSession` 时：注销 `removeCastListener`，清空音量状态并禁用音箱控制卡片。
- **手机声音恢复与生命周期保障（`PhoneGlobalMediaMuteController` & `AudioCastCaptureService`）**：
  - `AudioCastCaptureService.stopCastingSession`、启动异常回滚、Cast 断线、`MediaProjection.Callback.onStop` 以及 `ViewModel.captureAuthorizationFailed` 中，第一步立即同步执行：
    - 若冻结模式为 `SHIZUKU_SESSION_MUTE`：调用 `ShizukuSessionMuteController.stopLeaseAndRestoreImmediately()`（内部通过专属 ID 真正关闭/移除 `VolumeShaper` 包络）。
    - 若冻结模式为 `GLOBAL_MEDIA_MUTE`：调用 `PhoneGlobalMediaMuteController.restorePhoneAudioIfNeeded(context)`（若当前媒体音量为 0 则恢复原音量，若已被用户调为非零则保留用户设置，并清除持久化标志）。
  - 随后再异步等待 `WebRtcAudioSession.stop()` 的底层清理，确保任何 native 阻塞都不会拖延手机声音恢复。
