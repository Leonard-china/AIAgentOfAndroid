package com.aiagent.android

import com.aiagent.android.ai.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    @Test
    fun testToolRegistryDeclaresAllCoreTools() {
        val tools = ToolRegistry.getToolsSchema()
        assertEquals(22, tools.size())

        val toolNames = mutableSetOf<String>()
        for (elem in tools) {
            val fn = elem.asJsonObject.getAsJsonObject("function")
            toolNames.add(fn.get("name").asString)
        }

        assertTrue(toolNames.contains("query_storage_stats"))
        assertTrue(toolNames.contains("query_large_files"))
        assertTrue(toolNames.contains("find_duplicate_files"))
        assertTrue(toolNames.contains("find_similar_photos"))
        assertTrue(toolNames.contains("query_screenshots"))
        assertTrue(toolNames.contains("query_apk_files"))
        assertTrue(toolNames.contains("propose_trash_plan"))
        assertTrue(toolNames.contains("open_storage_settings"))
        assertTrue(toolNames.contains("operate_screen_click"))
        assertTrue(toolNames.contains("operate_screen_swipe"))
        assertTrue(toolNames.contains("operate_screen_global_action"))
        assertTrue(toolNames.contains("inspect_current_screen"))
        assertTrue(toolNames.contains("open_accessibility_settings"))
        assertTrue(toolNames.contains("query_file_detail"))
        assertTrue(toolNames.contains("open_floating_window"))
        assertTrue(toolNames.contains("launch_app"))
        assertTrue(toolNames.contains("operate_screen_input"))
        assertTrue(toolNames.contains("save_long_term_memory"))
        assertTrue(toolNames.contains("recall_long_term_memory"))
        assertTrue(toolNames.contains("finish_task"))
        assertTrue(toolNames.contains("inspect_screen_visual"))
        assertTrue(toolNames.contains("execute_action_pipeline"))
    }

    @Test
    fun testProposeTrashPlanHasStrictRequirements() {
        val tools = ToolRegistry.getToolsSchema()
        val trashTool = tools.first {
            it.asJsonObject.getAsJsonObject("function").get("name").asString == "propose_trash_plan"
        }.asJsonObject.getAsJsonObject("function")

        val params = trashTool.getAsJsonObject("parameters")
        val required = params.getAsJsonArray("required")
        assertNotNull(required)
        val requiredList = required.map { it.asString }
        assertTrue(requiredList.contains("target_ids"))
        assertTrue(requiredList.contains("reason"))
    }
}
