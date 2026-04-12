package io.agents.pokeclaw.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailComposeGuardTest {

    @Test
    fun explicitEmailComposeTask_isGuarded() {
        val guard = EmailComposeGuard.fromTask("Write an email saying I will be late today")
        assertTrue(guard.shouldBlockTextOnlyCompletion())
    }

    @Test
    fun genericWritingTask_isNotGuarded() {
        val guard = EmailComposeGuard.fromTask("Write a short apology note")
        assertFalse(guard.shouldBlockTextOnlyCompletion())
    }
}
