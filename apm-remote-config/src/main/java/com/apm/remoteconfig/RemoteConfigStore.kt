package com.apm.remoteconfig

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.security.MessageDigest

/** Durable store for the last verified document and its rollback/time metadata. */
internal interface RemoteConfigStore {

    /** Returns the complete cached state, or an empty rollback floor on first use. */
    fun read(): CachedRemoteConfig

    /** Atomically persists one complete cache state. */
    fun write(value: CachedRemoteConfig): Boolean
}

/** SharedPreferences implementation using synchronous commit on the refresh worker. */
internal class SharedPreferencesRemoteConfigStore(
    context: Context,
    config: RemoteConfigClientConfig,
    publicKeysBase64: Map<String, String>
) : RemoteConfigStore {

    /** App-private preferences detached from Activity lifecycles. */
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        cacheName(config, publicKeysBase64),
        Context.MODE_PRIVATE
    )

    /** Reads a self-consistent snapshot; missing fields degrade to an inactive empty cache. */
    override fun read(): CachedRemoteConfig {
        return CachedRemoteConfig(
            rawJson = preferences.getString(KEY_RAW_JSON, null),
            etag = preferences.getString(KEY_ETAG, null),
            highestRevision = preferences.getLong(KEY_HIGHEST_REVISION, 0L),
            active = preferences.getBoolean(KEY_ACTIVE, false),
            serverTimeAtReceiptMs = preferences.getLong(KEY_SERVER_TIME, 0L),
            elapsedRealtimeAtReceiptMs = preferences.getLong(KEY_ELAPSED_TIME, 0L)
        )
    }

    /** Commits metadata and body together so process death cannot publish a partial revision. */
    override fun write(value: CachedRemoteConfig): Boolean {
        val editor = preferences.edit()
            .putLong(KEY_HIGHEST_REVISION, value.highestRevision)
            .putBoolean(KEY_ACTIVE, value.active)
            .putLong(KEY_SERVER_TIME, value.serverTimeAtReceiptMs)
            .putLong(KEY_ELAPSED_TIME, value.elapsedRealtimeAtReceiptMs)
        if (value.rawJson == null) {
            editor.remove(KEY_RAW_JSON)
        } else {
            editor.putString(KEY_RAW_JSON, value.rawJson)
        }
        if (value.etag == null) {
            editor.remove(KEY_ETAG)
        } else {
            editor.putString(KEY_ETAG, value.etag)
        }
        return editor.commit()
    }

    companion object {
        /** Scoped format deliberately excludes the legacy cache without an authenticated audience. */
        private const val PREFERENCES_NAME_PREFIX = "androidapm_signed_remote_config_v2_"

        /** Hash algorithm used to avoid placing installation identity or endpoints in file names. */
        private const val SCOPE_HASH_ALGORITHM = "SHA-256"

        /** Fixed-width length encoding and hex constants for unambiguous deterministic hashing. */
        private const val BYTE_SHIFT = 8
        private const val BYTE_MASK = 0xFF
        private const val HEX_SHIFT = 4
        private const val HEX_MASK = 0x0F
        private const val HEX_DIGITS = "0123456789abcdef"

        /** Namespaces LKG, ETag, and rollback floor by the complete configured audience and trust set. */
        private fun cacheName(
            config: RemoteConfigClientConfig,
            publicKeysBase64: Map<String, String>
        ): String {
            val digest = MessageDigest.getInstance(SCOPE_HASH_ALGORITHM)
            for (value in listOf(config.endpoint, config.appId, config.environment, config.installationId)) {
                digest.updateScopeValue(value)
            }
            // Sorting makes equivalent key maps independent of host map insertion order.
            for ((keyId, publicKey) in publicKeysBase64.toSortedMap()) {
                digest.updateScopeValue(keyId)
                digest.updateScopeValue(publicKey)
            }
            val hash = digest.digest()
            val suffix = buildString(hash.size * 2) {
                for (byte in hash) {
                    val value = byte.toInt() and BYTE_MASK
                    append(HEX_DIGITS[value ushr HEX_SHIFT])
                    append(HEX_DIGITS[value and HEX_MASK])
                }
            }
            return PREFERENCES_NAME_PREFIX + suffix
        }

        /** Adds one length-prefixed UTF-8 scope component so concatenation cannot alias audiences. */
        private fun MessageDigest.updateScopeValue(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            for (shift in (3 * BYTE_SHIFT) downTo 0 step BYTE_SHIFT) {
                update((bytes.size ushr shift).toByte())
            }
            update(bytes)
        }

        /** Cache field names. */
        private const val KEY_RAW_JSON = "raw_json"
        private const val KEY_ETAG = "etag"
        private const val KEY_HIGHEST_REVISION = "highest_revision"
        private const val KEY_ACTIVE = "active"
        private const val KEY_SERVER_TIME = "server_time_at_receipt_ms"
        private const val KEY_ELAPSED_TIME = "elapsed_realtime_at_receipt_ms"
    }
}

/** Supplies wall and monotonic clocks for trusted-time anchoring and deterministic tests. */
internal interface RemoteConfigClock {

    /** Returns current Unix epoch milliseconds. */
    fun wallTimeMs(): Long

    /** Returns milliseconds since boot, including device sleep. */
    fun elapsedRealtimeMs(): Long
}

/** Android clock implementation; monotonic time protects expiry checks until the next reboot. */
internal object AndroidRemoteConfigClock : RemoteConfigClock {

    /** Delegates to the system wall clock. */
    override fun wallTimeMs(): Long = System.currentTimeMillis()

    /** Delegates to Android's sleep-inclusive monotonic clock. */
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
