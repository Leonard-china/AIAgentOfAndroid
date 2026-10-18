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
    SAVE_MEMORY,
    RECALL_MEMORY,
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
    RECOMMEND_VIDEO,
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
            // 0. 长时记忆保存与召回（跨会话永久生效）
            hasAny("记住我的", "记住我", "记住密码", "记住手机号", "保存记忆", "记下我的", "记一下我的") ->
                OfflineIntent.SAVE_MEMORY

            hasAny("我的手机号是多少", "我的密码是多少", "我叫什么", "你记住了什么", "查看记忆", "长时记忆", "我的记忆") ->
                OfflineIntent.RECALL_MEMORY

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

            // 7. 跨应用复合操作：看视频/推视频/搜视频任务（先于本地大视频检索与单纯打开应用）
            hasAny("推个视频", "推荐视频", "看视频", "推视频", "看b站", "刷视频", "看个视频", "播放视频", "推个b站视频", "推视频看", "搜视频", "找视频") ||
                    (lower.contains("推") && lower.contains("视频")) ||
                    (lower.contains("b站") && (lower.contains("视频") || lower.contains("搜") || lower.contains("找") || lower.contains("看") || lower.contains("播"))) ||
                    lower.contains("3blue1brown") -> OfflineIntent.RECOMMEND_VIDEO

            // 8. 相册/文件分析类
            hasAny("截图", "截屏") -> OfflineIntent.SCREENSHOTS
            hasAny("安装包", "apk") -> OfflineIntent.APK_FILES
            hasAny("相似", "连拍") -> OfflineIntent.SIMILAR_PHOTOS
            hasAny("重复", "查重") -> OfflineIntent.DUPLICATE_FILES
            hasAny("视频", "500mb", "大文件") -> OfflineIntent.LARGE_VIDEOS

            // 9. 存储设置页：这些短语需要先于泛化的“存储”统计分支
            hasAny("系统存储", "存储设置", "存储管理", "打开设置", "清理页面", "系统设置", "设置页面") ->
                OfflineIntent.OPEN_STORAGE_SETTINGS

            // 10. 启动/打开应用：优先原生 Intent 调起外部 App（如“打开B站”、“启动微信”）
            hasAny("打开", "启动", "运行") || hasAny("b站", "哔哩哔哩", "微信", "支付宝", "抖音", "小红书", "快手", "微博", "知乎", "网易云", "淘宝", "京东", "拼多多", "高德") ->
                OfflineIntent.LAUNCH_APP

            // 11. 存储总览：最后匹配宽泛的“存储/占空间”
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

    /**
     * 从用户的视频搜索/推荐指令中提取搜索关键词
     * 例如："打开B站并为我推荐一个3blue1brown的科普视频" -> "3blue1brown"
     * "在B站搜3blue1brown看" -> "3blue1brown"
     */
    fun extractVideoSearchKeyword(prompt: String): String? {
        val lower = prompt.lowercase()
        if (lower.contains("3blue1brown")) {
            return "3blue1brown"
        }
        val patterns = listOf(
            Regex("""(?:搜|找)(.+?)(?:的?(?:科普)?视频|视频|看|播放|$)"""),
            Regex("""(?:推荐|推)(?:一个|部|点)?(.+?)(?:的?(?:科普)?视频|视频)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(prompt)
            if (match != null && match.groupValues.size > 1) {
                var candidate = match.groupValues[1].trim()
                val stopWords = listOf("在b站", "打开b站", "哔哩哔哩", "b站", "并为我", "给我", "一个", "个", "部", "点", "看", "相关")
                for (sw in stopWords) {
                    candidate = candidate.replace(sw, "", ignoreCase = true).trim()
                }
                if (candidate.isNotEmpty() && candidate.length <= 30 && candidate !in listOf("好看", "有趣", "精彩", "热门", "推荐", "短", "搞笑", "最新")) {
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * 从记忆指令中提取键值与备注
     * 例如："记住我的手机号是13812345678" -> key="phone", value="13812345678", label="手机号"
     */
    fun extractMemoryKeyValue(prompt: String): Triple<String, String, String>? {
        val lower = prompt.lowercase()
        // 手机号检测
        val phoneRegex = Regex("""(?:手机号|电话|手机号码|号码)(?:是|为|:|：|\s+)?(\d{11})""")
        val phoneMatch = phoneRegex.find(prompt)
        if (phoneMatch != null) {
            val phone = phoneMatch.groupValues[1]
            return Triple("phone", phone, "手机号")
        }

        // 密码检测
        val pwdRegex = Regex("""(?:密码)(?:是|为|:|：|\s+)?([A-Za-z0-9@#\$%^&*_\-\.]{4,32})""")
        val pwdMatch = pwdRegex.find(prompt)
        if (pwdMatch != null) {
            val pwd = pwdMatch.groupValues[1]
            return Triple("password", pwd, "登录密码")
        }

        // 称呼检测
        val nameRegex = Regex("""(?:叫我|我叫|称呼我为|我的名字是)(.+?)$""")
        val nameMatch = nameRegex.find(prompt)
        if (nameMatch != null) {
            val name = nameMatch.groupValues[1].trim()
            if (name.isNotEmpty()) {
                return Triple("user_name", name, "用户称呼")
            }
        }

        return null
    }
}
