package com.aiagent.android.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object ToolRegistry {

    fun getToolsSchema(): JsonArray {
        val tools = JsonArray()

        // 1. query_storage_stats
        tools.add(createToolFunction(
            name = "query_storage_stats",
            description = "查询手机闪存存储总体占用情况、可用空间及各分类（图片、视频、音频、文档、安装包、其他）占用大小",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            }
        ))

        // 2. query_large_files
        tools.add(createToolFunction(
            name = "query_large_files",
            description = "查询超过指定大小的视频或大文件，支持按文件大小降序排序",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val minSize = JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "最小文件大小门槛（单位：MB，例如 500 代表 500MB）")
                    }
                    add("min_size_mb", minSize)
                }
                add("properties", props)
                val required = JsonArray().apply { add("min_size_mb") }
                add("required", required)
            }
        ))

        // 3. find_duplicate_files
        tools.add(createToolFunction(
            name = "find_duplicate_files",
            description = "排查完全重复文件（基于文件大小与完整内容哈希严格比对，安全可靠）",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            }
        ))

        // 4. query_screenshots
        tools.add(createToolFunction(
            name = "query_screenshots",
            description = "查询相册中的屏幕截图（可用于整理或清理无用截图）",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            }
        ))

        // 5. query_apk_files
        tools.add(createToolFunction(
            name = "query_apk_files",
            description = "查询下载或存储目录中的旧应用安装包（.apk）",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            }
        ))

        // 6. propose_trash_plan
        tools.add(createToolFunction(
            name = "propose_trash_plan",
            description = "为指定的一组真实文件生成移入系统媒体回收站的操作预览卡片。生成后必须等待用户在界面确认方可执行！",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val ids = JsonObject().apply {
                        addProperty("type", "array")
                        val items = JsonObject().apply { addProperty("type", "integer") }
                        add("items", items)
                        addProperty("description", "拟移入回收站的真实文件本地 ID 列表（必须来自此前的查询结果）")
                    }
                    val reason = JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "生成此建议的合理解释（例如：多余的重复照片、占用过大的超大视频等）")
                    }
                    add("target_ids", ids)
                    add("reason", reason)
                }
                add("properties", props)
                val required = JsonArray().apply {
                    add("target_ids")
                    add("reason")
                }
                add("required", required)
            }
        ))

        // 7. open_storage_settings
        tools.add(createToolFunction(
            name = "open_storage_settings",
            description = "在用户主动授权下，辅助调起 Android 系统设置中的存储管理页面",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            }
        ))

        // 8. find_similar_photos
        tools.add(createToolFunction(
            name = "find_similar_photos",
            description = "排查相册中视觉相似或连拍的照片候选分组（基于轻量感知哈希与时间戳聚类，不直接删除，仅供用户挑选保留）",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            }
        ))

        // 9. operate_screen_click
        tools.add(createToolFunction(
            name = "operate_screen_click",
            description = "模拟屏幕点击操作（支持连点器手势模式与文字按钮匹配）：可通过文字匹配点击，或直接指定屏幕坐标 (x, y) 模拟手势点击",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val textProp = JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "要点击的屏幕文字或按钮名称（例如：'设置'、'存储空间'、'确认'）")
                    }
                    val xProp = JsonObject().apply {
                        addProperty("type", "number")
                        addProperty("description", "点击的屏幕横坐标 X（像素），用于连点器坐标点击")
                    }
                    val yProp = JsonObject().apply {
                        addProperty("type", "number")
                        addProperty("description", "点击的屏幕纵坐标 Y（像素），用于连点器坐标点击")
                    }
                    add("text", textProp)
                    add("x", xProp)
                    add("y", yProp)
                }
                add("properties", props)
            }
        ))

        // 10. operate_screen_swipe
        tools.add(createToolFunction(
            name = "operate_screen_swipe",
            description = "模拟屏幕手势滑动操作（用于浏览长列表、上下翻页或左右切换）",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val dirProp = JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "滑动方向：UP(向上滑/浏览下方内容), DOWN(向下滑/浏览上方内容), LEFT(向左滑), RIGHT(向右滑)")
                    }
                    add("direction", dirProp)
                }
                add("properties", props)
                val required = JsonArray().apply { add("direction") }
                add("required", required)
            }
        ))

        // 11. operate_screen_global_action
        tools.add(createToolFunction(
            name = "operate_screen_global_action",
            description = "执行系统全局按键动作：返回（BACK）、回到桌面（HOME）、多任务列表（RECENTS）、通知中心（NOTIFICATIONS）",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val actProp = JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "全局动作名称：BACK, HOME, RECENTS, NOTIFICATIONS")
                    }
                    add("action", actProp)
                }
                add("properties", props)
                val required = JsonArray().apply { add("action") }
                add("required", required)
            }
        ))

        // 12. inspect_current_screen
        tools.add(createToolFunction(
            name = "inspect_current_screen",
            description = "【极度省 Token 纯文本】获取当前屏幕上可见的文本、按钮及坐标摘要，不耗费视觉 Token。在需要点击前可先查看屏幕上有哪些元素",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            }
        ))

        // 13. open_accessibility_settings
        tools.add(createToolFunction(
            name = "open_accessibility_settings",
            description = "调起系统无障碍设置页面，指引用户开启辅助操作服务",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            }
        ))

        // 14. query_file_detail
        tools.add(createToolFunction(
            name = "query_file_detail",
            description = "【极度省 Token】仅在用户明确需要查看某一个具体文件的详细信息时调用，严格限制单文件查询，严禁用于遍历相册",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val idProp = JsonObject().apply {
                        addProperty("type", "integer")
                        addProperty("description", "目标文件的本地唯一 ID")
                    }
                    add("file_id", idProp)
                }
                add("properties", props)
                val required = JsonArray().apply { add("file_id") }
                add("required", required)
            }
        ))

        // 15. open_floating_window
        tools.add(createToolFunction(
            name = "open_floating_window",
            description = "在用户要求时启动桌面悬浮窗助手，开启后在手机桌面与任意应用上层显示悬浮球，支持随时随地对话与操作屏幕",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject())
            }
        ))

        return tools
    }

    private fun createToolFunction(
        name: String,
        description: String,
        parameters: JsonObject
    ): JsonObject {
        return JsonObject().apply {
            addProperty("type", "function")
            val fn = JsonObject().apply {
                addProperty("name", name)
                addProperty("description", description)
                add("parameters", parameters)
            }
            add("function", fn)
        }
    }
}
