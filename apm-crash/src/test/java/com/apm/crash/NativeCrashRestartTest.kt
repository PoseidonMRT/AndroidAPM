package com.apm.crash

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies start/stop/restart and failed-install retry against the real monitor's JNI boundary. */
class NativeCrashRestartTest {
    /** Re-enabling the module installs again and passes the new callback preference. */
    @Test
    fun `stopped native monitor installs handlers on restart`() {
        withRuntime { runtime ->
            NativeCrashMonitor.init(false)
            NativeCrashMonitor.init(false)
            NativeCrashMonitor.destroy()
            NativeCrashMonitor.init(true)
            assertEquals(listOf(false, true), runtime.installations)
            assertEquals(1, runtime.uninstallations)
        }
    }

    /** A failed installation does not leave a sticky initialized flag that prevents later recovery. */
    @Test
    fun `failed native installation is retryable`() {
        withRuntime { runtime ->
            runtime.installSucceeds = false
            NativeCrashMonitor.init()
            runtime.installSucceeds = true
            NativeCrashMonitor.init()
            assertEquals(listOf(false, false), runtime.installations)
        }
    }

    /** Restores the singleton even when an assertion fails. */
    private fun withRuntime(action: (RecordingRuntime) -> Unit) {
        NativeCrashMonitor.destroy()
        val previous = NativeCrashMonitor.signalRuntime
        val runtime = RecordingRuntime()
        NativeCrashMonitor.signalRuntime = runtime
        try {
            action(runtime)
        } finally {
            NativeCrashMonitor.destroy()
            NativeCrashMonitor.signalRuntime = previous
        }
    }

    /** Records native calls without requiring an Android signal handler in the JVM process. */
    private class RecordingRuntime : NativeCrashSignalRuntime {
        /** Callback preference of each installation attempt. */
        val installations = ArrayList<Boolean>()
        /** Number of actual uninstall attempts. */
        var uninstallations = 0
        /** Injected native outcome. */
        var installSucceeds = true
        /** Loading is a no-op for this test adapter. */
        override fun load() = Unit
        /** Records every attempt, including failed ones. */
        override fun install(unsafeSignalCallback: Boolean): Boolean {
            installations += unsafeSignalCallback
            return installSucceeds
        }
        /** Counts restoration of a successful installation. */
        override fun uninstall() { uninstallations++ }
    }
}
