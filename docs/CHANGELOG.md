# 变更记录历史 (CHANGELOG.md)

所有记录遵循中国标准时间（Asia/Shanghai, UTC+08:00）。按时间追加，保留完整历史，严禁覆盖或删除旧记录。

---

## [2026-10-17 21:15:00] - 项目初始化与核心工程规约落地

- **变更摘要**：
  - 初始化 Git 仓库与 Android 原生工程结构；
  - 落地 Agent 协作守则 `AGENTS.md` 及全套文档体系（`docs/README.md`, `docs/PRODUCT.md`, `docs/ARCHITECTURE.md`, `docs/TESTING.md`, `docs/HANDOFF.md`, `docs/CHANGELOG.md`）；
  - 配置基于 Gradle 9.5.0 与 AGP 9.3.1 的编译环境，适配 Android 16 (API 36)；
  - 建立标准 `.gitignore` 与本地 SDK 路径配置。
- **修改原因**：
  - 响应用户关于构建真实可安装、可使用、持续迭代的 Android AI 文件与相册管理助手的硬性要求；
  - 确保多轮次或跨 Agent 开发过程中的严格一致性、真实性保证与交接机制。
- **涉及文件或模块**：
  - `AGENTS.md`
  - `.gitignore`
  - `local.properties`
  - `docs/README.md`
  - `docs/PRODUCT.md`
  - `docs/ARCHITECTURE.md`
  - `docs/TESTING.md`
  - `docs/HANDOFF.md`
  - `docs/CHANGELOG.md`
- **验证方法与真实结果**：
  - 验证方法：
    1. 检查本地 JDK 环境：`java -version` 输出 `Java 17.0.12 LTS`；
    2. 检查 Android SDK：`C:\Users\Leonard\AppData\Local\Android\Sdk` 包含 `android-36` 及 Build-Tools `36.0.0`；
    3. 运行 Gradle 包装器：`.\gradlew.bat --version` 成功输出 `Gradle 9.5.0`，退出码 0；
    4. 检查 ADB 连接：`adb devices` 守护进程正常启动，当前尚未连接物理机（List of devices attached 为空）。
  - 真实结果：构建工具链与文档骨架全部就绪，验证通过。
- **已知问题与后续事项**：
  - 已知问题：目前基准设备（荣耀 VKJ-AN00）尚未通过 USB/无线 ADB 连接电脑，暂时无法直接执行 `adb install` 或真机 UI 自动化测试。
  - 后续事项：立刻推进第一阶段——构建完整的 Android 模块（`app` 模块），编写权限管理、真实 MediaStore 媒体/文件扫描索引、存储空间分析、完全重复文件检测算法、Room 数据库、操作预览卡片与 Compose 5大核心界面。
- **Git 提交标识**：`0ce3c69`
---

## [2026-10-17 21:30:00] - 第一阶段基础应用、文件闭环与 AI 编排层全量落地

- **变更摘要**：
  - 完成 Android 原生主工程 `app` 模块的全套构建配置，适配 Android 16 (API 36) / Min SDK 30；
  - 建立领域模型层 (`model/Models.kt`)，涵盖媒体文件、物理存储、重复分组、操作计划与审计记录；
  - 实现基于 SQLite 的持久化数据层 (`data/AppDatabaseHelper.kt`)，支持操作记录留痕与哈希缓存；
  - 实现基于 Android KeyStore 硬件后端的 AES-256-GCM 本地加密保险库 (`security/KeyStoreVault.kt`)；
  - 编写可校验工具层：
    1. `StorageEngine`：调用底层 `StatFs` 获取真机闪存总容量、可用容量与六大分类真实物理占用；
    2. `MediaScannerEngine`：原生 MediaStore 分页扫描，支持大视频、截图识别与 Android 14+ 部分授权检测；
    3. `DuplicateDetectionEngine`：$O(1)$ 大小快速粗筛 + 64KB 流式 SHA-256 内容哈希精筛，支持实时中断；
    4. `SimilarPhotoEngine`：基于连拍命名与时间戳聚合的相似照片候选识别，严禁自动删除；
    5. `TrashOperationExecutor`：基于 `MediaStore.createTrashRequest` 的系统回收站安全操作机制，执行前重新核验文件身份，支持确认与取消流转并写入审计日志；
  - 编写 AI 任务规划与双模编排引擎：
    1. `LlmClient`：适配标准 OpenAI 兼容协议，支持工具调用与网络/密钥连通性测试；
    2. `ToolRegistry`：声明 7 项核心工具的 JSON Schema，严格限制模型参数；
    3. `AgentOrchestrator`：支持大模型多步规划，并在离线或未填 Key 时无缝切换为全功能本地规则引擎；
  - 编写受控辅助无障碍屏幕操作模块 (`screen/ScreenAssistantAccessibilityService.kt`)，默认关闭，具备全局急停与安全边界；
  - 构建五大现代化 Jetpack Compose / Material 3 界面：
    1. 助手主页 (`AssistantScreen`)：支持对话流、实时任务步骤卡片与【操作预览确认卡片】；
    2. 存储分析 (`StorageScreen`)：真实容量进度、分类卡片与清理建议；
    3. 文件与相册 (`MediaScreen`)：六大分类筛选、多选批量移入回收站、元数据详情对话框；
    4. 操作记录 (`AuditLogScreen`)：操作时间线、状态徽标与影响详情；
    5. 设置界面 (`SettingsScreen`)：接口配置、密钥加密保存、连接测试、权限看板与无障碍引导；
  - 编写并执行单元测试集 (`FormatBytesTest`, `DuplicateDetectionLogicTest`, `ToolRegistryTest`, `ActionPlanSafetyTest`)；
  - 成功编译打包产出 `app-debug.apk`。
- **修改原因**：
  - 完整兑现第一阶段所有核心功能要求，并同步打通第二阶段 AI 工具调用与第三阶段屏幕辅助基础架构。
- **涉及文件或模块**：
  - `gradle.properties`
  - `gradle/libs.versions.toml`
  - `app/build.gradle.kts`
  - `app/proguard-rules.pro`
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/res/*`
  - `app/src/main/java/com/aiagent/android/*`
  - `app/src/test/java/com/aiagent/android/*`
  - `docs/CHANGELOG.md`
- **验证方法与真实结果**：
  - 验证方法：
    1. 运行本地单元测试：`.\gradlew.bat testDebugUnitTest`；
    2. 编译打包 Debug APK：`.\gradlew.bat assembleDebug`；
    3. 检查产物文件：PowerShell 查询 `app/build/outputs/apk/debug/app-debug.apk`。
  - 真实结果：
    1. 单元测试全量通过：`BUILD SUCCESSFUL in 48s`，无任何报错；
    2. APK 构建成功：`BUILD SUCCESSFUL in 2m 21s`，生成有效安装包 `app-debug.apk` (大小约 63.8MB)；
    3. 目标真机目前尚未连接 ADB，物理机交互联调状态如实标注为待联调。
- **已知问题与后续事项**：
  - 已知问题：荣耀 VKJ-AN00 手机当前未连接 ADB，暂未直接安装到真机；
  - 后续事项：待用户连接手机或启动无线调试后，使用 `adb install -r` 将 APK 推送至手机进行端到端权限、相册回收站弹窗与无障碍导航真机测试。
- **Git 提交标识**：`516fb64`
