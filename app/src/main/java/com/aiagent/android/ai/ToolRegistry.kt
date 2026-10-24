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

        // Targets must come from the latest local screen snapshot.
        tools.add(createToolFunction(
            name = "operate_screen_click",
            description = "点击最新 screen 中的真实控件。优先 snapshot_id + node_id；视觉定位后可用 snapshot_id + visual_id + visual_target_id，执行前会重新截屏比对；禁止任意坐标。",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    listOf("snapshot_id", "node_id", "text", "visual_id", "visual_target_id").forEach { key ->
                        add(key, JsonObject().apply { addProperty("type", "string") })
                    }
                })
                add("required", JsonArray().apply { add("snapshot_id") })
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
            description = "读取当前应用的真实无障碍控件，返回 snapshot_id、node id、文本、角色、边界和状态。自绘或受保护画面可能不可读。",
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

        // 16. launch_app
        tools.add(createToolFunction(
            name = "launch_app",
            description = "通过原生 Intent 启动本机已安装的应用，并返回当前 screen。必须观察真实界面再继续搜索或后续操作；发出启动请求不代表目标完成。",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val nameProp = JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "要打开的应用名称或别名（例如：'B站'、'哔哩哔哩'、'微信'、'支付宝'、'设置'）")
                    }
                    add("app_name", nameProp)
                }
                add("properties", props)
                val required = JsonArray().apply { add("app_name") }
                add("required", required)
            }
        ))

        // 17. operate_screen_input
        tools.add(createToolFunction(
            name = "operate_screen_input",
            description = "在最新 screen 中的真实输入框填写文本。必须提供 snapshot_id，推荐 node_id。submit 默认 false；true 仅发出输入法提交动作，不会点击其他内容。",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val textProp = JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "要输入的文字内容（例如搜索关键词、消息文本等）")
                    }
                    val submitProp = JsonObject().apply {
                        addProperty("type", "boolean")
                        addProperty("description", "输入后是否发出输入法提交动作（默认 false）；发送消息需用户已明确授权")
                    }
                    add("text", textProp)
                    add("submit", submitProp)
                    add("snapshot_id", JsonObject().apply { addProperty("type", "string") })
                    add("node_id", JsonObject().apply { addProperty("type", "string") })
                }
                add("properties", props)
                val required = JsonArray().apply { add("text"); add("snapshot_id") }
                add("required", required)
            }
        ))

        // 18. save_long_term_memory
        tools.add(createToolFunction(
            name = "save_long_term_memory",
            description = "在手机硬件加密库中永久记住用户的长时信息（如手机号码、密码、个人称呼、使用偏好等）。即使开启新对话也永久保留！",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val keyProp = JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "记忆键名，例如：'phone'(手机号)、'password'(密码)、'nickname'(称呼)、'pref_video'(偏好题材)")
                    }
                    val valProp = JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "要永久记住的信息或偏好内容（本地 AES-256 硬件加密保存）")
                    }
                    val lblProp = JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "可读说明标签，例如：'我的手机号'、'登录密码'、'喜欢的科普UP主'")
                    }
                    add("key", keyProp)
                    add("value", valProp)
                    add("label", lblProp)
                }
                add("properties", props)
                val required = JsonArray().apply {
                    add("key")
                    add("value")
                }
                add("required", required)
            }
        ))

        // 19. recall_long_term_memory
        tools.add(createToolFunction(
            name = "recall_long_term_memory",
            description = "读取已加密保存的用户长时记忆（如取回手机号或密码用于在当前屏幕自动输入）",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                val props = JsonObject().apply {
                    val keyProp = JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "要查询的键名，例如：'phone'、'password'，留空则返回所有已存记忆条目")
                    }
                    add("key", keyProp)
                }
                add("properties", props)
            }
        ))

        tools.add(createToolFunction(
            name = "inspect_screen_visual",
            description = "仅在用户已开启截图视觉并配置视觉模型后，按需观察当前应用截图；用于无障碍控件不可读时理解画面或定位。截图只在内存中发送给用户配置的视觉接口，不保存。受保护/密码界面拒绝截图。",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("focus", JsonObject().apply { addProperty("type", "string"); addProperty("description", "本次需要理解或定位的内容，不超过500字") })
                })
                add("required", JsonArray().apply { add("focus") })
            }
        ))

        tools.add(createToolFunction(
            name = "execute_action_pipeline",
            description = "预判并快速连贯执行一组确定性高的屏幕动作序列（如打开应用并搜索：launch_app -> wait_and_click -> wait_and_input）。端侧在本地毫秒级监听目标控件出现后立即连击，无需中间多轮网络往返。若目标未出现或遇到非预期弹窗，会安全熔断并返回当前实际界面供重新决策。严禁包含删除、付款等破坏性操作。",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    val stepsProp = JsonObject().apply {
                        addProperty("type", "array")
                        addProperty("description", "预判动作列表（最多4步）")
                        val itemSchema = JsonObject().apply {
                            addProperty("type", "object")
                            add("properties", JsonObject().apply {
                                add("action", JsonObject().apply {
                                    addProperty("type", "string")
                                    add("enum", JsonArray().apply {
                                        add("launch_app"); add("wait_and_click"); add("wait_and_input"); add("swipe")
                                    })
                                    addProperty("description", "动作类型")
                                })
                                add("app_name", JsonObject().apply { addProperty("type", "string"); addProperty("description", "launch_app 时的应用名，如 '哔哩哔哩'") })
                                add("target_text", JsonObject().apply { addProperty("type", "string"); addProperty("description", "wait_and_click 或 wait_and_input 需匹配的文字/描述，如 '搜索'") })
                                add("text", JsonObject().apply { addProperty("type", "string"); addProperty("description", "wait_and_input 填入的文本") })
                                add("submit", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "输入后是否回车提交，默认 false") })
                                add("direction", JsonObject().apply {
                                    addProperty("type", "string")
                                    add("enum", JsonArray().apply { add("UP"); add("DOWN"); add("LEFT"); add("RIGHT") })
                                })
                                add("timeout_ms", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "等待超时毫秒数，默认 3500ms") })
                            })
                            add("required", JsonArray().apply { add("action") })
                        }
                        add("items", itemSchema)
                    }
                    add("steps", stepsProp)
                })
                add("required", JsonArray().apply { add("steps") })
            }
        ))

        tools.add(createToolFunction(
            name = "finish_task",
            description = "屏幕任务的结束报告。只读理解且未改变屏幕时可用 answered 引用观察证据；completed 必须引用最新 snapshot_id 和节点/视觉识别原文 evidence；受阻或需用户接手用 blocked/needs_user，summary 说明具体原因。",
            parameters = JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    listOf("status", "summary", "snapshot_id", "evidence").forEach { key ->
                        add(key, JsonObject().apply {
                            addProperty("type", "string")
                            if (key == "status") add("enum", JsonArray().apply {
                                add("completed"); add("answered"); add("blocked"); add("needs_user")
                            })
                        })
                    }
                })
                add("required", JsonArray().apply { add("status"); add("summary") })
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
