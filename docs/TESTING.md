# 测试与验证指导说明书 (TESTING.md)

---

## 1. 测试环境与工具链配置

- **操作系统**：Windows 11 (x86_64)
- **JDK 版本**：Java 17 (Oracle JDK 17.0.12 LTS)
- **Android SDK 路径**：`C:\Users\Leonard\AppData\Local\Android\Sdk`
- **已安装 Platforms**：`android-36` (Android 16), `android-37.0`
- **已安装 Build Tools**：`36.0.0`, `34.0.0`
- **Gradle Wrapper**：Gradle 9.5.0
- **Android Gradle Plugin**：9.3.1
- **基准真实设备**：
  - 型号：荣耀 VKJ-AN00
  - 系统版本：MagicOS 10.0 (基于 Android 16)
  - 核心配置：12GB RAM / 256GB ROM
  - 接入模式：USB 调试 / 无线调试 (ADB)

---

## 2. 常用构建与测试命令

所有命令在项目根目录 `D:\Project\AIAgentOfAndriod` 下执行：

```powershell
# 1. 语法检查与依赖构建校验（Dry Run）
.\gradlew.bat tasks --dry-run

# 2. 运行本地单元测试
.\gradlew.bat testDebugUnitTest

# 3. 构建 Debug APK 安装包
.\gradlew.bat assembleDebug

# 4. 构建 Release APK（本机存在 release.keystore 时自动签名；缺失时产出未签名 APK）
.\gradlew.bat assembleRelease

# 5. 查看连接设备
C:\Users\Leonard\AppData\Local\Android\Sdk\platform-tools\adb.exe devices

# 6. 安装 Debug 包至目标手机（当设备在线时）
C:\Users\Leonard\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 3. 关键测试用例与边界覆盖

> 本节表格保留此前阶段记录；本轮最新结果为 §6：8 个测试类、29 项，0 失败。

根据产品规范，测试必须覆盖以下关键行为与风险点：

| 测试模块 | 用例名称 | 测试目标与验证点 | 实测结果 |
| :--- | :--- | :--- | :--- |
| **存储层** | `FormatBytesTest` | 验证 0B、KB、MB、500MB、GB 边界单位格式化 | `PASSED` (单元测试通过) |
| **工具层** | `DuplicateDetectionLogicTest` | 验证 O(1) 大小粗筛剔除唯一文件，减少哈希 I/O | `PASSED` (单元测试通过) |
| **工具层** | `ActionPlanSafetyTest` | 验证操作计划在 PREVIEW 状态前不可执行，安全状态流转 | `PASSED` (单元测试通过) |
| **编排层** | `ToolRegistryTest` | 验证 15 个工具 Schema 完整性与必需参数（target_ids, reason） | `PASSED` (单元测试通过) |
| **编排层** | `OfflineIntentClassifierTest` | 验证离线意图优先级：悬浮窗/点击/无障碍/查看屏幕/存储等指令不被更宽泛关键词抢占 | `PASSED` (5 个用例通过) |
| **构建层** | `assembleDebug` | 验证 Android 16/Compose/D8 全量打包链路及资源装配 | `PASSED` (生成 63.8MB APK) |
| **构建层** | `testDebugUnitTest` | 6 个测试类、16 个用例的全量回归 | `PASSED` (0 失败) |

---

## 4. 真机（荣耀 VKJ-AN00）实测检查清单

当目标设备连接时，执行以下端到端专项验证：

- [ ] **权限降级与部分授权**：
  - 测试授予“仅允许访问部分照片和视频”（Android 14+ 视觉媒体选择器），验证应用仅展示受限范围照片，明确标记扫描范围，不宣称为“全盘扫描”。
  - 测试用户在系统设置中撤销权限后，返回应用不发生闪退，友好提示权限缺失。
- [ ] **回收站真实行为**：
  - 调用 `createTrashRequest`，观察 MagicOS 10.0 系统确认弹窗。
  - 用户点击同意后，媒体文件移入系统回收站，验证图库应用中移入“最近删除”，设备可用存储尚未改变。
  - 用户在系统弹窗中点击“取消”，应用正确捕获 `RESULT_CANCELED`，不执行删除且记录审计日志为“用户取消”。
- [ ] **大体积文件与高并发哈希**：
  - 扫描手机中 $>500\text{MB}$ 的大视频，启动哈希查重，验证 UI 帧率流畅（不卡主线程）、进度条平滑刷新，并可在中途点击【取消】立即停止后台流式 I/O。
- [ ] **受控屏幕辅助**：
  - 用户在设置中手动开启本应用无障碍服务；
  - 触发“打开系统存储管理页面”，验证无障碍模块成功调起系统设置并将视觉焦点辅助引导至存储页面。

---

## 5. 测试结果记录规约

所有测试结果必须如实记录在 `docs/CHANGELOG.md` 与 `docs/HANDOFF.md` 中：
- 本地构建与单元测试：标明具体执行时间、命令与结果状态；
- 真机验证：仅当真实物理机连接并完成交互时方可记录为“真机已验证”，记录具体测试结果；无真机在线时明确标注为“代码已完成，待真机联调”。


## 6. 手机跨应用执行闭环验证（2026-10-18）

### 本地回归

- 实际命令：`.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease`，最终 BUILD SUCCESSFUL（24s），8 类 29 项均实际执行通过。
- 新增 `AgentLoopTest` 7 项：防提前结束、快照和原文证据校验、失败工具不完成、无进展限制、需接手停止批次、取消传播、动作预算。
- 新增 `LlmClientCancellationTest`：本机 ServerSocket 返回未完整结束的 HTTP 响应体，取消协程后 2 秒内请求结束且服务端观察到 socket 断开；不使用线上 API Key。
- 原有 21 项测试保留，总计 8 类 29 项。

### 实机执行记录

- 设备：荣耀 VKJ-AN00 / Android 16 / MagicOS 10.0 / AXRBUT6520002413。
- 模型：设备已配置的 qwen-plus，未读取/导出 API Key。
- 任务：“在哔哩哔哩搜索 3Blue1Brown，打开一个数学视频”。由 ADB 点击主界面示例和发送，后续点击/搜索均由应用自身执行；ADB 只截屏观察，未代替应用完成任务。
- 已观察结果：9 个工具步骤后进入 3Blue1Brown 官方《AI 未能解决的最后一道 IMO 真题》详情页，前后截图的视频画面持续变化；助手结果引用准确标题并显示“已核验界面”。这只证明该机该任务，不是跨应用通用成功率。
- 会话连续性：切回主界面保留步骤和答复；打开浮窗可看到同一历史。后续移除浮窗启动时插入的欢迎消息。
- 停止测试发现：原 HUD 在 show 中调用 dismissInternal 后清空了停止回调，出现点停止后继续执行。已修回调绑定顺序。修复后分别启动任务，在约 500ms 后点击顶部 HUD 停止、主界面停止，均呈现“已停止任务”并恢复发送入口，日志出现 task cancelled by user；截图 hud-stopped.png、main-stopped.png。

### 避免测试工具干扰

**不要在应用自主操作期间运行普通 `adb shell uiautomator dump`。** 本轮第二次尝试使用该命令后，看到无障碍服务临时断开，任务正确以 NEEDS_USER 结束，之后服务重连；该样本不是有效的停止验收。UiAutomation 默认抑制其他无障碍服务，参见 [Android UiAutomation 文档](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)。运行中使用 `adb shell screencap` 和仅元数据日志观察；如采用自定义 instrumentation，连接时使用 DONT_SUPPRESS 标志。

主动通过 ADB 拉回主界面会改变智能体的当前屏幕，也会干扰在途目标；应先停止任务再切回，或等任务结束。

本地截图、UI XML 与 APK 校验文件放在被 Git 忽略的 `artifacts/2026-10-18-review/`，不把设备个人内容提交到仓库。截图视觉理解、自绘界面与长任务压力尚未验收。


### 最终安装版复验

- 最终 APK SHA-256：`2db2c2cfcdf4ac41c6578c8c84b6e01b4ab3b46d439d5880c5e0e9f748a30dc8`；apksigner verify 退出码 0；设备 pm install -r -t 返回 Success。
- 再次提交同一 3Blue1Brown 目标，从上次已打开的视频状态起步，自主执行 10 步，日志 COMPLETED，已进入相同数学视频详情且可观察到视频画面；未用 ADB 代替应用点击搜索或视频。
- 最终页面报告使用“简介”作为 finish_task 原文证据；这能证明来自当前屏幕，但无法独立证明完整语义目标。仍需补强完成标准与证据质量；本次播放由测试者另行通过截图观察确认。
- 版本更新后首页真实显示“屏幕已连接”；测试结束已回到主应用结果页，已停止本轮主机 logcat 观察进程。
