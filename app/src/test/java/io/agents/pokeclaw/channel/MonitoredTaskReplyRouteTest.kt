package io.agents.pokeclaw.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoredTaskReplyRouteTest {

    @Test
    fun `monitored task responses wait until that task is flushed`() {
        val delivered = mutableListOf<String>()
        val taskId = "monitor-route-buffer"
        ChannelManager.registerExternalReplyRoute(taskId) { delivered += it }

        ChannelManager.sendMessage(Channel.WHATSAPP_MONITOR, "Opened Uber", taskId)
        ChannelManager.sendMessage(Channel.WHATSAPP_MONITOR, "Done", taskId)

        assertTrue(delivered.isEmpty())

        ChannelManager.flushMessages(Channel.WHATSAPP_MONITOR, taskId)

        assertEquals(listOf("Opened Uber\n\nDone"), delivered)
    }

    @Test
    fun `flushing one monitored task never delivers another tasks response`() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val firstId = "monitor-route-first"
        val secondId = "monitor-route-second"
        ChannelManager.registerExternalReplyRoute(firstId) { first += it }
        ChannelManager.registerExternalReplyRoute(secondId) { second += it }

        ChannelManager.sendMessage(Channel.WHATSAPP_MONITOR, "first result", firstId)
        ChannelManager.sendMessage(Channel.WHATSAPP_MONITOR, "second result", secondId)
        ChannelManager.flushMessages(Channel.WHATSAPP_MONITOR, firstId)

        assertEquals(listOf("first result"), first)
        assertTrue(second.isEmpty())

        ChannelManager.flushMessages(Channel.WHATSAPP_MONITOR, secondId)
        assertEquals(listOf("second result"), second)
    }
}
