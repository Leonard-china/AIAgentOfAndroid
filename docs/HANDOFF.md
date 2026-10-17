# 开发交接备忘录 (HANDOFF.md)

---

## 1. 当前实施阶段与整体概览

- **当前阶段**：第一阶段（基础应用与真实文件闭环）启动中。
- **当前目标**：搭建完整的原生 Android 项目代码（Jetpack Compose + Material 3 + Room + MediaStore/SAF + 工具层），实现第一阶段全部核心功能，产出可运行、可构建的 Debug APK，并保证离线状态下所有文件检索、存储分析、重复哈希排查、回收站预览闭环可用。

---

## 2. 三态功能清单

### 2.1 已实现且已验证 (Implemented & Verified)
- [x] **工程与基础工具链准备**：
  - Git 仓库初始化完成；
  - JDK 17 (17.0.12) 环境核验通过；
  - Android SDK (API 36, Build Tools 36.0.0) 路径与配置验证通过；
  - Gradle 9.5.0 包装器运行通过（`.\gradlew.bat --version` 退出码 0）；
  - 全套规范文档与交接体系建立（`AGENTS.md`, `docs/*`）。

### 2.2 已实现但未验证 (Implemented but Unverified)
- 暂无（本阶段首批代码即将开始编写）。

### 2.3 尚未实现 (Not Implemented)
- [ ] **第一阶段：基础应用与真实文件闭环**：
  - `build.gradle.kts` 与 Gradle 依赖树配置（Compose M3, Room, WorkManager, Coroutines, OkHttp, Coil 等）；
  - `AndroidManifest.xml` 与权限声明（Android 11~16 媒体/文件权限、AccessibilityService 声明）；
  - 存储空间分析引擎（`StorageStatsEngine`，调用 `StatFs` 与真实分类聚合）；
  - 媒体与文件扫描索引引擎（`MediaScannerEngine`，分页 MediaStore + SAF 支持，部分授权处理）；
  - 完全重复文件排查引擎（`DuplicateDetectionEngine`，大小粗筛 + 分块流式 SHA-256 哈希计算）；
  - 安全回收站与操作执行器（`TrashOperationExecutor`，调用 `MediaStore.createTrashRequest`，状态重验）；
  - Room 数据库（索引缓存、任务状态、操作审计日志）；
  - 五大 Compose 核心界面：
    1. 助手主页（对话气泡、任务步骤指示、操作预览卡片、取消/停止入口）；
    2. 存储分析（磁盘概览环形/条形图、大文件专区、重复文件专区、清理建议）；
    3. 文件与相册（分类相册、文件网格、筛选排序、多选、图片预览）；
    4. 操作记录（审计记录时间线、成功/失败/撤销明细、回收站跳转入口）；
    5. 设置界面（模型参数配置、KeyStore 密钥保护、权限状态看板、无障碍开关）。
  - 单元测试集构建与 Gradle APK 编译校验。
- [ ] **第二阶段：AI 任务闭环**：
  - OpenAI 兼容协议适配器（可自定义 Base URL / API Key / Model）；
  - KeyStore AES-256-GCM 本地加密密钥保管库；
  - 结构化 Tool Calling 规划器与协议解析；
  - 自然语言意图转真实操作计划（防路径伪造、防越权注入）；
  - 相似照片候选分组（基于感知哈希或特征粗筛）。
- [ ] **第三阶段：屏幕操作闭环**：
  - 独立 `AccessibilityService` 模块（受控辅助、安全边界、全局停止入口）；
  - 控件语义树定位与高保真坐标映射；
  - 真实流程适配：拉起系统存储详情设置页并辅助导航。

---

## 3. 当前阻塞项与应对方案

1. **真实物理设备（荣耀 VKJ-AN00）当前未在线**：
   - **现状**：通过 `adb devices` 检查，当前无在线设备。
   - **影响**：无法直接将 APK push 到该设备执行触控手势和厂商专属 MagicOS 10.0 回收站 UI 弹窗真机联调。
   - **应对策略**：不以设备未连接为借口停滞。我们严格按照 Android 16 (API 36) 规范编写高兼容性代码，在本地通过 `./gradlew testDebugUnitTest` 运行全量单元测试，通过 `./gradlew assembleDebug` 编译生成真实的 APK，提供详细的安装与测试指南，待用户插线或开启网络 ADB 时一键验证。

---

## 4. 下一步行动计划

1. 配置 `settings.gradle.kts`、根 `build.gradle.kts`、`gradle/libs.versions.toml` 及 `app/build.gradle.kts`。
2. 编写核心数据结构、Room 实体与 DAO（`FileIndex`、`TaskState`、`AuditLog`）。
3. 编写 `StorageEngine`、`MediaScanEngine`、`DuplicateHashEngine`。
4. 编写 `ActionPlan` 数据模型与安全回收站执行器 `TrashExecutor`。
5. 编写 UI 层（Navigation、AssistantScreen、StorageScreen、MediaScreen、AuditLogScreen、SettingsScreen）及对应 ViewModels。
6. 执行单元测试与构建测试，生成 Debug APK。
