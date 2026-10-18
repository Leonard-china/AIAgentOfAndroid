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
