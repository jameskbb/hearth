package io.github.jameskbb.hearth.prefs

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import io.github.jameskbb.hearth.apps.AppKey
import io.github.jameskbb.hearth.theme.ThemeId
import io.github.jameskbb.hearth.theme.ThemeMode
import io.github.jameskbb.hearth.theme.ThemeRotationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Exercises the real DataStore on the JVM against a temporary file. */
class DataStorePrefsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After fun tearDown() = scopes.forEach { it.cancel() }

    private fun file() = File(tmp.root, "hearth.preferences_pb")

    private fun repo(): DataStorePrefsRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        return DataStorePrefsRepository(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = ::file,
            ),
        )
    }

    private val a = AppKey("com.example.a/.Main")
    private val b = AppKey("com.example.b/.Main")

    @Test fun missingFileGivesDefaults() = runBlocking {
        assertEquals(HearthPrefs(), repo().prefs.first())
    }

    @Test fun updatesPersistAcrossInstances() = runBlocking {
        val first = repo()
        first.update { FavoritesOps.addFavorite(it, a) }
        first.update { FavoritesOps.addFavorite(it, b) }
        first.update { FavoritesOps.move(it, b, -1) }
        first.update {
            it.copy(
                theme = ThemeId("aurora"),
                themeMode = ThemeMode.SurpriseMe,
                surpriseThemeIds = setOf(ThemeId("aurora"), ThemeId("dusk")),
                surpriseSelection = ThemeRotationState("surprise|UTC|123", ThemeId("aurora")),
                reducedMotion = true,
                homeLabelEnabled = true,
                homeLabel = "My Workshop",
            )
        }
        first.update { FavoritesOps.hide(it, a) }
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin() // the process goes away

        val second = repo().prefs.first()
        assertEquals(listOf(b), second.favorites)
        assertEquals(setOf(a), second.hidden)
        assertEquals(ThemeId("aurora"), second.theme)
        assertEquals(ThemeMode.SurpriseMe, second.themeMode)
        assertEquals(setOf(ThemeId("aurora"), ThemeId("dusk")), second.surpriseThemeIds)
        assertEquals(ThemeRotationState("surprise|UTC|123", ThemeId("aurora")), second.surpriseSelection)
        assertEquals(true, second.reducedMotion)
        assertEquals(true, second.homeLabelEnabled)
        assertEquals("My Workshop", second.homeLabel)
    }

    @Test fun disabledLabelTextAndClearingSurviveRestarts() = runBlocking {
        val first = repo()
        first.update { it.copy(homeLabelEnabled = true, homeLabel = " Game\tRoom ") }
        first.update { it.copy(homeLabelEnabled = false) }
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin()
        val second = repo()
        assertEquals("Game Room", second.prefs.first().homeLabel)
        assertEquals(false, second.prefs.first().homeLabelEnabled)
        second.update { it.copy(homeLabel = "", homeLabelEnabled = false) }
        scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin()
        val third = repo().prefs.first()
        assertEquals("", third.homeLabel)
        assertEquals(false, third.homeLabelEnabled)
    }

    @Test fun concurrentUpdatesAreNotLost() = runBlocking {
        val r = repo()
        val keys = (1..40).map { AppKey("com.example.p$it/.Main") }
        keys.map { k -> async(Dispatchers.IO) { r.update { FavoritesOps.addFavorite(it, k) } } }.awaitAll()
        assertEquals(keys.toSet(), r.prefs.first().favorites.toSet())
    }

    @Test fun corruptFileFallsBackToDefaults() = runBlocking {
        file().writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42, 0x01, 0x02))
        val r = repo()
        assertEquals(HearthPrefs(), r.prefs.first())
        // And the store is usable again afterwards.
        r.update { FavoritesOps.addFavorite(it, a) }
        assertEquals(listOf(a), r.prefs.first().favorites)
    }
}
