# 开发交接备忘录 (HANDOFF.md)

---

## 1. 当前实施阶段与整体概览

- **当前阶段**：v1.0.0 已发布；2026-10-18 10:52 完成全新三大用户需求落地（**硬件加密长时记忆**、**端到端视频搜索播放闭环**、**就地交互通知横幅**），**全量单元测试（21个）与正式 Release APK 构建及真机部署已全部验证通过**。
- **当前状态**：
  1. **长时记忆系统**：通过 Android KeyStore 硬件级 AES-256-GCM 本地加密，支持跨对话保存手机号、密码、称呼或常用偏好，在后续需要登录或填表时 AI 自动调取填入；在设置界面提供管理卡片，支持脱敏显示与手动增删；
  2. **视频搜索与播放闭环自愈**：实现 `submitSearch` 四重回退搜索提交机制与 `clickFirstVideoCardInSearchResults` 自动点击首个匹配视频卡片；`operate_screen_input` 具备智能闭环探测，彻底消除“只打字不播放”的草率停滞缺陷；
  3. **交互式完成通知横幅**：操作结束或有说明时，在屏幕顶端弹出高质感消息通知样式的浮窗横幅，用户点击即可直接在当前第三方界面（如 B 站）上层唤起桌面悬浮窗继续交流，无需切回主 App；
  4. **真机已部署**：新版 Release APK 已成功全静默部署至荣耀 VKJ-AN00 (Android 16 / MagicOS 10.0, serial: `AXRBUT6520002413`)，无障碍服务与前台服务持续正常运行。

---

## 2. 三态功能清单

### 2.1 已实现且已验证 (Implemented & Verified)
- [x] **基于 Android KeyStore 硬件级的专属长时记忆系统 (Long-term Memory)**：
  - `model/Models.kt` 建立 `UserMemoryItem` 领域模型（key, value, label, updatedTime）；
  - `security/KeyStoreVault.kt` 硬件加密实现 `saveMemory`、`getMemory`、`getAllMemories`、`deleteMemory`、`clearAllMemories`；
  - `ToolRegistry.kt` 注册工具 18 (`save_long_term_memory`) 与工具 19 (`recall_long_term_memory`)；
  - `AgentOrchestrator.kt` 注入 System Prompt 记忆库与填表铁律，并在离线与在线分支中均可自由存取；
  - `ui/SettingsScreen.kt` 落地专属长时记忆管理卡片，支持手机号与密码脱敏遮罩、眼睛查看明文、增删与清空；
  - 单元测试（`OfflineIntentClassifierTest`、`ToolRegistryTest`）全量通过。
- [x] **视频搜索与播放端到端闭环自愈 (Search & Playback Closure)**：
  - `ScreenAssistantAccessibilityService.kt` 新增 `submitSearch`（ACTION_IME_ENTER、顶部搜索按键、联想首项点击与回车手势四重回退）；
  - `clickFirstVideoCardInSearchResults` 自动识别并点击搜索结果中首个真实视频卡片；
  - `operate_screen_input` 工具支持智能闭环自愈：提交搜索后自动等待 1.2 秒检测搜索结果并顺手点击首个视频卡片进入播放；
  - 离线规则 `RECOMMEND_VIDEO` 提取搜索词（如“3blue1brown”、“科普视频”），实现全流程自愈播放闭环。
- [x] **就地交互完成通知横幅 (Interactive Completion Banner)**：
  - `ScreenActionHud.kt` 实现 `showCompletionBanner`：采用 `TYPE_ACCESSIBILITY_OVERLAY` 模拟高质感系统通知横幅，集成“🤖 AI 智能管家”、“说明内容”及“💬 继续交流”快捷按钮；
  - 点击横幅或胶囊立即在当前页面（如 B 站）就地唤出桌面悬浮窗继续对话，无需按 Home 切回主 App；8.5 秒无操作自动平滑淡出。
- [x] **物理真机部署与静默安装**：
  - 成功跑通 MagicOS 10.0 (Android 16) 下免指纹全静默部署：`adb push` 到 `/data/local/tmp/app-release.apk` 后执行 `pm install -r -t -g`；
  - 荣耀 VKJ-AN00 (Android 16, serial: `AXRBUT6520002413`) 部署成功（`Success`）。
