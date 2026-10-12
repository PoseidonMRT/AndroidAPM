package com.apm.memory.oom

import android.content.ContextWrapper
import com.apm.core.ApmLogger
import com.apm.memory.MemoryConfig
import com.apm.memory.MemoryReport
import com.apm.memory.MemoryReportSink
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the real executor, artifact creation and permanent shutdown gate with a controllable ART boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HprofDumperLifecycleTest {
    /** Isolates retention checks from other dump sessions and existing application cache files. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** No diagnostic side effects are needed for a simulated heap dump. */
    private val logger = object : ApmLogger {
        override fun d(message: String) = Unit
        override fun w(message: String) = Unit
        override fun e(message: String, throwable: Throwable?) = Unit
    }

    /** An ART call that ignores shutdown interruption may finish, but its artifact and report are discarded. */
    @Test
    fun `late dump and queued dumps are discarded after shutdown`() {
        val dumping = CountDownLatch(1)
        val releaseDump = CountDownLatch(1)
        val reports = LinkedBlockingQueue<MemoryReport>()
        val calls = AtomicInteger()
        var created: File? = null
        val dumper = HprofDumper(
            RuntimeEnvironment.getApplication(), MemoryConfig(enableHprofDump = true), logger,
            directDump = { file ->
                calls.incrementAndGet()
                created = file
                dumping.countDown()
                // Simulate the ART dump boundary ignoring an interrupt and returning after module stop.
                var released = false
                while (!released) {
                    try { released = releaseDump.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { /* ART can be non-cooperative. */ }
                }
                file.writeBytes(byteArrayOf(1, 2, 3))
            },
            reportSink = MemoryReportSink { reports.add(it) }
        )
        try {
            dumper.dumpAsync("old-session")
            assertTrue(dumping.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            dumper.dumpAsync("queued-old-session")
            dumper.shutdown()
            dumper.dumpAsync("after-stop")
            releaseDump.countDown()
            assertTrue(executor(dumper).awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertTrue(reports.isEmpty())
            assertNotNull(created)
            assertFalse(created!!.exists())
        } finally {
            releaseDump.countDown()
            dumper.shutdown()
        }
    }

    /** A live session still publishes the produced artifact with its exact local path. */
    @Test
    fun `active dump is reported normally`() {
        val reports = LinkedBlockingQueue<MemoryReport>()
        val dumper = HprofDumper(
            RuntimeEnvironment.getApplication(), MemoryConfig(enableHprofDump = true), logger,
            directDump = { it.writeBytes(byteArrayOf(1, 2, 3)) },
            reportSink = MemoryReportSink { reports.add(it) }
        )
        try {
            dumper.dumpAsync("live-session")
            val report = reports.poll(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNotNull(report)
            assertEquals("hprof_dump", report!!.name)
            val file = File(report.fields["filePath"] as String)
            assertTrue(file.exists())
            file.delete()
        } finally { dumper.shutdown() }
    }

    /** The production guarded bridge resolves host callbacks before taking the dumper's final gate. */
    @Test
    fun `stop while host context callback is blocked rejects late report`() {
        val resolving = CountDownLatch(1)
        val releaseContext = CountDownLatch(1)
        val deliveries = AtomicInteger()
        var produced: File? = null
        val dumper = HprofDumper(
            RuntimeEnvironment.getApplication(), MemoryConfig(enableHprofDump = true), logger,
            directDump = { produced = it; it.writeBytes(byteArrayOf(1)) },
            guardedReport = { _, guard ->
                resolving.countDown()
                var released = false
                while (!released) {
                    try { released = releaseContext.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { /* Host callback may ignore interruption. */ }
                }
                guard { deliveries.incrementAndGet(); true }
            }
        )
        try {
            dumper.dumpAsync("resolving-context")
            assertTrue(resolving.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            dumper.shutdown()
            releaseContext.countDown()
            assertTrue(executor(dumper).awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(0, deliveries.get())
            assertFalse(produced!!.exists())
        } finally {
            releaseContext.countDown()
            dumper.shutdown()
        }
    }

    /** Successive same-millisecond raw dumps retain unique paths and a bounded artifact count. */
    @Test
    fun `successful raw dumps retain at most five files without deleting current report`() {
        verifySuccessfulRetention(strip = false)
    }

    /** Stripped output and existing raw artifacts share one budget; the current strip target always survives. */
    @Test
    fun `successful stripped dumps share five file budget and retain current report`() {
        verifySuccessfulRetention(strip = true)
    }

    /** Executes eight real dumper jobs with a fixed filename clock and adversarial old-file mtimes. */
    private fun verifySuccessfulRetention(strip: Boolean) {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            /** Test-owned cache storage makes file-count assertions independent of other suites. */
            override fun getCacheDir(): File = temporaryFolder.root
        }
        val directory = File(context.cacheDir, "apm/hprof").apply { mkdirs() }
        // Both file forms count, and future mtimes ensure naive newest-first pruning deletes a new report.
        for (name in listOf("previous.hprof", "previous_stripped.hprof")) {
            File(directory, name).apply {
                writeBytes(byteArrayOf(1))
                assertTrue(setLastModified(OLD_ARTIFACT_MTIME_MS))
            }
        }
        val reports = LinkedBlockingQueue<MemoryReport>()
        val rawPaths = mutableListOf<String>()
        val reportedPaths = mutableListOf<String>()
        val input = minimalArtHprof()
        val dumper = HprofDumper(
            context, MemoryConfig(enableHprofDump = true, enableHprofStrip = strip), logger,
            directDump = { file ->
                rawPaths.add(file.absolutePath)
                file.writeBytes(input)
                assertTrue(file.setLastModified(SAME_WALL_TIME_MS))
            },
            reportSink = MemoryReportSink { reports.add(it) },
            wallTimeMillis = { SAME_WALL_TIME_MS }
        )
        try {
            repeat(SUCCESSIVE_DUMPS) {
                dumper.dumpAsync("same-reason")
                val report = reports.poll(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertNotNull("dump $it must report", report)
                val current = File(report!!.fields["filePath"] as String)
                reportedPaths.add(current.absolutePath)
                assertTrue("current report must refer to an existing file", current.exists())
                assertTrue("raw and stripped files share one retention budget", directory.listFiles()!!.size <= MAX_RETAINED_FILES)
                val expected = if (strip) input.copyOf().apply {
                    // This ART fixture has one byte-array payload at the end of the file.
                    fill(0, size - PRIMITIVE_PAYLOAD_BYTES, size)
                } else input
                assertArrayEquals(expected, current.readBytes())
                assertEquals(strip, current.name.endsWith("_stripped.hprof"))
                assertTrue(current.setLastModified(OLD_ARTIFACT_MTIME_MS))
            }
            assertEquals(SUCCESSIVE_DUMPS, rawPaths.toSet().size)
            assertEquals(SUCCESSIVE_DUMPS, reportedPaths.toSet().size)
            assertTrue(rawPaths.all { File(it).name.startsWith("${SAME_WALL_TIME_MS}_same-reason_") })
            assertEquals(MAX_RETAINED_FILES, directory.listFiles()!!.size)
            assertTrue(File(reportedPaths.last()).exists())
        } finally {
            dumper.shutdown()
            assertTrue(executor(dumper).awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    /** Real ART's four-byte object IDs and primitive-array layout exercise the successful strip path. */
    private fun minimalArtHprof(): ByteArray {
        val heap = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(0x23) // PRIMITIVE_ARRAY_DUMP
                output.writeInt(1) // Object ID
                output.writeInt(0) // Allocation stack serial
                output.writeInt(PRIMITIVE_PAYLOAD_BYTES)
                output.writeByte(8) // BYTE
                output.write(byteArrayOf(1, 2, 3, 4))
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("JAVA PROFILE 1.0.3".toByteArray(Charsets.US_ASCII))
                output.writeByte(0)
                output.writeInt(4)
                output.writeLong(0)
                output.writeByte(0x1c) // HEAP_DUMP_SEGMENT
                output.writeInt(0)
                output.writeInt(heap.size)
                output.write(heap)
            }
        }.toByteArray()
    }

    /** Reads the real executor solely to wait for post-cancellation artifact cleanup deterministically. */
    private fun executor(dumper: HprofDumper): ExecutorService = HprofDumper::class.java
        .getDeclaredField("dumpExecutor").apply { isAccessible = true }.get(dumper) as ExecutorService

    companion object {
        /** Upper bound for test-owned worker completion. */
        private const val TEST_TIMEOUT_SECONDS = 5L
        /** Exceeds the production retention budget by three jobs in each output mode. */
        private const val SUCCESSIVE_DUMPS = 8
        /** Matches the shared production budget under test. */
        private const val MAX_RETAINED_FILES = 5
        /** Every output reserves a distinct path despite an identical wall clock and reason. */
        private const val SAME_WALL_TIME_MS = 1_000L
        /** Older artifacts sort ahead of every new fixture unless the report target is protected. */
        private const val OLD_ARTIFACT_MTIME_MS = 4_102_444_800_000L
        /** Number of primitive bytes in the real-layout fixture. */
        private const val PRIMITIVE_PAYLOAD_BYTES = 4
    }
}
