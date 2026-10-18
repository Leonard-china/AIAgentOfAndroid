# 开发交接备忘录 (HANDOFF.md)

---

## 1. 当前实施阶段与整体概览

- **当前阶段**：v1.0.0 已发布；2026-10-18 08:13 完成一轮 P1/P2 缺陷修复（离线意图回归、回收站清单回写、存储重复计数、通知前台化、屏幕自动化安全加固、签名可移植性），**构建与单元测试已通过**。
- **当前状态**：Qwen 大模型在线规划、Tool Calling（存储查询、大视频检索、重复照片精确排查）、系统回收站安全操作、审计反向还原、感知哈希（dHash）相似照片聚类及 Compose 自动化交互均已在真机上验证闭环；本轮修复项尚未在真机复验（当前 `adb devices` 无设备在线）。

---

## 2. 三态功能清单

### 2.1 已实现且已验证 (Implemented & Verified)
- [x] **物理真机部署与静默安装**：
  - 成功跑通 MagicOS 10.0 (Android 16) 下免指纹全静默部署：`adb push` 到 `/data/local/tmp/app.apk` 后执行 `pm install -r -t -g`；
  - 配置保活参数，支持无人值守夜间自动化调试。
- [x] **大模型端侧对接与硬件 Keystore**：
  - Android KeyStore 硬件级 AES-256-GCM 本地加密保存自定义 API Key，不落明文日志；
  - 适配阿里云百炼 DashScope（`qwen-plus`），实测网络连通性 HTTP 200；
  - 真机实测自然语言多轮规划与 Function Calling 工具调用成功率 100%。
- [x] **存储与媒体真实分析工具层**：
  - `StorageEngine`：真机 StatFs 获取 220.2GB 闪存实际占用分项（视频 19.4GB，图片等真实数据）；
  - `MediaScannerEngine`：检索出 11 个超过 500MB 的真机大视频（合计 13.6GB），精准解析 display_name 与 MediaStore ID；
  - `DuplicateDetectionEngine`：真机流式 SHA-256 分块比对，精准识别 2 组完全一致副本，输出保留与清理建议；
  - `SimilarPhotoEngine`：实现 64-bit 灰度差异感知哈希 (`dHash`) 与汉明距离聚类，单元测试及架构就绪；
  - `TrashOperationExecutor`：基于 `MediaStore.createTrashRequest` 的安全回收站提交流程，支持反向还原（`restoreConsentLauncher`）与 SQLite 审计跟踪。
- [x] **现代化 Jetpack Compose UI 与交互**：
  - 5 大核心界面就绪（助手、存储、相册与文件、操作记录、设置）；
- [x] **受控屏幕操作与连点器辅助能力 (Accessibility & Gesture)**：
  - `ScreenAssistantAccessibilityService` 升级：实现基于 `dispatchGesture` 的高精度屏幕单点模拟点击 (`clickAt(x, y)`) 与平滑方向滑动 (`swipeDirection`)；
  - 文字控件智能点击：优先调用系统无障碍节点 Action，若节点未声明 clickable 则自动回退到节点屏幕物理中心坐标模拟手势点击；
  - 全局系统按键：支持返回 (`BACK`)、回到桌面 (`HOME`)、多任务 (`RECENTS`)、通知栏 (`NOTIFICATIONS`)；
  - 纯文本屏幕提取：`inspect_current_screen` 输出当前屏幕可见文字与中心坐标，极省 Token，零视觉模型开销；
  - 双模无缝支持：在线 LLM 工具调用（14 大工具声明）与离线规则规划引擎均完整支持屏幕操作；
  - 荣耀 VKJ-AN00 (MagicOS 10.0 / Android 16) 无障碍服务已激活并成功部署最新 APK。
- [x] **相册防 Token 爆炸与隐私离线保护 (Token Economy & Privacy)**：
  - System Prompt 确立铁律：严禁逐张翻看相册或向大模型回传原始图片二进制/Base64；
  - 纯本地引擎处理：相册扫描、SHA-256 分块查重、dHash 相似度聚类全部在设备端离线运行；
  - 极精简工具返回协议：大文件、重复分组、相似照片、截图及 APK 列表回传数量合理封顶（仅返回前 4~8 项关键摘要），剔除臃肿字段；
  - 新增 `query_file_detail` 单文件按需查询工具，仅在用户指明要查看某具体文件时调取，彻底根除 Token 爆炸风险。
