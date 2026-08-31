package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TaskParserTest {

    @Test
    fun `send message command routes to direct send message tool`() {
        val parsed = TaskParser.parse("send hi to Girlfriend on WhatsApp")

        assertNotNull(parsed)
        assertEquals("send_message", parsed!!.action)
        assertEquals("send_message", parsed.toolName)
        assertEquals("hi", parsed.toolParams!!["message"])
        assertEquals("Girlfriend", parsed.toolParams!!["contact"])
        assertEquals("WhatsApp", parsed.toolParams!!["app"])
    }

    @Test
    fun `send contextual message still falls through to agent`() {
        assertNull(TaskParser.parse("send that to Girlfriend on WhatsApp"))
    }

    @Test
    fun `email commands do not route to messaging app tool`() {
        assertNull(TaskParser.parse("send email to nicole@example.com"))
    }
}
