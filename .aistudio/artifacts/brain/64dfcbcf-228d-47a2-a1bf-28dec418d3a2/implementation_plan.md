# Android 14 真机 RequestCode 报错修复与前台服务加固方案

修复 Android 14 真机点击“开始投屏”请求权限或录屏授权时触发的 `Can only use lower 16 bits for requestCode` 异常，并全面加固 Android 14 (API 34+) 真机上的运行时权限请求、MediaProjection 授权及前台服务（Foreground Service）生命周期兼容性。

## User Review & Critical Decisions

> [!IMPORTANT]
> 以下为您在第一阶段确认的修复范围与关键技术决策，确认后我们将立即执行代码修改与编译验证。

- **Confirmed Decision 1（触发场景定位）**：报错在点击“开始投屏”触发运行时权限请求（`RECORD_AUDIO` / `POST_NOTIFICATIONS`）或系统录屏授权弹窗（`MediaProjection`）时出现。
- **Confirmed Decision 2（修复范围）**：除彻底解决 16-bit `requestCode` 冲突外，一并检查并加固 Android 14 真机的前台服务类型校验与权限异常处理流。

---

## 1. Overview & Core Concept

- **问题根因分析**：
  - 当前主界面继承自 `FragmentActivity`（以支持 Google Cast `MediaRouteButton` 的路由选择弹窗），同时使用了现代 Jetpack Activity Result API（`rememberLauncherForActivityResult`）。
  - 新版 Activity Result 注册表会自动生成 32 位随机请求码（固定大于 `65536`，高 16 位非零），而旧版 Google Cast / MediaRouter 传递依赖引入的旧版 `FragmentActivity` 仍保留了传统的 `checkForValidRequestCode` 16 位掩码校验（`(requestCode & 0xffff0000) != 0`），导致发起权限或 `MediaProjection` 授权 Intent 时立即抛出 `IllegalArgumentException: Can only use lower 16 bits for requestCode`。
- **目标用户场景**：在 Android 14 及更高版本的物理真机上，稳定完成麦克风/通知权限授予、系统内部音频捕获授权（MediaProjection），并零崩溃启动 WebRTC 或 AAC/HTTP 音频投屏。
- **核心价值**：消除新旧 AndroidX 组件库传递依赖版本割裂导致的 16 位请求码限制报错，并确保 Android 14 严苛的前台服务类型（`mediaProjection` 与 `microphone`）及超时约束在真机上完全合规。

---

## 2. User Experience & Visual Design

- **Key User Flows（核心交互流）**：
  1. **点击开始投屏**：用户在主界面选择 WebRTC 或 AAC/HTTP 模式后点击“Start Audio Cast”。
  2. **运行时权限请求**：若尚未授予 `RECORD_AUDIO`（及 Android 13+ 的 `POST_NOTIFICATIONS`），系统正常弹出权限授权框，不再因 32 位 `requestCode` 崩溃或报错中断。
  3. **系统音频捕获授权（MediaProjection）**：权限通过后，正常拉起 Android 14 系统级“录制或投射内容”授权弹窗（支持单应用音频或整机音频）。
  4. **前台服务启动与状态反馈**：用户确认授权后，状态栏平稳展示常驻前台服务通知，主界面实时更新捕获状态与音频电平遥测；若用户拒绝授权或中途取消，顶部卡片清晰展示可恢复的错误提示并释放按钮锁定状态。
- **Visual Identity & Theme**：
  - 保持现有的 Material 3 科技感音频控制台视觉风格、动态状态胶囊（Live Pill）、圆角卡片与排版层级不变。
