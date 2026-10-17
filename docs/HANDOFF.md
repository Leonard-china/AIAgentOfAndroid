# 开发交接备忘录 (HANDOFF.md)

---

## 1. 当前实施阶段与整体概览

- **当前阶段**：真机（荣耀 VKJ-AN00 / MagicOS 10.0 / Android 16）端到端全链路闭环验证已**全量通过**。
- **当前状态**：Qwen 大模型在线规划、Tool Calling（存储查询、大视频检索、重复照片精确排查）、系统回收站安全操作、审计反向还原、感知哈希（dHash）相似照片聚类及 Compose 自动化交互均已在真机上验证闭环。

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
- [x] **单元测试与打包**：
  - `.\gradlew.bat testDebugUnitTest` 14 项工具 Schema 与逻辑单元测试全量通过；
  - `.\gradlew.bat assembleDebug` 全量通过并成功静默覆盖安装到真机。

### 2.2 已实现但未验证 (Implemented but Unverified)
- [ ] **全量相册感知哈希大图集压力测试**：
  - 已在单元测试及小规模图集中验证 `SimilarPhotoEngine`，需在用户全量上千张相册背景并发运行时验证内存与耗时边界。

### 2.3 尚未实现 (Not Implemented)
- [ ] **扩展增量功能**：
  - 基于端侧 NPU/TFLite 的图片本地 Embedding 语义搜图；
  - SAF 自选目录全盘树形重命名与跨卷物理移动；
  - 正式发布用签名证书配置。

---

## 3. 当前外部阻塞项与应对方案

- **外部阻塞项**：无重大阻塞。
- **设备状态**：荣耀 VKJ-AN00 (Android 16) 在线且已安装最新应用版本。
- **模型状态**：DashScope Qwen KeyStore 加密存储，模型 `qwen-plus` 随时可调用。

---

## 4. 关键构建与部署命令

```powershell
# 1. 运行本地单元测试
.\gradlew.bat testDebugUnitTest

# 2. 构建 Debug APK 安装包
.\gradlew.bat assembleDebug

# 3. 检查设备在线情况
C:\Users\Leonard\AppData\Local\Android\Sdk\platform-tools\adb.exe devices

# 4. 一键安装至目标手机
C:\Users\Leonard\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk

# 5. 启动主页面
C:\Users\Leonard\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.aiagent.android.debug/com.aiagent.android.ui.MainActivity
```

---

## 5. 当前代码与提交信息

- **当前分支**：`master`
- **最新提交 Commit**：`516fb64`
- **最新代码状态**：所有代码、资源、配置与文档均已就绪并全量构建验证通过。
