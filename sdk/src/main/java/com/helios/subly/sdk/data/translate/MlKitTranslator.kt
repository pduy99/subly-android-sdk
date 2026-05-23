package com.helios.subly.sdk.data.translate

import android.util.Log
import com.helios.subly.sdk.domain.repository.DownloadResult
import com.helios.subly.sdk.domain.repository.TranslatorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [TranslatorRepository] backed by on-device ML Kit Translation.
 *
 * Per-pair [NmtClient]s are cached in a small access-ordered LRU so the
 * audio pipeline doesn't re-instantiate the native translator on every
 * segment. Eviction closes the evicted client to release model memory.
 *
 * Thread-safety: the cache is guarded by a [Mutex]. ML Kit clients themselves
 * are documented as safe to call concurrently, so once the lock returns the
 * caller can stream translations off the main thread freely.
 *
 * @property factory Pair factory; defaults to [MlKitNmt.defaultFactory].
 * @property identifier Source-language identifier; defaults to ML Kit Lang-ID.
 * @property maxCacheSize Max live translators. 4 covers the v1 matrix
 *   `(en|vi|es)` for two simultaneous targets without thrashing.
 */
internal class MlKitTranslator(
    private val factory: NmtClientFactory = MlKitNmt.defaultFactory,
    private val identifier: LanguageIdentifier = MlKitNmt.defaultLanguageIdentifier(),
    private val maxCacheSize: Int = 4,
) : TranslatorRepository {

    private data class Key(val source: String, val target: String)

    /** access-ordered LinkedHashMap → trivial LRU. */
    private val cache = object : LinkedHashMap<Key, NmtClient>(maxCacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, NmtClient>): Boolean {
            val evict = size > maxCacheSize
            if (evict) {
                runCatching { eldest.value.close() }
                Log.d(TAG, "LRU evict ${eldest.key.source}->${eldest.key.target}")
            }
            return evict
        }
    }
    private val mutex = Mutex()

    override fun translate(
        text: String,
        sourceBcp47: String,
        targetBcp47: String,
    ): Flow<String> = flow {
        if (text.isEmpty()) {
            emit(text); return@flow
        }
        if (sourceBcp47.equals(targetBcp47, ignoreCase = true)) {
            emit(text); return@flow
        }
        val client = acquire(sourceBcp47.lowercase(), targetBcp47.lowercase())
        val out = runCatching { client.translate(text) }
            .getOrElse {
                Log.w(TAG, "translate($sourceBcp47->$targetBcp47) failed; passing through", it)
                text
            }
        emit(out)
    }

    override suspend fun ensureModel(
        sourceBcp47: String,
        targetBcp47: String,
    ): DownloadResult {
        if (sourceBcp47.equals(targetBcp47, ignoreCase = true)) return DownloadResult.Success
        return try {
            val client = acquire(sourceBcp47.lowercase(), targetBcp47.lowercase())
            if (client.ensureModel()) DownloadResult.Success
            else DownloadResult.Failed(IllegalStateException("ML Kit downloadModelIfNeeded returned failure"))
        } catch (t: Throwable) {
            DownloadResult.Failed(t)
        }
    }

    override suspend fun identifySource(text: String): String? = identifier.identify(text)

    override fun release() {
        synchronized(cache) {
            cache.values.forEach { runCatching { it.close() } }
            cache.clear()
        }
        runCatching { identifier.close() }
    }

    private suspend fun acquire(source: String, target: String): NmtClient = mutex.withLock {
        val key = Key(source, target)
        cache.getOrPut(key) { factory.create(source, target) }
    }

    private companion object {
        const val TAG = "SublyMlKitTranslator"
    }
}
