package com.aiagent.android.ai

/**
 * 离线规则规划引擎的意图分类器。
 *
 * 从 [AgentOrchestrator.executeOfflineRulePlan] 中抽离出的纯函数，不依赖 Android 框架，
 * 因此可以编写 JVM 单元测试覆盖所有关键指令，避免关键词顺序回归（例如
 * “打开桌面悬浮窗” 曾被更宽泛的 “桌面” 分支抢先匹配，错误地执行 HOME）。
 *
 * 匹配原则：**先具体、后宽泛**。每个分支只负责判断意图，具体执行逻辑仍在编排器中。
 */
enum class OfflineIntent {
    NEW_CHAT,
    FLOATING_WINDOW,
    OPEN_ACCESSIBILITY_SETTINGS,
    INSPECT_SCREEN,
    SCREEN_CLICK,
    SCREEN_SWIPE,
    SCREEN_GLOBAL,
    SCREENSHOTS,
    APK_FILES,
    SIMILAR_PHOTOS,
    DUPLICATE_FILES,
    LARGE_VIDEOS,
    OPEN_STORAGE_SETTINGS,
    LAUNCH_APP,
    STORAGE_OVERVIEW,
    UNKNOWN
}

object OfflineIntentClassifier {

    fun classify(prompt: String): OfflineIntent {
        val lower = prompt.lowercase()

        fun hasAny(vararg keywords: String): Boolean = keywords.any { lower.contains(it) }

        return when {
            // 1. 会话重置：最高优先级，且必须精确短语，避免误伤普通对话
            hasAny("开新对话", "新建对话", "清空对话", "重新开始") -> OfflineIntent.NEW_CHAT

            // 2. 悬浮窗：必须先于“桌面/返回”等宽泛分支（“打开桌面悬浮窗”）
            hasAny("悬浮窗", "悬浮球", "浮窗", "悬浮") -> OfflineIntent.FLOATING_WINDOW

            // 3. 无障碍设置：必须先于泛化的“设置”分支（“打开无障碍设置”）
            hasAny("无障碍", "连点器", "辅助服务") -> OfflineIntent.OPEN_ACCESSIBILITY_SETTINGS

            // 4. 查看屏幕：必须在“点击/滑动/桌面”之前，并兼容“查看当前屏幕”
            hasAny("查看当前屏幕", "当前屏幕", "查看屏幕", "看屏幕", "屏幕有什么", "屏幕上有什么", "屏幕元素") ->
                OfflineIntent.INSPECT_SCREEN

            // 5. 点击/连点：必须先于“设置”分支（“帮我点设置”应点击“设置”文字，而不是打开设置页）
            hasAny("点击", "点一下", "按一下", "点按", "帮我点", "单击", "连点") -> OfflineIntent.SCREEN_CLICK

            // 6. 滑动与全局按键
            hasAny("滑动", "下滑", "上滑", "翻页") -> OfflineIntent.SCREEN_SWIPE
            hasAny("返回", "回桌面", "回到桌面", "桌面") -> OfflineIntent.SCREEN_GLOBAL

            // 7. 相册/文件分析类
            hasAny("截图", "截屏") -> OfflineIntent.SCREENSHOTS
            hasAny("安装包", "apk") -> OfflineIntent.APK_FILES
            hasAny("相似", "连拍") -> OfflineIntent.SIMILAR_PHOTOS
            hasAny("重复", "查重") -> OfflineIntent.DUPLICATE_FILES
            hasAny("视频", "500mb", "大文件") -> OfflineIntent.LARGE_VIDEOS

            // 8. 存储设置页：这些短语需要先于泛化的“存储”统计分支
            hasAny("系统存储", "存储设置", "存储管理", "打开设置", "清理页面", "系统设置", "设置页面") ->
                OfflineIntent.OPEN_STORAGE_SETTINGS

            // 9. 启动/打开应用：优先原生 Intent 调起外部 App（如“打开B站”、“启动微信”）
            hasAny("打开", "启动", "运行") || hasAny("b站", "哔哩哔哩", "微信", "支付宝", "抖音", "小红书", "快手", "微博", "知乎", "网易云", "淘宝", "京东", "拼多多", "高德") ->
                OfflineIntent.LAUNCH_APP

            // 10. 存储总览：最后匹配宽泛的“存储/占空间”
            hasAny("占空间", "什么最占", "存储", "空间占用") -> OfflineIntent.STORAGE_OVERVIEW

            else -> OfflineIntent.UNKNOWN
        }
    }

    /**
     * 从用户的打开指令中提取目标应用名称或别名
     * 例如："打开B站" -> "B站"，"请帮我启动哔哩哔哩客户端" -> "哔哩哔哩"
     */
    fun extractAppName(prompt: String): String {
        var clean = prompt.trim()
        val prefixes = listOf("帮我打开", "请帮我打开", "帮我启动", "请打开", "请启动", "立即打开", "打开", "启动", "运行", "帮我进入", "进入")
        for (prefix in prefixes) {
            if (clean.startsWith(prefix, ignoreCase = true)) {
                clean = clean.substring(prefix.length).trim()
                break
            }
        }
        val suffixes = listOf("应用", "软件", "app", "客户端")
        for (suffix in suffixes) {
            if (clean.endsWith(suffix, ignoreCase = true)) {
                clean = clean.substring(0, clean.length - suffix.length).trim()
                break
            }
        }
        return clean.ifBlank { prompt.trim() }
    }
}
