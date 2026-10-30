package io.github.jameskbb.hearth

import io.github.jameskbb.hearth.apps.AppCatalog
import io.github.jameskbb.hearth.apps.AppEntry
import io.github.jameskbb.hearth.apps.AppKey
import io.github.jameskbb.hearth.prefs.HearthPrefs
import io.github.jameskbb.hearth.prefs.PrefsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeedFavoritesTest {
    private fun entry(id: String, tv: Boolean = true) = AppEntry(AppKey(id), id, tv, 0L)

    private class Catalog(initial: List<AppEntry>) : AppCatalog {
        override val apps = MutableStateFlow(initial)
        override val loaded = MutableStateFlow(true)
        override suspend fun refresh() = Unit
    }

    private class Prefs(initial: HearthPrefs) : PrefsRepository {
        val state = MutableStateFlow(initial)
        override val prefs = state
        override suspend fun update(transform: (HearthPrefs) -> HearthPrefs) {
            state.value = transform(state.value)
        }
    }

    @Test fun seedsTvAppsOnFirstRun() = runTest {
        val prefs = Prefs(HearthPrefs())
        seedFavoritesOnce(prefs, Catalog(listOf(entry("a/A"), entry("b/B", tv = false), entry("c/C"))))
        assertEquals(listOf(AppKey("a/A"), AppKey("c/C")), prefs.state.value.favorites)
        assertTrue(prefs.state.value.seeded)
    }

    @Test fun anEmptyFirstScanDoesNotLockInAnEmptyList() = runTest(UnconfinedTestDispatcher()) {
        val prefs = Prefs(HearthPrefs())
        val catalog = Catalog(emptyList())
        val job = launch { seedFavoritesOnce(prefs, catalog) }
        assertFalse(prefs.state.value.seeded)
        catalog.apps.value = listOf(entry("a/A"))
        job.join()
        assertEquals(listOf(AppKey("a/A")), prefs.state.value.favorites)
        assertTrue(prefs.state.value.seeded)
    }

    @Test fun neverReseeds() = runTest {
        val seeded = HearthPrefs(favorites = listOf(AppKey("x/X")), seeded = true)
        val prefs = Prefs(seeded)
        seedFavoritesOnce(prefs, Catalog(listOf(entry("a/A"))))
        assertEquals(seeded, prefs.state.value)
    }
}
