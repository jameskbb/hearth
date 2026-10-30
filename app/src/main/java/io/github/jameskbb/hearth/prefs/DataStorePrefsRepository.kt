package io.github.jameskbb.hearth.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * [PrefsRepository] backed by DataStore Preferences on disk, so preferences survive
 * restarts and reboots. Create exactly one instance per process (DataStore requires a
 * single owner per file); [DefaultAppContainer][io.github.jameskbb.hearth.DefaultAppContainer]
 * does that.
 */
class DataStorePrefsRepository(private val store: DataStore<Preferences>) : PrefsRepository {

    override val prefs: Flow<HearthPrefs> = store.data
        // A read error (not corruption, which the handler below repairs) yields defaults
        // instead of crashing the home screen.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(PrefsCodec::read)
        .distinctUntilChanged()

    /**
     * Runs [transform] inside DataStore.edit, which serializes all writers, so concurrent
     * updates never lose each other's changes. A transform that returns an equal value
     * writes nothing.
     */
    override suspend fun update(transform: (HearthPrefs) -> HearthPrefs) {
        store.edit { current ->
            val before = PrefsCodec.read(current)
            val after = transform(before)
            // Overwrites only Hearth's own keys; anything else in the file is left alone.
            if (after != before) PrefsCodec.write(current, after)
        }
    }

    companion object {
        const val FILE_NAME = "hearth"

        /**
         * Builds the on-disk store, owned by the application [scope] (file work runs on
         * Dispatchers.IO). A corrupt file is replaced with defaults, not fatal.
         */
        fun create(context: Context, scope: CoroutineScope): DataStorePrefsRepository =
            DataStorePrefsRepository(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                    produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
                ),
            )
    }
}
