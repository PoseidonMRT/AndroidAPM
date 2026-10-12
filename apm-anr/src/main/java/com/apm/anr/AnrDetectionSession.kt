package com.apm.anr

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One permanently cancellable ANR run, including tick and dedup state that old workers cannot mutate. */
internal class AnrDetectionSession {
    /** A closed session remains closed even after a later module start. */
    @Volatile private var active = true
    /** ANR already detected before the current main-thread recovery. */
    val anrDetected = AtomicBoolean(false)
    /** Main-thread acknowledgment of this session's latest tick. */
    val tick = AtomicBoolean(false)
    /** First observed blockage in this run. */
    val blockedSinceMs = AtomicLong(0L)
    /** Last delivered/deduplicated detection time in this run. */
    val lastReportTimeMs = AtomicLong(0L)
    /** Fingerprint belongs exclusively to this run. */
    @Volatile var lastStackFingerprint = ""

    /** Returns liveness without reviving a stopped generation. */
    fun isActive(): Boolean = active

    /** Closes admission before shutdown interrupts analysis and watch threads. */
    fun close() { active = false }

    /**
     * The active read linearizes module admission without holding a lock across a custom store.
     * Already admitted delivery belongs to core's bounded EventDeliveryBarrier; close must stay immediate.
     */
    fun handoff(action: () -> Boolean): Boolean {
        if (!active) return false
        return action()
    }
}