- [x] **桌面全局悬浮窗防遮挡与四态伸缩架构 (Floating Assistant Window Overhaul)**：
  - 重塑四态智能伸缩模型（展开对话态 `EXPANDED` / 贴边极窄把手 `DOCKED_TAB` / 灵动微型胶囊 `COLLAPSED_PILL` / 完全后台隐身 `HIDDEN`）；
  - **贴边极窄把手**：仅占屏幕边缘 14dp 磨砂胶囊拉手，居侧边零遮挡，轻触把手瞬间展开，点击外部（`ACTION_OUTSIDE`）自动贴边收纳；
  - **黄金半屏比例**：展开窗压缩至 52% 屏幕高度与 88% 宽度（最大 400dp），22dp 毛玻璃圆角卡片，绝不遮挡全屏；
  - **随时随地唤出**：系统通知栏常驻卡片一键唤出 / 贴边 / 隐身，标题栏与设置页全套直控，语音/文本“贴边”、“隐身”、“打开悬浮窗”秒级响应；
  - 荣耀 VKJ-AN00 (Android 16) `SYSTEM_ALERT_WINDOW` 与 `POST_NOTIFICATIONS` 生效，实测触控与伸缩闭环 100% 成功。
- [x] **全场景“开新对话”与上下文物理重置闭环 (New Chat & Context Reset)**：
  - 主应用助手页面顶部新增 Header Bar，集成 `FilledTonalButton("开新对话")` 按钮及快捷 Chip；
  - 桌面悬浮窗标题栏集成 `IconButton("开新对话")` 图标按钮及悬浮快捷 Chip；
  - 彻底清空内存中累积的消息上下文 (`messages.clear()`)、实时任务步骤 (`activeSteps.clear()`) 并输出全新就绪欢迎语；
  - 支持自然语言和快捷指令拦截（“开新对话/新建对话/清空对话/重新开始”），真机端到端点击与触控验证 100% 成功。
- [x] **正式 Release 签名打包与 GitHub 远程开源发布**：
  - 配置 RSA 2048-bit 签名密钥库与 `signingConfigs.release`；
  - 成功生成带正式签名的 Release APK：`app/build/outputs/apk/release/app-release.apk` (45.8 MB)；
  - 编写全景根目录 `README.md` 与 Apache 2.0 `LICENSE`；
  - 建立 GitHub 远程仓库：`https://github.com/Leonard-china/AIAgentOfAndroid`；
  - 发布 GitHub Release `v1.0.0` 并挂载正式 Release APK 资产包。
- [x] **单元测试与打包**：
  - `.\gradlew.bat testDebugUnitTest` 6 个测试类、18 个单元测试用例全量通过（0 失败）；
  - `.\gradlew.bat assembleDebug` 全量通过；
  - `.\gradlew.bat assembleRelease` 全量通过，生成正式签名 Release 安装包（本机存在 `release.keystore`）。
- [x] **原生应用启动能力与受限设置解除指引 (launch_app & Restricted Settings Guide)**：
  - **原生应用启动**：遵循 Native First 铁律，新增 `AppLauncherHelper`，基于 `PackageManager.getLaunchIntentForPackage` 原生调起外部 App，解决“打开B站”误点击聊天气泡的缺陷；
  - **双模工具链**：`ToolRegistry` 注册第 16 个核心工具 `launch_app`，System Prompt 严禁屏幕盲点；`OfflineIntentClassifier` 新增 `OfflineIntent.LAUNCH_APP` 与 `extractAppName`，离线规则同样支持原生启动；
  - **权限与清单**：`AndroidManifest.xml` 新增 `QUERY_ALL_PACKAGES` 与 `<queries>` Launcher 声明；
  - **受限设置解除指引**：`SettingsScreen` 针对 Android 13+ 外部安装提示“受限制的设置”，新增一键跳转应用信息页按钮（`ACTION_APPLICATION_DETAILS_SETTINGS`）与操作贴士。
