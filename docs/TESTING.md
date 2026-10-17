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

# 4. 构建 Release APK（待配置签名）
.\gradlew.bat assembleRelease

# 5. 查看连接设备
C:\Users\Leonard\AppData\Local\Android\Sdk\platform-tools\adb.exe devices

# 6. 安装 Debug 包至目标手机（当设备在线时）
C:\Users\Leonard\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 3. 关键测试用例与边界覆盖

根据产品规范，测试必须覆盖以下关键行为与风险点：

| 测试模块 | 用例名称 | 测试目标与验证点 | 预期表现 |
| :--- | :--- | :--- | :--- |
| **工具层** | `HashAlgorithmTest` | 验证多文件分块 SHA-256 计算一致性与抗碰撞 | 相同内容不同名称文件哈希完全一致，不同文件哈希不同 |
| **工具层** | `DuplicateGroupingTest` | 大小粗筛 + 哈希精筛组合算法 | 先按文件大小快速分组，大小不同直接跳过哈希计算，降低 I/O 开销 |
| **工具层** | `ActionPlanValidationTest` | 验证破坏性操作计划合法性校验 | 拒绝不存在的虚假路径，仅接受经过扫描录入的真实 Media ID / Document Uri |
| **工具层** | `CryptoKeyStoreTest` | 本地 AES-256-GCM 加密存储 | API Key 加密后存储为密文，解密后还原无损，密文随随机 IV 动态变化 |
| **编排层** | `ToolCallParserTest` | 验证大模型返回的 Tool Calls JSON 解析与校验 | 过滤非法参数，超范围请求直接拦截并抛出受控错误，防止提示词注入 |
| **编排层** | `ActionConfirmationStateMachineTest` | 确认状态机生命周期流转 | 未经用户确认（`user_confirmed=true`）前严禁触发实际删除/移动接口 |
| **存储层** | `StorageCalculationTest` | 存储体积格式化与类别聚合计算 | 正确处理 B, KB, MB, GB, TB 边界单位，统计数值与底层物理占用严丝合缝 |

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
