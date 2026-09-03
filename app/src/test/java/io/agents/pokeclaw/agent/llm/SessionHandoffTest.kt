// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Static / unit coverage for the local-model session-handoff fix
 * (fix/experiment: serialize local model session handoff).
 *
 * These exercise the pieces that are Android-free:
 *  - EngineHolder.withConversationSlot        (FIX 1 — lifecycle mutex)
 *  - EngineHolder task-starting fence         (FIX 2 — task-active-or-starting)
 *  - LocalModelRuntime session-conflict retry (FIX 3 — bounded retry)
 */
class SessionHandoffTest {

    @Before
    fun setUp() {
        EngineHolder.clearTaskStarting()
    }

    @After
    fun tearDown() {
        EngineHolder.clearTaskStarting()
    }

    // ---------------------------------------------------------------
    // A. chat open + task starting  ->  the fence reports "task owns session"
    // ---------------------------------------------------------------
    @Test
    fun `A - fence is active while a task is starting`() {
        assertFalse("no task -> not starting", EngineHolder.isTaskStarting())

        EngineHolder.markTaskStarting()

        // This is exactly what ChatSessionController.taskOwnsSession() consults
        // before opening a chat Conversation.
        assertTrue("task starting -> chat must defer", EngineHolder.isTaskStarting())
    }

    // ---------------------------------------------------------------
    // B. task terminal  ->  fence cleared  ->  chat may reopen
    // ---------------------------------------------------------------
    @Test
    fun `B - clearing the fence lets the chat side reopen`() {
        EngineHolder.markTaskStarting()
        assertTrue(EngineHolder.isTaskStarting())

        EngineHolder.clearTaskStarting()

        assertFalse("fence cleared -> chat open allowed", EngineHolder.isTaskStarting())
    }

    @Test
    fun `B2 - starting fence auto-expires so a wedged launch cannot block chat forever`() {
        val now = 10_000_000L
        assertTrue(EngineHolder.startingFenceActive(now - 5_000L, now))
        assertFalse(EngineHolder.startingFenceActive(now - (EngineHolder.TASK_STARTING_GRACE_MS + 1L), now))
        assertFalse("zero timestamp = not set", EngineHolder.startingFenceActive(0L, now))
    }

    // ---------------------------------------------------------------
    // C. two concurrent create attempts  ->  serialized by the slot mutex
    // ---------------------------------------------------------------
    @Test
    fun `C - withConversationSlot serializes concurrent lifecycle blocks`() {
        val inside = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val overlaps = AtomicInteger(0)
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            Thread {
                start.await()
                EngineHolder.withConversationSlot {
                    val n = inside.incrementAndGet()
                    maxObserved.updateAndGet { m -> maxOf(m, n) }
                    if (n > 1) overlaps.incrementAndGet()
                    Thread.sleep(15)
                    inside.decrementAndGet()
                }
                done.countDown()
            }.start()
        }

        start.countDown()
        assertTrue("threads finished", done.await(10, TimeUnit.SECONDS))
        assertEquals("never more than one thread inside the slot", 1, maxObserved.get())
        assertEquals("no overlap observed", 0, overlaps.get())
    }

    @Test
    fun `C2 - withConversationSlot returns the block value and propagates exceptions`() {
        assertEquals(42, EngineHolder.withConversationSlot { 42 })

        var threw = false
        try {
            EngineHolder.withConversationSlot { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue("exception propagates out of the slot", threw)
        // slot is released even on exception — a following acquire must not deadlock
        assertEquals("released after exception", 7, EngineHolder.withConversationSlot { 7 })
    }

    // ---------------------------------------------------------------
    // D. session conflict  ->  at most SESSION_CONFLICT_MAX_ATTEMPTS tries
    // ---------------------------------------------------------------
    @Test
    fun `D - bounded session-conflict retry caps at 3`() {
        assertEquals(3, LocalModelRuntime.SESSION_CONFLICT_MAX_ATTEMPTS)
        assertTrue("1st conflict -> retry", LocalModelRuntime.shouldRetrySessionConflict(1))
        assertTrue("2nd conflict -> retry", LocalModelRuntime.shouldRetrySessionConflict(2))
        assertFalse("3rd conflict -> fail closed", LocalModelRuntime.shouldRetrySessionConflict(3))
        assertFalse("4th conflict -> fail closed", LocalModelRuntime.shouldRetrySessionConflict(4))
    }

    @Test
    fun `D2 - the three known session-conflict messages are recognised`() {
        assertTrue(LocalModelRuntime.isSessionConflict(RuntimeException("FAILED_PRECONDITION: A session already exists.")))
        assertTrue(LocalModelRuntime.isSessionConflict(RuntimeException("Only one session is supported at a time")))
        assertTrue(LocalModelRuntime.isSessionConflict(IllegalStateException("Local model session already in use")))
    }

    // ---------------------------------------------------------------
    // E. non-session-conflict error  ->  NOT treated as a retryable conflict
    // ---------------------------------------------------------------
    @Test
    fun `E - unrelated errors are not session conflicts`() {
        assertFalse(LocalModelRuntime.isSessionConflict(RuntimeException("OpenCL init failed")))
        assertFalse(LocalModelRuntime.isSessionConflict(RuntimeException("out of memory")))
        assertFalse(LocalModelRuntime.isSessionConflict(null))
        assertFalse(LocalModelRuntime.isSessionConflict(RuntimeException(null as String?)))
    }

    // ---------------------------------------------------------------
    // F. fence must be cleared on FAIL / CANCEL too — TaskOrchestrator.releaseTask()
    //    is the single chokepoint for every terminal and always calls clearTaskStarting().
    //    Here we assert the primitive it relies on: clear is unconditional + idempotent.
    // ---------------------------------------------------------------
    @Test
    fun `F - clearTaskStarting is unconditional and idempotent`() {
        EngineHolder.markTaskStarting()
        EngineHolder.clearTaskStarting()   // e.g. TaskEvent.Failed path
        assertFalse(EngineHolder.isTaskStarting())

        EngineHolder.clearTaskStarting()   // e.g. TaskEvent.Cancelled path, fence already down
        assertFalse(EngineHolder.isTaskStarting())

        // and a fresh task can still claim the fence afterwards
        EngineHolder.markTaskStarting()
        assertTrue(EngineHolder.isTaskStarting())
    }
}
