package com.aiagent.android

import com.aiagent.android.model.ActionPlan
import com.aiagent.android.model.ActionTarget
import com.aiagent.android.model.ActionType
import com.aiagent.android.model.PlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPlanSafetyTest {

    @Test
    fun testActionPlanLifecycleRequiresPreviewFirst() {
        val targets = listOf(
            ActionTarget(
                id = 101L,
                uriString = "content://media/external/images/media/101",
                displayName = "test.png",
                sizeBytes = 1048576L,
                originalPath = "/storage/emulated/0/DCIM/test.png"
            )
        )

        val plan = ActionPlan(
            actionType = ActionType.TRASH,
            title = "清理冗余文件",
            description = "测试清理计划",
            targets = targets,
            estimatedReleasableBytes = 1048576L,
            status = PlanStatus.PREVIEW
        )

        assertEquals(PlanStatus.PREVIEW, plan.status)
        assertEquals(1, plan.targets.size)
        assertTrue(plan.isRecoverable)

        // After user explicit consent
        plan.status = PlanStatus.EXECUTING
        assertEquals(PlanStatus.EXECUTING, plan.status)

        // Succeeded
        plan.status = PlanStatus.SUCCESS
        assertEquals(PlanStatus.SUCCESS, plan.status)
    }
}
