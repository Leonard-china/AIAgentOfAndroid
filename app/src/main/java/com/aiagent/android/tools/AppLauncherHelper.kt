package com.aiagent.android.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.provider.Settings

data class AppLaunchResult(
    val success: Boolean,
    val message: String,
    val appName: String? = null,
    val packageName: String? = null
)

object AppLauncherHelper {

    // 常见流行应用中文名/拼音与官方包名映射别名表
    private val ALIAS_PACKAGES = mapOf(
        "b站" to listOf("tv.danmaku.bili", "com.bilibili.app.in"),
        "哔哩哔哩" to listOf("tv.danmaku.bili", "com.bilibili.app.in"),
        "bilibili" to listOf("tv.danmaku.bili", "com.bilibili.app.in"),
        "微信" to listOf("com.tencent.mm"),
        "wechat" to listOf("com.tencent.mm"),
        "支付宝" to listOf("com.eg.android.AlipayGphone"),
        "alipay" to listOf("com.eg.android.AlipayGphone"),
        "qq" to listOf("com.tencent.mobileqq", "com.tencent.tim"),
        "抖音" to listOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite"),
        "快手" to listOf("com.smile.gifmaker", "com.kuaishou.nebula"),
        "小红书" to listOf("com.xingin.xhs"),
        "微博" to listOf("com.sina.weibo"),
        "知乎" to listOf("com.zhihu.android"),
        "淘宝" to listOf("com.taobao.taobao"),
        "京东" to listOf("com.jingdong.app.mall"),
        "拼多多" to listOf("com.xunmeng.pinduoduo"),
        "网易云音乐" to listOf("com.netease.cloudmusic"),
        "网易云" to listOf("com.netease.cloudmusic"),
        "qq音乐" to listOf("com.tencent.qqmusic"),
        "高德地图" to listOf("com.autonavi.minimap"),
        "高德" to listOf("com.autonavi.minimap"),
        "百度地图" to listOf("com.baidu.BaiduMap"),
        "美团" to listOf("com.sankuai.meituan"),
        "饿了么" to listOf("me.ele"),
        "腾讯视频" to listOf("com.tencent.qqlive"),
        "爱奇艺" to listOf("com.qiyi.video"),
        "优酷" to listOf("com.youku.phone"),
        "百度网盘" to listOf("com.baidu.netdisk")
    )

    fun launchApp(context: Context, appNameQuery: String): AppLaunchResult {
        val trimmed = appNameQuery.trim()
        val queryLower = trimmed.lowercase()
        val pm = context.packageManager

        // 1. 处理系统特殊 Intent（设置、相机、相册）
        if (queryLower == "设置" || queryLower == "系统设置") {
            return try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                AppLaunchResult(true, "已成功为您打开系统设置页面。", "系统设置", "com.android.settings")
            } catch (e: Exception) {
                AppLaunchResult(false, "打开系统设置失败: ${e.message}")
            }
        }

        if (queryLower == "相机") {
            return try {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                AppLaunchResult(true, "已成功为您启动系统相机。", "相机", null)
            } catch (e: Exception) {
                AppLaunchResult(false, "启动相机失败: ${e.message}")
            }
        }

        // 2. 检查别名包名表（优先根据别名尝试启动）
        val aliasCandidates = ALIAS_PACKAGES[queryLower]
            ?: ALIAS_PACKAGES.entries.firstOrNull { queryLower.contains(it.key) || it.key.contains(queryLower) }?.value

        if (aliasCandidates != null) {
            for (pkg in aliasCandidates) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    return try {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        val appLabel = runCatching {
                            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                        }.getOrDefault(trimmed)
                        AppLaunchResult(
                            success = true,
                            message = "已成功通过原生 Intent 启动「$appLabel」应用。",
                            appName = appLabel,
                            packageName = pkg
                        )
                    } catch (e: Exception) {
                        AppLaunchResult(false, "启动应用失败: ${e.message}", trimmed, pkg)
                    }
                }
            }
        }

        // 3. 动态扫描已安装的所有可启动应用程序
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = runCatching {
            pm.queryIntentActivities(launcherIntent, 0)
        }.getOrDefault(emptyList())

        // 3.1 尝试精确匹配应用名
        var matched = resolveInfos.firstOrNull {
            val label = it.loadLabel(pm).toString()
            label.equals(trimmed, ignoreCase = true)
        }

        // 3.2 尝试包含模糊匹配应用名
        if (matched == null) {
            matched = resolveInfos.firstOrNull {
                val label = it.loadLabel(pm).toString().lowercase()
                label.contains(queryLower) || queryLower.contains(label)
            }
        }

        // 3.3 尝试匹配包名
        if (matched == null) {
            matched = resolveInfos.firstOrNull {
                it.activityInfo.packageName.lowercase().contains(queryLower)
            }
        }

        if (matched != null) {
            val targetPkg = matched.activityInfo.packageName
            val targetLabel = matched.loadLabel(pm).toString()
            val intent = pm.getLaunchIntentForPackage(targetPkg)
            if (intent != null) {
                return try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    AppLaunchResult(
                        success = true,
                        message = "已成功通过原生 Intent 启动「$targetLabel」应用。",
                        appName = targetLabel,
                        packageName = targetPkg
                    )
                } catch (e: Exception) {
                    AppLaunchResult(false, "启动应用「$targetLabel」失败: ${e.message}", targetLabel, targetPkg)
                }
            }
        }

        return AppLaunchResult(
            success = false,
            message = "未在手机中找到与「$trimmed」匹配的已安装应用。请确认应用是否已安装或输入更完整的应用名称。",
            appName = trimmed
        )
    }
}
