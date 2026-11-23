package com.llamatik.agent.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskIntentConversationTest {
    @Test
    fun `creative writing without code is a conversation`() {
        assertTrue(TaskIntent.isConversation("tell me a story about a forest"))
        assertTrue(TaskIntent.isConversation("write a nice poem"))
        assertFalse(TaskIntent.wantsEdit("write a nice story"))
    }

    @Test
    fun `requests that mention code or files stay with the agent`() {
        assertFalse(TaskIntent.isConversation("write the code for a screen that shows a story about a forest"))
        assertFalse(TaskIntent.isConversation("add a joke to README.md"))
        assertFalse(TaskIntent.isConversation("write a story in @{notes.txt}"))
        assertFalse(TaskIntent.isConversation("refactor the login function"))
        assertFalse(TaskIntent.isConversation("what does this project do?"))
    }
}
