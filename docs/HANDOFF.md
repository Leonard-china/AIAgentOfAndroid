# 开发交接备忘录

更新时间：2026-10-18 12:08:18（Asia/Shanghai / UTC+08:00）

## 当前阶段与目标

已完成“手机跨应用任务智能体”第一轮架构纠偏、工作台改版与实机验证。用户优先目标为：**打开应用、搜索内容、继续完成操作**。文件与相册保留为本地原生工具。尚未达到任意应用、任意屏幕都可稳定操作的完整产品阶段。

接手先读 AGENTS.md → docs/README.md → 本文件 → PRODUCT.md / ARCHITECTURE.md / TESTING.md。不要沿用旧版本“100%成功”“无已知问题”等结论。

## 已实现且已验证

### 本地构建与回归

- 最终代码执行 `.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease`，BUILD SUCCESSFUL，24 秒。
- 8 个测试类、29 项，0 failures / 0 errors / 0 skipped；新增 7 项执行循环回归、1 项真实本机 HTTP 不完整响应取消测试，原有 21 项保留。
- 执行循环覆盖提前结束拦截、伪造/过期快照与证据拒绝、失败动作不能完成、重复无进展限制、需接手立即停批次、取消传播、动作预算。HTTP 测试观察取消后 socket 关闭。
- Release 已由 apksigner verify 验证成功，文件大小 46,023,277 字节，SHA-256：`2db2c2cfcdf4ac41c6578c8c84b6e01b4ab3b46d439d5880c5e0e9f748a30dc8`。
- 最终 Release 经 ADB push + `pm install -r -t` 覆盖安装，返回 Success；未清数据或导出密钥。

### 真机结果（仅限以下实际场景）

- 荣耀 VKJ-AN00 / Android 16 / MagicOS 10.0 / serial `AXRBUT6520002413`，设备配置模型 qwen-plus。
- “在哔哩哔哩搜索 3Blue1Brown，打开一个数学视频”：首轮 9 步自主完成，进入官方《AI 未能解决的最后一道 IMO 真题》详情页；相隔截图中的视频画面持续变化。助手报告引用真实标题，切回主界面保留 9 个步骤。最终 APK 覆盖安装后再次从已有视频页面出发，以 10 步完成同一目标，日志 `task finished status=COMPLETED steps=10`；结果与视频页面已保存为 final-report.xml/png、final-task.png。第二轮 finish_task 的原文证据为“简介”，虽来源真实但语义证明较弱，进一步说明仍需补强独立目标核验。
- 运行中顶部 HUD 停止与主界面停止分别实测：点击后均显示“已停止任务”，发送按钮恢复，无自动离线重放；日志出现 task cancelled by user。修复了旧 HUD 的停止回调在 show 清理时被清空的问题。
- 主界面、悬浮窗共用应用级 AgentConversation 历史和忙碌状态；悬浮窗打开可见相同记录；执行前隐藏悬浮聊天防遮挡，启动浮窗不再注入额外欢迎消息。
- 最后补充服务连接 StateFlow，真机覆盖安装后首页会自动显示“屏幕已连接”，避免重连晚于 onResume 时提示永久滞后。

### 实现要点

- `AgentLoop` 通用循环最多 24 轮、48 工具调用，外层 5 分钟；动作后回读真实屏幕，finish_task 区分完成/阻塞/需接手。普通文字回复与耗尽预算不会被当作屏幕任务完成。
- `ScreenSession` 当前快照持有真实节点；点击/输入绑定 snapshot_id 与 node_id，复核窗口、包名、可见性、启用状态、文本和边界。输入不再顺带点击视频；删除仍经原生文件确认流程。
- 屏幕操作同时受应用开关与无障碍连接控制，离线屏幕命令也遵守开关。密码字段脱敏；屏幕文字会发给用户配置的模型，设置页已如实说明；不再自动向系统提示注入整个记忆库。
- 停止取消当前协程与 OkHttp 请求；互斥保护多入口；三入口共享进程内历史。主导航为助手、工具、设置；存储/相册/审计归入工具。

## 已实现但未充分验证

- 通用多应用流程只完成 B 站专项；微信、浏览器、系统设置、商用应用等需要逐一建立任务矩阵。不可从一个成功任务推导通用成功率。
- finish_task 校验的是最新屏幕原文的出处和时效；**语义目标仍由模型判断**，不是独立的目标正确性验证器，也不能单凭标题证明播放。
- 过期引用与多候选拒绝已实现；复杂窗口/键盘/分屏、快速滚动、第三方自绘 UI 仍需更多真机覆盖。
- 顶部停止和 HTTP 取消已测；已派发给 Android 的单次动作无法自动撤销，不能承诺点击停止后撤销已发生动作。
- 大相册哈希并发、权限降级、回收站等本轮未重做实机回归，不沿用旧文档的“全量通过”断言。

## 尚未实现

1. 截图视觉理解与图像坐标定位；Canvas/游戏/受保护画面等可能无可读节点，目前需报告阻塞。
2. 多会话磁盘持久化、任务恢复、前台任务生命周期保障；当前会话仅进程内保存，进程结束会丢失。
3. 稳定的跨应用任务验收矩阵、目标独立语义核验与更清楚的接手/继续交互。
4. 千张相册压力测试、SAF 目录移动/重命名、本地语义搜图、分类相册/EXIF 预览等旧规划。

## 测试注意事项与当前外部条件

- 手机和已配置模型在本轮测试时可用；不等于以后永久在线，接手时重新检查 ADB / 模型连接。
- **运行中不要执行普通 uiautomator dump**：本轮它临时抑制无障碍服务，导致一个测试任务以 NEEDS_USER 结束后服务重连。运行中只用 screencap 和日志；若用 instrumentation，须使用 FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES。
- 主动切回本应用也会改变模型操作目标，不能把这种干扰后的失败算作自然场景失败样本。
- `onInterrupt` 是反馈中断而非解绑；当前已改为仅记录反馈中断，连接状态在 onServiceConnected / onUnbind / onDestroy 更新。
- 本地证据在被 Git 忽略的 artifacts/2026-10-18-review/，含 XML、截图和 verification.json；不会把个人屏幕内容提交到 Git。

## 下一步最优先任务

1. 加入由用户明确开启的截图视觉观察，结合可替换的视觉模型解决无障碍不可读画面，并记录屏幕内容上传范围与开关。
2. 增加浏览器/系统设置两个只读任务的端到端验收；先把失败反馈、接手和继续做可靠。
3. 会话落盘与历史切换、进程恢复，避免手机后台回收后丢任务。

## 构建与部署命令

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s AXRBUT6520002413 push app\build\outputs\apk\release\app-release.apk /data/local/tmp/aiagent-final.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s AXRBUT6520002413 shell pm install -r -t /data/local/tmp/aiagent-final.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s AXRBUT6520002413 shell am start -n com.aiagent.android/.ui.MainActivity
```

## 分支与提交

- 分支：master；本轮开始及当前 HEAD：4de95e4（此前功能提交 f6f239f）。
- 本轮改动尚未提交。不要把旧 HEAD 写成本轮代码提交。
- CHANGELOG 历史前缀已与 Git HEAD 内容对比确认保留，仅追加新记录。
