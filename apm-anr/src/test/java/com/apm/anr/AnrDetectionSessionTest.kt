package com.apm.anr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic cancellation tests for the token shared by actual watchdog and SIGQUIT analysis tasks. */
class AnrDetectionSessionTest {
    /** Uninterruptible analysis finishing after stop and a new start cannot use the old final handoff. */
    @Test
    fun `old analysis cannot report after stop and a new session starts`() {
        val oldSession = AnrDetectionSession()
        val analyzing = CountDownLatch(1)
        val finishAnalysis = CountDownLatch(1)
        val deliveries = AtomicInteger()
        val analysis = Thread {
            analyzing.countDown()
            assertTrue(finishAnalysis.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            oldSession.handoff { deliveries.incrementAndGet(); true }
        }.apply { start() }
        try {
            assertTrue(analyzing.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            oldSession.close()
            val newSession = AnrDetectionSession()
            assertTrue(newSession.handoff { deliveries.incrementAndGet(); true })
            finishAnalysis.countDown()
            analysis.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
            assertFalse(analysis.isAlive)
            assertEquals(1, deliveries.get())
            assertFalse(oldSession.isActive())
        } finally {
            finishAnalysis.countDown()
            analysis.interrupt()
        }
    }

    /** A blocked host-context provider does not hold the final-handoff lock or delay cancellation. */
    @Test
    fun `closing during context resolution rejects final admission`() {
        val session = AnrDetectionSession()
        val contextResolving = CountDownLatch(1)
        val returnContext = CountDownLatch(1)
        val deliveries = AtomicInteger()
        val analysis = Thread {
            contextResolving.countDown()
            returnContext.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.handoff { deliveries.incrementAndGet(); true }
        }.apply { start() }
        try {
            assertTrue(contextResolving.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            session.close()
            assertFalse(session.isActive())
            returnContext.countDown()
            analysis.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
            assertFalse(analysis.isAlive)
            assertEquals(0, deliveries.get())
        } finally {
            returnContext.countDown()
            analysis.interrupt()
        }
    }

    /** Restarted runs have independent recovery and dedup markers, so an old worker cannot poison them. */
    @Test
    fun `tick and fingerprint state are isolated per session`() {
        val oldSession = AnrDetectionSession()
        oldSession.anrDetected.set(true)
        oldSession.tick.set(true)
        oldSession.lastStackFingerprint = "old-stack"
        oldSession.lastReportTimeMs.set(123)
        oldSession.close()
        val newSession = AnrDetectionSession()
        assertFalse(newSession.anrDetected.get())
        assertFalse(newSession.tick.get())
        assertEquals("", newSession.lastStackFingerprint)
        assertEquals(0L, newSession.lastReportTimeMs.get())
    }

    /** An already admitted custom critical store may hang; module close must not wait on that host code. */
    @Test
    fun `close does not wait on an already admitted critical handoff`() {
        val session = AnrDetectionSession()
        val storing = CountDownLatch(1)
        val releaseStore = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val deliveries = AtomicInteger()
        val handoff = Thread {
            session.handoff {
                storing.countDown()
                releaseStore.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                deliveries.incrementAndGet()
                true
            }
        }.apply { start() }
        val closer = Thread { session.close(); closeReturned.countDown() }
        try {
            assertTrue(storing.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            closer.start()
            // The store has not returned, so waiting for it would violate bounded SDK revocation.
            assertTrue(closeReturned.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(session.handoff { deliveries.incrementAndGet(); true })
            assertTrue(handoff.isAlive)
            releaseStore.countDown()
            handoff.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
            assertEquals(1, deliveries.get())
        } finally {
            releaseStore.countDown()
            closer.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
            handoff.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
        }
    }

    companion object {
        /** Upper bound for test-owned worker joins and latches. */
        private const val TEST_TIMEOUT_SECONDS = 5L
    }
}