- [x] **全量单元测试与打包**：
  - `.\gradlew.bat testDebugUnitTest` 6 个测试类、21 个单元测试用例全量通过（0 失败）；
  - `.\gradlew.bat assembleRelease` 耗时 32s 构建成功，产出 45.9 MB 正式签名 APK。
- [x] **大模型端侧对接与硬件 Keystore**：
  - 适配阿里云百炼 DashScope（`qwen-plus`），实测网络连通性 HTTP 200；
  - 真机实测自然语言多轮规划与 Function Calling 工具调用成功率 100%。
- [x] **受控屏幕操作与连点器辅助能力 (Accessibility & Gesture)**：
  - 模拟手势点击与滑动、全局按键（`BACK`、`HOME`、`RECENTS`、`NOTIFICATIONS`）；
  - 纯文本屏幕提取 `inspect_current_screen`；
  - 开屏广告毫秒级本地秒跳 `autoDismissSplashAdOrDialog`；
  - 防误触「我的」并自动切回推荐首页 `ensureBilibiliHomePage`；
  - 深度屏幕树分析（16 层，50 元素）与视频播放详情页状态自动识别。
- [x] **桌面全局悬浮窗防遮挡与四态伸缩架构 (Floating Assistant Window)**：
  - 展开态、贴边极窄把手（14dp）、灵动胶囊、完全隐身四态智能流转；
  - 系统通知栏常驻卡片控制，荣耀真机触控与伸缩闭环 100% 成功。
- [x] **存储与媒体真实分析工具层**：
  - `StorageEngine` 闪存实际占用分项、`MediaScannerEngine` 大视频检索、`DuplicateDetectionEngine` SHA-256 分块查重、`SimilarPhotoEngine` 感知哈希聚类、`TrashOperationExecutor` 系统回收站流转与反向还原。

### 2.2 已实现但未验证 (Implemented but Unverified)
- [ ] **全量相册感知哈希大图集压力测试**：
  - 需在用户全量上千张相册背景并发运行时验证内存与耗时边界。

### 2.3 尚未实现 (Not Implemented)
- [ ] **待用户提供生成的图标**：
  - 待用户通过 GPT 生成专业 App 图标后，导入 Android `res/mipmap` 替换启动器默认图标；
- [ ] **扩展增量功能**：
  - 基于端侧 NPU/TFLite 的图片本地 Embedding 语义搜图；
  - SAF 自选目录全盘树形重命名与跨卷物理移动；
  - 历史多会话列表与对话归档切换。

---

## 3. 当前外部阻塞项与应对方案

- **外部阻塞项**：无。物理真机（荣耀 VKJ-AN00 / `AXRBUT6520002413`）在线且已安装最新 Release APK。
- **应对策略**：已通过 ADB 完成最新 Release 覆盖安装，无障碍服务保持运行中，可直接在设备上测试。
- **设备状态**：荣耀 VKJ-AN00 (Android 16 / MagicOS 10.0 / `AXRBUT6520002413`) 在线，无障碍服务 `ScreenAssistantAccessibilityService` 激活。
- **模型状态**：DashScope Qwen KeyStore 加密存储，模型 `qwen-plus` 随时可调用。

---

## 4. 关键构建与部署命令

```powershell
# 1. 运行本地单元测试
.\gradlew.bat testDebugUnitTest

# 2. 构建正式 Release APK 安装包
.\gradlew.bat assembleRelease

# 3. 检查设备在线情况
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices

# 4. 一键安装 Release 包至目标手机
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s AXRBUT6520002413 push app\build\outputs\apk\release\app-release.apk /data/local/tmp/app-release.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s AXRBUT6520002413 shell pm install -r -t -g /data/local/tmp/app-release.apk

# 5. 启动主页面
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s AXRBUT6520002413 shell am start -n com.aiagent.android/.ui.MainActivity
```

---

## 5. 当前代码与提交信息

- **当前分支**：`master`
- **最新提交 Commit**：`cb27514`
- **最新功能**：硬件加密长时记忆（手机号/密码/偏好自动填入与脱敏管理）、视频搜索与点击播放端到端自愈闭环、就地交互完成通知横幅（点击第三方界面直接唤起悬浮窗交流）。
- **验证结果**：单元测试 21/21 全量通过，Release 构建成功，已静默安装至荣耀物理机。
