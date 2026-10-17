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
