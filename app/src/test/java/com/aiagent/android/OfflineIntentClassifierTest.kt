package com.aiagent.android

import com.aiagent.android.ai.OfflineIntent
import com.aiagent.android.ai.OfflineIntentClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineIntentClassifierTest {

    @Test
    fun testFloatingWindowWinsOverDesktopKeyword() {
        // Regression: “打开桌面悬浮窗” 曾先命中 “桌面” 分支并错误执行 HOME
        assertEquals(OfflineIntent.FLOATING_WINDOW, OfflineIntentClassifier.classify("打开桌面悬浮窗"))
        assertEquals(OfflineIntent.FLOATING_WINDOW, OfflineIntentClassifier.classify("把悬浮窗贴边"))
        assertEquals(OfflineIntent.FLOATING_WINDOW, OfflineIntentClassifier.classify("隐藏悬浮球"))
    }

    @Test
    fun testScreenClickWinsOverSettingsKeyword() {
        // Regression: “帮我点设置” 曾先命中 “设置” 分支并打开系统存储设置页
        assertEquals(OfflineIntent.SCREEN_CLICK, OfflineIntentClassifier.classify("帮我点设置"))
        assertEquals(OfflineIntent.SCREEN_CLICK, OfflineIntentClassifier.classify("点击确认"))
        assertEquals(OfflineIntent.OPEN_STORAGE_SETTINGS, OfflineIntentClassifier.classify("打开设置"))
        assertEquals(OfflineIntent.OPEN_STORAGE_SETTINGS, OfflineIntentClassifier.classify("打开系统存储管理页面"))
    }

    @Test
    fun testAccessibilityAndInspectScreenIntents() {
        // Regression: “打开无障碍设置” 曾先命中 “设置” 分支
        assertEquals(OfflineIntent.OPEN_ACCESSIBILITY_SETTINGS, OfflineIntentClassifier.classify("打开无障碍设置"))
        assertEquals(OfflineIntent.OPEN_ACCESSIBILITY_SETTINGS, OfflineIntentClassifier.classify("开启辅助服务"))
        // Regression: “查看当前屏幕” 曾匹配不上 “查看屏幕”
        assertEquals(OfflineIntent.INSPECT_SCREEN, OfflineIntentClassifier.classify("查看当前屏幕"))
        assertEquals(OfflineIntent.INSPECT_SCREEN, OfflineIntentClassifier.classify("屏幕上有什么"))
    }

    @Test
    fun testStorageAndMediaAnalysisIntents() {
        assertEquals(OfflineIntent.STORAGE_OVERVIEW, OfflineIntentClassifier.classify("看看手机里什么最占空间"))
        assertEquals(OfflineIntent.STORAGE_OVERVIEW, OfflineIntentClassifier.classify("存储空间占用"))
        assertEquals(OfflineIntent.LARGE_VIDEOS, OfflineIntentClassifier.classify("找出超过 500MB 的视频"))
        assertEquals(OfflineIntent.DUPLICATE_FILES, OfflineIntentClassifier.classify("排查重复照片"))
        assertEquals(OfflineIntent.SIMILAR_PHOTOS, OfflineIntentClassifier.classify("排查相似照片"))
        assertEquals(OfflineIntent.SCREENSHOTS, OfflineIntentClassifier.classify("整理相册截图"))
        assertEquals(OfflineIntent.APK_FILES, OfflineIntentClassifier.classify("找出下载目录里的安装包"))
        assertEquals(OfflineIntent.NEW_CHAT, OfflineIntentClassifier.classify("开新对话"))
        assertEquals(OfflineIntent.SCREEN_GLOBAL, OfflineIntentClassifier.classify("返回桌面"))
        assertEquals(OfflineIntent.SCREEN_SWIPE, OfflineIntentClassifier.classify("向下滑动"))
    }

    @Test
    fun testLaunchAppIntents() {
        assertEquals(OfflineIntent.LAUNCH_APP, OfflineIntentClassifier.classify("打开B站"))
        assertEquals(OfflineIntent.LAUNCH_APP, OfflineIntentClassifier.classify("帮我打开哔哩哔哩"))
        assertEquals(OfflineIntent.LAUNCH_APP, OfflineIntentClassifier.classify("启动微信"))
        assertEquals(OfflineIntent.LAUNCH_APP, OfflineIntentClassifier.classify("打开抖音"))
        assertEquals(OfflineIntent.LAUNCH_APP, OfflineIntentClassifier.classify("运行知乎客户端"))
    }

    @Test
    fun testRecommendVideoIntents() {
        assertEquals(OfflineIntent.RECOMMEND_VIDEO, OfflineIntentClassifier.classify("给我推个B站视频看"))
        assertEquals(OfflineIntent.RECOMMEND_VIDEO, OfflineIntentClassifier.classify("帮我推荐一个视频看"))
        assertEquals(OfflineIntent.RECOMMEND_VIDEO, OfflineIntentClassifier.classify("在B站看视频"))
        assertEquals(OfflineIntent.RECOMMEND_VIDEO, OfflineIntentClassifier.classify("刷视频"))
        assertEquals(OfflineIntent.RECOMMEND_VIDEO, OfflineIntentClassifier.classify("打开B站并为我找一个有趣的科普视频"))
    }

    @Test
    fun testExtractAppName() {
        assertEquals("B站", OfflineIntentClassifier.extractAppName("打开B站"))
        assertEquals("哔哩哔哩", OfflineIntentClassifier.extractAppName("帮我打开哔哩哔哩应用"))
        assertEquals("微信", OfflineIntentClassifier.extractAppName("请启动微信客户端"))
        assertEquals("抖音", OfflineIntentClassifier.extractAppName("立即打开抖音"))
    }

    @Test
    fun testUnknownFallsBackToHelp() {
        assertEquals(OfflineIntent.UNKNOWN, OfflineIntentClassifier.classify("今天天气怎么样"))
        assertEquals(OfflineIntent.UNKNOWN, OfflineIntentClassifier.classify("你好"))
    }
}
