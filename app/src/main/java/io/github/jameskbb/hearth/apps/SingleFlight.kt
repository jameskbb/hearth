package io.github.jameskbb.hearth.apps

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Collapses concurrent requests for the same key into one computation: the first caller
 * starts [run]'s block in [scope], later callers for that key await the same result. Once
 * it completes the key is forgotten, so a later call computes again (callers cache).
 *
 * The work belongs to [scope], not to any caller: a caller that is cancelled (a tile
 * scrolled away) stops waiting without cancelling the shared load for the others.
 */
internal class SingleFlight<K : Any, V>(private val scope: CoroutineScope) {
    private val inFlight = HashMap<K, Deferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val deferred = synchronized(inFlight) {
            inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) { block() }.also { d ->
                inFlight[key] = d
                d.invokeOnCompletion {
                    synchronized(inFlight) { if (inFlight[key] === d) inFlight.remove(key) }
                }
            }
        }
        return deferred.await()
    }

    /** Number of keys currently loading; for tests. */
    internal val size: Int get() = synchronized(inFlight) { inFlight.size }
}
