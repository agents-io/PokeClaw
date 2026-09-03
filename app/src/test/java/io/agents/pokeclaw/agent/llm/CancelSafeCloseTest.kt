// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import io.agents.pokeclaw.agent.AgentConfig
import io.agents.pokeclaw.agent.LlmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cancel-safe local-LLM close (experiment/pokeclaw-cancel-safe-close).
 *
 * The bug: close() → Conversation.close() → nativeDeleteConversation runs on
 * another thread while the agent-loop thread is still inside
 * Conversation.sendMessage → nativeSendMessage → use-after-free → SIGSEGV.
 *
 * These exercise the per-client in-flight send guard directly (no LiteRT-LM,
 * no Android runtime): beginSend / endSend / close(timeoutMs).
 */
class CancelSafeCloseTest {

    private fun newClient() =
        LocalLlmClient(AgentConfig(apiKey = "", baseUrl = "/no/such/model", provider = LlmProvider.LOCAL))

    // A — normal send lifecycle
    @Test
    fun `A - begin then end leaves no in-flight send`() {
        val c = newClient()
        c.beginSend()
        assertEquals(1, c.inFlightSends)
        c.endSend()
        assertEquals(0, c.inFlightSends)
        assertFalse(c.isClosing)
        assertFalse(c.closeExecuted)
    }

    // B — close with no send in flight → native teardown happens synchronously
    @Test
    fun `B - close with no active send executes immediately`() {
        val c = newClient()
        c.close(5_000L)
        assertTrue(c.isClosing)
        assertTrue("native close ran", c.closeExecuted)
    }

    // C + D — cancel while a send is active: close must NOT tear down until the
    // send finishes; when it finishes, the deferred close executes.
    @Test
    fun `C-D - close waits for the in-flight send, then executes on drain`() {
        val c = newClient()
        c.beginSend()   // simulate the loop thread inside nativeSendMessage

        val closeReturned = CountDownLatch(1)
        val t = Thread {
            c.close(10_000L)   // generous timeout; should be released by endSend
            closeReturned.countDown()
        }
        t.start()

        // close() must still be blocked/deferred while the send is in flight
        assertFalse("close returned before the send finished", closeReturned.await(400, TimeUnit.MILLISECONDS))
        assertTrue(c.isClosing)
        assertTrue(c.isCloseDeferred)
        assertFalse("native close ran while a send was active (UAF!)", c.closeExecuted)

        // send finishes → deferred close runs
        c.endSend()
        assertTrue("close() returned after drain", closeReturned.await(3, TimeUnit.SECONDS))
        assertEquals(0, c.inFlightSends)
        assertTrue("deferred native close executed", c.closeExecuted)
        assertFalse(c.isCloseDeferred)
    }

    // E — a send started after close() must be rejected
    @Test
    fun `E - send after closing is rejected`() {
        val c = newClient()
        c.close(2_000L)
        assertTrue(c.isClosing)
        try {
            c.beginSend()
            fail("beginSend should have been rejected while closing")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("closing"))
        }
        assertEquals(0, c.inFlightSends)
    }

    // F — repeated close is idempotent and never throws
    @Test
    fun `F - repeated close is idempotent`() {
        val c = newClient()
        c.close(1_000L)
        c.close(1_000L)
        c.close(1_000L)
        assertTrue(c.isClosing)
        assertTrue(c.closeExecuted)
    }

    // G — close times out while a send is still running → it does NOT force a
    // native close; the deferred close still runs later when the send drains.
    @Test
    fun `G - timeout does not force a native close`() {
        val c = newClient()
        c.beginSend()

        val start = System.currentTimeMillis()
        c.close(150L)   // short timeout, send still "running"
        val elapsed = System.currentTimeMillis() - start

        assertTrue("close returned near the timeout", elapsed in 100..2_000)
        assertTrue(c.isClosing)
        assertTrue("close is still pending", c.isCloseDeferred)
        assertFalse("must NOT have torn down the conversation under an active send", c.closeExecuted)

        // later, the send finishes → the deferred close finally runs
        c.endSend()
        assertTrue("deferred close executed after drain", c.closeExecuted)
        assertEquals(0, c.inFlightSends)
    }

    // H — concurrency smoke: many begin/end pairs racing a close never leave
    // the guard inconsistent and never run the native close early.
    @Test
    fun `H - concurrent sends racing a close stay consistent`() {
        val c = newClient()
        val workers = 6
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        repeat(workers) {
            Thread {
                start.await()
                repeat(50) {
                    try {
                        c.beginSend()
                        try { Thread.sleep(1) } finally { c.endSend() }
                    } catch (_: IllegalStateException) {
                        // closing — expected once close() has run
                    }
                }
                done.countDown()
            }.start()
        }
        val closer = Thread { start.await(); Thread.sleep(20); c.close(5_000L) }
        closer.start()

        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        closer.join(5_000)

        assertTrue(c.isClosing)
        assertEquals("no leaked in-flight sends", 0, c.inFlightSends)
        assertTrue("native close eventually executed", c.closeExecuted)
        assertFalse(c.isCloseDeferred)
    }
}
