package com.jarves.mh.ui

import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.ChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRetryPolicyTest {
    @Test
    fun failedTaskCanBeRetried() {
        assertTrue(taskBlock("Task stopped").isRetryableTaskEnd("Task interrupted"))
    }

    @Test
    fun savedInterruptedTaskCanBeRetriedWithLocalizedTitle() {
        assertTrue(taskBlock("Задача прервана").isRetryableTaskEnd("Задача прервана"))
    }

    @Test
    fun completedTaskIsNotRetryable() {
        assertFalse(taskBlock("Task completed").isRetryableTaskEnd("Task interrupted"))
    }

    @Test
    fun aUserMessageIsNotRetryable() {
        assertFalse(ChatMessage(fromUser = true, text = "Build this").isRetryableTaskEnd("Task interrupted"))
    }

    @Test
    fun onlyTheFinalWorkItemDeterminesRetryEligibility() {
        val message = ChatMessage(
            fromUser = false,
            text = "",
            workItems = listOf(
                ActivityItem(title = "Task stopped", detail = "Previous attempt failed"),
                ActivityItem(title = "Task completed", detail = "A later attempt succeeded"),
            ),
        )
        assertFalse(message.isRetryableTaskEnd("Task interrupted"))
    }

    private fun taskBlock(title: String) = ChatMessage(
        fromUser = false,
        text = "",
        workItems = listOf(ActivityItem(title = title, detail = "Task outcome")),
    )
}
