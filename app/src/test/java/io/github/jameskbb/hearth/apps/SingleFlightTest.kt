package io.github.jameskbb.hearth.apps

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SingleFlightTest {
    @Test fun concurrentCallersShareOneComputation() = runTest {
        val flights = SingleFlight<String, Int>(backgroundScope)
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val results = (1..5).map {
            async { flights.run("k") { calls.incrementAndGet(); gate.await(); 7 } }
        }
        runCurrent()
        assertEquals(1, flights.size)
        gate.complete(Unit)
        assertEquals(List(5) { 7 }, results.awaitAll())
        assertEquals(1, calls.get())
        assertEquals(0, flights.size)
    }

    @Test fun differentKeysRunSeparately() = runTest {
        val flights = SingleFlight<String, String>(backgroundScope)
        val a = async { flights.run("a") { "A" } }
        val b = async { flights.run("b") { "B" } }
        assertEquals(listOf("A", "B"), listOf(a.await(), b.await()))
    }

    @Test fun keyIsForgottenAfterCompletion() = runTest {
        val flights = SingleFlight<String, Int>(backgroundScope)
        val calls = AtomicInteger()
        flights.run("k") { calls.incrementAndGet() }
        flights.run("k") { calls.incrementAndGet() }
        assertEquals(2, calls.get())
    }

    @Test fun cancelledCallerDoesNotCancelSharedWork() = runTest {
        val flights = SingleFlight<String, Int>(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val quitter = launch { flights.run("k") { gate.await(); 1 } }
        val stayer = async { flights.run("k") { error("second block must not run") } }
        runCurrent()
        quitter.cancel()
        runCurrent()
        gate.complete(Unit)
        assertEquals(1, stayer.await())
        assertTrue(quitter.isCancelled)
    }
}
