package com.apm.remoteconfig

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real app-private cache tests covering audience, trust-set isolation, and legacy-cache rejection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RemoteConfigStoreTest {

    /** Changing any audience component or pinned trust set starts with no inherited durable state. */
    @Test
    fun `cache isolates endpoint app environment installation and trust set`() {
        val context = RuntimeEnvironment.getApplication()
        val original = SharedPreferencesRemoteConfigStore(context, CLIENT, KEYS)
        assertTrue(original.write(CACHE))
        assertEquals(CACHE, SharedPreferencesRemoteConfigStore(context, CLIENT, KEYS).read())

        val changedAudiences = listOf(
            CLIENT.copy(endpoint = "https://other.example/v1/config"),
            CLIENT.copy(appId = "other-app"),
            CLIENT.copy(environment = "staging"),
            CLIENT.copy(installationId = "other-installation")
        )
        for (config in changedAudiences) {
            assertEmpty(SharedPreferencesRemoteConfigStore(context, config, KEYS).read())
        }
        assertEmpty(SharedPreferencesRemoteConfigStore(context, CLIENT,
            KEYS + ("key-new" to "new-public-key")).read())
        assertEmpty(SharedPreferencesRemoteConfigStore(context, CLIENT,
            KEYS + ("key-a" to "replacement-public-key")).read())
        assertEmpty(SharedPreferencesRemoteConfigStore(context, CLIENT,
            mapOf("key-a" to KEYS.getValue("key-a"))).read())
    }

    /** Equivalent pinned maps share the same scope regardless of host iteration order. */
    @Test
    fun `cache trust namespace is independent of map insertion order`() {
        val context = RuntimeEnvironment.getApplication()
        val original = SharedPreferencesRemoteConfigStore(context, CLIENT, KEYS)
        assertTrue(original.write(CACHE))
        val reversedKeys = linkedMapOf("key-b" to KEYS.getValue("key-b"), "key-a" to KEYS.getValue("key-a"))
        assertEquals(CACHE, SharedPreferencesRemoteConfigStore(context, CLIENT, reversedKeys).read())
    }

    /** A new audience cannot publish the old kill switch or send its ETag and rollback floor. */
    @Test
    fun `new audience neither publishes old values nor inherits etag and revision`() {
        val context = RuntimeEnvironment.getApplication()
        val originalStore = SharedPreferencesRemoteConfigStore(context, CLIENT, KEYS)
        assertTrue(originalStore.write(CACHE))
        val original = provider(originalStore)
        assertFalse(original.getBoolean(KEY_ENABLED, true))

        val requestedEtags = mutableListOf<String?>()
        val changedStore = SharedPreferencesRemoteConfigStore(context,
            CLIENT.copy(environment = "fresh-environment"), KEYS)
        val changed = provider(changedStore, fakeTransport { etag ->
            requestedEtags += etag
            RemoteConfigHttpResponse(200, "fresh-etag", documentJson(1), SERVER_TIME_MS)
        })
        assertTrue(changed.getBoolean(KEY_ENABLED, true))
        assertEquals(RemoteConfigRefreshStatus.UPDATED, changed.refreshNow().status)
        assertEquals(listOf<String?>(null), requestedEtags)
        assertEquals(1L, changedStore.read().highestRevision)
        assertEquals(CACHE, originalStore.read())
    }

    /** Legacy documents have no authenticated scope and are deliberately not migrated. */
    @Test
    fun `legacy unscoped cache is ignored`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString("raw_json", CACHE.rawJson)
            .putString("etag", CACHE.etag)
            .putLong("highest_revision", CACHE.highestRevision)
            .putBoolean("active", true)
            .commit()
        val newScope = SharedPreferencesRemoteConfigStore(context,
            CLIENT.copy(installationId = "legacy-migration-check"), KEYS)
        assertEmpty(newScope.read())
        assertTrue(provider(newScope).getBoolean(KEY_ENABLED, true))
    }

    /** Asserts all delivery and rollback metadata is empty in a previously unused scope. */
    private fun assertEmpty(cache: CachedRemoteConfig) {
        assertNull(cache.rawJson)
        assertNull(cache.etag)
        assertFalse(cache.active)
        assertEquals(0L, cache.highestRevision)
    }

    /** Creates a deterministic provider whose verifier recognizes the already-validated test body. */
    private fun provider(
        store: RemoteConfigStore,
        transport: RemoteConfigTransport = fakeTransport {
            throw IllegalStateException("No network response configured")
        }
    ): SignedRemoteConfigProvider = SignedRemoteConfigProvider(
        store, RemoteConfigSignatureVerifier { _, _, _ -> true }, transport,
        object : RemoteConfigClock {
            override fun wallTimeMs(): Long = SERVER_TIME_MS
            override fun elapsedRealtimeMs(): Long = ELAPSED_TIME_MS
        }, REFRESH_INTERVAL_MS, RemoteConfigLogger.NONE
    )

    /** Adapts one deterministic fetch callback to the internal transport contract. */
    private fun fakeTransport(fetchResponse: (String?) -> RemoteConfigHttpResponse): RemoteConfigTransport =
        object : RemoteConfigTransport {
            override fun fetch(etag: String?): RemoteConfigHttpResponse = fetchResponse(etag)
        }

    companion object {
        /** One audience and two distinct trust selectors for deterministic namespace tests. */
        private val CLIENT = RemoteConfigClientConfig(
            "https://collector.example/v1/config", "app", "production", "installation"
        )

        /** Storage only fingerprints pinned strings; cryptographic parsing belongs to the verifier. */
        private val KEYS = linkedMapOf("key-a" to "public-key-a", "key-b" to "public-key-b")

        /** Existing unscoped format deliberately never read by the scoped implementation. */
        private const val LEGACY_PREFERENCES_NAME = "androidapm_signed_remote_config"

        /** Stable payload key demonstrating cross-environment kill-switch leakage. */
        private const val KEY_ENABLED = "apm.enabled"

        /** Trusted-time fixtures remain inside the signed document lifetime. */
        private const val SERVER_TIME_MS = 1_784_160_000_000L
        private const val ELAPSED_TIME_MS = 1_000L
        private const val REFRESH_INTERVAL_MS = 60_000L

        /** Complete LKG metadata that must remain exclusive to its original audience. */
        private val CACHE = CachedRemoteConfig(documentJson(100), "old-etag", 100L,
            true, SERVER_TIME_MS, ELAPSED_TIME_MS)

        /** Canonical-shaped immutable signed fixture, with verification stubbed only in state tests. */
        private fun documentJson(revision: Long): String =
            "{\"revision\":$revision,\"issuedAt\":\"2026-07-16T00:00:00Z\"," +
                "\"expiresAt\":\"2026-07-17T00:00:00Z\",\"rolloutBasisPoints\":10000," +
                "\"payload\":{\"apm.enabled\":false},\"keyId\":\"key-a\",\"signature\":\"dmFsaWQ=\"}"
    }
}