- **Interactive Feedback & Error Boundaries**：
  - 为权限启动器与录屏授权启动器统一增加异常保护边界；若系统 ROM 拦截或用户拒绝授权，立即重置请求中状态（`isRequestingCapture = false`）并在主卡片展示直观的中文/英文状态说明。

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1：升级并对齐 AndroidX Fragment 与 AppCompat 依赖版本**
  - *Chosen Approach*：显式声明并对齐新版 `androidx.fragment` 与 `androidx.appcompat` 依赖，使 `FragmentActivity` 直接接入 `ComponentActivity` 的现代 `ActivityResultRegistry` 通道。
  - *Why*：在现代 `androidx.fragment`（1.3.0+，采用最新稳定版）中，`FragmentActivity` 原生支持 `ActivityResultLauncher` 的 32 位请求码分发，既能彻底根除 `Can only use lower 16 bits for requestCode`，又能完整保留 Google Cast `MediaRouteButton` 所需的 `FragmentActivity` 上下文能力。
  - *Alternatives Considered*：将 `MainActivity` 降级为纯 `ComponentActivity` 会导致 Google Cast 官方 `MediaRouteButton` 在点击时无法弹出 `MediaRouteChooserDialogFragment`（Cast SDK 强依赖 `FragmentActivity`）。
- **Decision 2：Android 14 真机前台服务（Foreground Service）双类型与超时防卫加固**
  - *Chosen Approach*：
    1. 在前台服务启动流中，确保只要通过 `startForegroundService` 拉起服务，即使前置校验失败也先完成合规的 `startForeground` 或在启动服务前完成完备校验，彻底杜绝 Android 14 的 `ForegroundServiceDidNotStartInTimeException`。
    2. 在 Android 14+ 真机上，当 `RECORD_AUDIO` 权限已授予时，严格对齐 Manifest 声明的 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` 与 `FOREGROUND_SERVICE_TYPE_MICROPHONE` 服务类型位掩码，并在 `getMediaProjection()` 调用前完成前台提升。
  - *Why*：Android 14 (API 34) 对 `mediaProjection` 和 `microphone` 前台服务类型执行严格的运行时审计，必须在获取 `MediaProjection` 实例及启动 `AudioRecord` 之前完成对应类型的前台服务绑定。

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         MainActivity (FragmentActivity)                  │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │ Unified ActivityResultRegistry (Modern AndroidX Fragment/Activity) │  │
│  │  ├─ permissionLauncher (RequestMultiplePermissions - 32-bit safe)  │  │
│  │  └─ mediaProjectionLauncher (StartActivityForResult - 32-bit safe) │  │
│  └───────────────┬────────────────────────────────────┬───────────────┘  │
└──────────────────┼────────────────────────────────────┼──────────────────┘
                   │ 1. RECORD_AUDIO / NOTIFICATIONS    │ 2. MediaProjection
                   ▼                                    ▼
┌────────────────────────────────────┐   ┌─────────────────────────────────┐
│     ScreenCastViewModel            │   │    AudioCastCaptureService      │
│  ├─ prepareCaptureRequest()        │──▶│  ├─ promoteToForeground()       │
│  ├─ startAudioCastWithProjection() │   │  │   (MEDIA_PROJECTION | MIC)   │
│  └─ captureAuthorizationFailed()   │   │  ├─ getMediaProjection()        │
└────────────────────────────────────┘   │  └─ WebRTC / AAC AudioRecord    │
                                         └─────────────────────────────────┘
```

### Interactive Component & State Mapping

- **权限与授权启动器防卫（MainActivity）**：
  - 点击开始按钮后，通过现代 `ActivityResultRegistry` 发起 `permissionLauncher.launch(...)`，并在外层包裹异常捕获，一旦发生系统级异常立即回调 `captureAuthorizationFailed`。
  - 权限授予后立即触发 `requestProjectionConsent()`，通过 `MediaProjectionManager.createScreenCaptureIntent()` 拉起系统录屏授权。
- **Android 14 前台服务状态机（AudioCastCaptureService）**：
  - 接收 `ACTION_START_AUDIO_CAST` 后，先在 Android 14+ 校验 `RECORD_AUDIO` 权限与 `MediaProjection` 授权结果，立即调用 `promoteToForeground(mode)` 绑定 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`（以及麦克风捕获所需的 `FOREGROUND_SERVICE_TYPE_MICROPHONE`），随后再调用 `MediaProjectionManager.getMediaProjection(...)` 并注册 `MediaProjection.Callback`，确保完全符合 Android 14 严苛的真机安全策略。
- **自动化单元与 Robolectric 测试验证**：
  - 新增/更新针对权限与 MediaProjection 授权启动器及服务启动流程的本地测试，确保在 API 34+ 环境下无 16-bit `requestCode` 异常且状态流转完整。
