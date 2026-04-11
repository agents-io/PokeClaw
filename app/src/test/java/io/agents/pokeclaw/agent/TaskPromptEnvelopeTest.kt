package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPromptEnvelopeTest {

    @Test
    fun `build and parse preserve current request and chat history`() {
        val prompt = TaskPromptEnvelope.build(
            chatHistoryLines = listOf(
                "User: Please summarize the meeting notes.",
                "Assistant: Here is the summary of the meeting.",
            ),
            currentRequest = "Send that summary by email",
        )

        val parsed = TaskPromptEnvelope.parse(prompt)

        assertTrue(parsed.hasChatHistory)
        assertEquals("Send that summary by email", parsed.currentRequest)
        assertTrue(parsed.chatHistory!!.contains("Please summarize the meeting notes."))
        assertTrue(parsed.chatHistory!!.contains("Here is the summary of the meeting."))
    }

    @Test
    fun `plain prompt without envelope still parses as raw request`() {
        val parsed = TaskPromptEnvelope.parse("how much battery left")

        assertFalse(parsed.hasChatHistory)
        assertEquals("how much battery left", parsed.currentRequest)
    }
}
