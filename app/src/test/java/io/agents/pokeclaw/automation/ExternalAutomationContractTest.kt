package io.agents.pokeclaw.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ExternalAutomationContractTest {

    @Test
    fun `parse task action reads plain task payload`() {
        val request = ExternalAutomationContract.parse(
            ExternalAutomationContract.ACTION_RUN_TASK
        ) { key ->
            mapOf(
                ExternalAutomationContract.EXTRA_TASK to " summarize notifications ",
                ExternalAutomationContract.EXTRA_REQUEST_ID to " req-1 ",
                ExternalAutomationContract.EXTRA_RETURN_ACTION to " io.example.RESULT ",
                ExternalAutomationContract.EXTRA_RETURN_PACKAGE to " net.dinglisch.android.taskerm ",
            )[key]
        }

        assertEquals(ExternalAutomationContract.Mode.TASK, request!!.mode)
        assertEquals("summarize notifications", request.text)
        assertEquals("req-1", request.requestId)
        assertEquals("io.example.RESULT", request.returnAction)
        assertEquals("net.dinglisch.android.taskerm", request.returnPackage)
    }

    @Test
    fun `parse prefers base64 payload over plain payload`() {
        val encoded = Base64.getEncoder().encodeToString("battery status".toByteArray())
        val request = ExternalAutomationContract.parse(
            ExternalAutomationContract.ACTION_RUN_TASK
        ) { key ->
            mapOf(
                ExternalAutomationContract.EXTRA_TASK_B64 to encoded,
                ExternalAutomationContract.EXTRA_TASK to "wrong fallback",
            )[key]
        }

        assertEquals("battery status", request!!.text)
    }

    @Test
    fun `parse chat action can fallback to task extra`() {
        val request = ExternalAutomationContract.parse(
            ExternalAutomationContract.ACTION_RUN_CHAT
        ) { key ->
            mapOf(ExternalAutomationContract.EXTRA_TASK to "hello")[key]
        }

        assertEquals(ExternalAutomationContract.Mode.CHAT, request!!.mode)
        assertEquals("hello", request.text)
    }

    @Test
    fun `parse rejects unknown action or empty payload`() {
        assertNull(ExternalAutomationContract.parse("other.action") { null })
        assertNull(ExternalAutomationContract.parse(ExternalAutomationContract.ACTION_RUN_TASK) { null })
    }
}
