package io.github.jameskbb.hearth.prefs

import io.github.jameskbb.hearth.apps.AppKey

/**
 * Pure, testable operations on [HearthPrefs]. Every UI action that changes favorites or
 * hidden apps goes through one of these inside [PrefsRepository.update].
 *
 * Contract (implemented and unit-tested by the data track):
 * - addFavorite appends at the end; no duplicates; un-hides the app.
 * - removeFavorite removes it; other favorites keep their order.
 * - move shifts one favorite by [delta] positions, clamped to the list bounds.
 * - moveTo places one favorite at an absolute index, clamped.
 * - hide removes the app from favorites and adds it to hidden.
 * - unhide removes it from hidden (it does not re-add it to favorites).
 * - seed sets favorites once from the first TV apps if not yet seeded; later calls are no-ops.
 *
 * Operations that would not change anything return the same instance, so callers (and
 * DataStore, which skips writes of equal data) can cheaply tell a no-op apart.
 */
object FavoritesOps {

    /** Appends [key] unless it is already a favorite. Either way, [key] is no longer hidden. */
    fun addFavorite(p: HearthPrefs, key: AppKey): HearthPrefs {
        val favorites = if (key in p.favorites) p.favorites else p.favorites + key
        val hidden = if (key in p.hidden) p.hidden - key else p.hidden
        if (favorites === p.favorites && hidden === p.hidden) return p
        return p.copy(favorites = favorites, hidden = hidden)
    }

    fun removeFavorite(p: HearthPrefs, key: AppKey): HearthPrefs {
        if (key !in p.favorites) return p
        return p.copy(favorites = p.favorites.filter { it != key })
    }

    /** Shifts [key] by [delta] (negative is toward the front). Unknown keys are a no-op. */
    fun move(p: HearthPrefs, key: AppKey, delta: Int): HearthPrefs {
        val from = p.favorites.indexOf(key)
        if (from < 0) return p
        // Long arithmetic so an extreme delta cannot overflow before clamping.
        val target = (from.toLong() + delta).coerceIn(0L, (p.favorites.size - 1).toLong()).toInt()
        return moveTo(p, key, target)
    }

    /** Places [key] at [index], clamped to the list bounds. Unknown keys are a no-op. */
    fun moveTo(p: HearthPrefs, key: AppKey, index: Int): HearthPrefs {
        val from = p.favorites.indexOf(key)
        if (from < 0) return p
        val target = index.coerceIn(0, p.favorites.size - 1)
        if (target == from) return p
        val list = p.favorites.toMutableList()
        list.removeAt(from)
        list.add(target, key)
        return p.copy(favorites = list)
    }

    fun hide(p: HearthPrefs, key: AppKey): HearthPrefs {
        if (key in p.hidden && key !in p.favorites) return p
        return p.copy(favorites = p.favorites.filter { it != key }, hidden = p.hidden + key)
    }

    fun unhide(p: HearthPrefs, key: AppKey): HearthPrefs {
        if (key !in p.hidden) return p
        return p.copy(hidden = p.hidden - key)
    }

    /**
     * Seeds favorites once, from the first [max] of [tvApps] (in the order given) that are not
     * hidden, and marks the prefs as seeded. Later calls are no-ops.
     *
     * If the user already added favorites before seeding ran, those are kept as they are and
     * nothing is added: the user's own choice wins. Call this only after the catalog has
     * loaded, because an empty [tvApps] still counts as the one seeding.
     */
    fun seed(p: HearthPrefs, tvApps: List<AppKey>, max: Int = 6): HearthPrefs {
        if (p.seeded) return p
        if (p.favorites.isNotEmpty()) return p.copy(seeded = true)
        val picks = tvApps.asSequence()
            .filter { it !in p.hidden }
            .distinct()
            .take(max.coerceAtLeast(0))
            .toList()
        return p.copy(favorites = picks, seeded = true)
    }
}