- [x] **P1/P2 缺陷修复（2026-10-18 08:13，构建与单元测试已验证）**：
  - **离线意图回归修复**：新增 `OfflineIntentClassifier` 纯函数并重排优先级，“打开桌面悬浮窗”不再错误执行 HOME、“帮我点设置”不再打开存储设置页、“打开无障碍设置/查看当前屏幕”可正确匹配；
  - **回收站清单回写**：目标文件被外部移除时，`MainActivity` 将重新校验后的最新计划回写消息卡片，消除“反复确认旧清单”的死循环；
  - **存储分类去重**：`StorageEngine` 文档分类排除 APK MIME 与 `.apk` 路径，避免 APK 在“文档”和“安装包”重复计数；
  - **通知与前台服务**：补充 `POST_NOTIFICATIONS` 运行时申请，`FloatingAssistantService` 使用 `startForeground` + `FOREGROUND_SERVICE_SPECIAL_USE`，保障通知栏“随时唤出”；
  - **屏幕自动化安全加固**：新增系统级破坏性弹窗检测，坐标/文字点击均硬性拦截；移除未使用的 `canTakeScreenshot` 能力；
  - **攻击面收敛**：`FloatingAssistantService` 改为 `exported=false`，移除 `MainActivity` 对外部 Intent `prompt` 的执行入口；
  - **回收站真实复核**：系统确认后逐一复核目标是否真正离开原位置，审计状态区分成功与部分成功；
  - **签名可移植**：`release.keystore` 缺失时不再阻止 `assembleRelease`，密码优先从环境变量/Gradle 属性读取。

### 2.2 已实现但未验证 (Implemented but Unverified)
- [ ] **全量相册感知哈希大图集压力测试**：
  - 已在单元测试及小规模图集中验证 `SimilarPhotoEngine`，需在用户全量上千张相册背景并发运行时验证内存与耗时边界。
- [ ] **本轮 P1/P2 修复的真机行为验证**（当前 `adb devices` 无设备在线）：
  - 通知权限弹窗、悬浮窗前台通知与通知栏【唤出/贴边/隐身/关闭】按钮；
  - 离线指令“打开桌面悬浮窗 / 帮我点设置 / 打开无障碍设置 / 查看当前屏幕”；
  - 目标文件失效时的清单回写与重新确认；
  - 系统级删除确认弹窗的自动点击拦截；
  - 存储页“文档与压缩包 / 安装包 / 系统与其他数据”分类数值一致性。

### 2.3 尚未实现 (Not Implemented)
- [ ] **待用户提供生成的图标**：
  - 待用户通过 GPT 生成专业 App 图标后，导入 Android `res/mipmap` 替换启动器默认图标；
- [ ] **扩展增量功能**：
  - 基于端侧 NPU/TFLite 的图片本地 Embedding 语义搜图；
  - SAF 自选目录全盘树形重命名与跨卷物理移动；
  - 历史多会话列表与对话归档切换。

---

## 3. 当前外部阻塞项与应对方案

- **外部阻塞项**：暂无外部阻塞。2026-10-18 08:36 物理真机（荣耀 VKJ-AN00 / `AXRBUT6520002413`）已重新在线连接。
- **应对策略**：已具备真机回归条件，可随时执行 §4 命令部署并按 §2.2 清单逐项验证。
- **设备状态**：荣耀 VKJ-AN00 (Android 16 / MagicOS 10.0 / `AXRBUT6520002413`) 当前在线；待安装本轮 P1/P2 修复 APK。
- **模型状态**：DashScope Qwen KeyStore 加密存储，模型 `qwen-plus` 随时可调用。
- **开源发布状态**：已在 GitHub 成功发布开源仓库与 Release v1.0.0。

---

## 4. 关键构建与部署命令

```powershell
# 1. 运行本地单元测试
.\gradlew.bat testDebugUnitTest

# 2. 构建正式 Release APK 安装包
#    （可选）通过环境变量提供签名口令：AIAGENT_STORE_PASSWORD / AIAGENT_KEY_ALIAS / AIAGENT_KEY_PASSWORD
#    本机缺少 release.keystore 时 assembleRelease 仍会成功，但产出未签名 APK
.\gradlew.bat assembleRelease

# 3. 检查设备在线情况
C:\Users\Leonard\AppData\Local\Android\Sdk\platform-tools\adb.exe devices

# 4. 一键安装 Release 包至目标手机
C:\Users\Leonard\AppData\Local\Android\Sdk\platform-tools\adb.exe push app\build\outputs\apk\release\app-release.apk /data/local/tmp/app.apk
C:\Users\Leonard\AppData\Local\Android\Sdk\platform-tools\adb.exe shell pm install -r -t -g /data/local/tmp/app.apk

# 5. 启动主页面
C:\Users\Leonard\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.aiagent.android/com.aiagent.android.ui.MainActivity
```

---

## 5. 当前代码与提交信息

- **当前分支**：`master`
- **最新提交 Commit**：`a2e6b2a`
- **最新代码状态**：已集成原生 launch_app 工具组件与受限制设置直达指引，单元测试（18个用例）与 Release 构建均已通过，待部署真机实测。
