package io.github.jameskbb.hearth.prefs

import io.github.jameskbb.hearth.apps.AppKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesOpsTest {
    private val a = AppKey("pkg.a/.Main")
    private val b = AppKey("pkg.b/.Main")
    private val c = AppKey("pkg.c/.Main")
    private val d = AppKey("pkg.d/.Main")

    private fun favs(vararg ids: String) = HearthPrefs(favorites = ids.map(::AppKey))

    // addFavorite

    @Test fun addAppendsAtEnd() {
        val p = FavoritesOps.addFavorite(favs(a.id, b.id), c)
        assertEquals(listOf(a, b, c), p.favorites)
    }

    @Test fun addDoesNotDuplicate() {
        val start = favs(a.id, b.id)
        val p = FavoritesOps.addFavorite(start, a)
        assertEquals(listOf(a, b), p.favorites)
        assertSame(start, p)
    }

    @Test fun addUnhides() {
        val p = FavoritesOps.addFavorite(HearthPrefs(hidden = setOf(a, b)), a)
        assertEquals(listOf(a), p.favorites)
        assertEquals(setOf(b), p.hidden)
    }

    // removeFavorite

    @Test fun removeKeepsOrderOfOthers() {
        val p = FavoritesOps.removeFavorite(favs(a.id, b.id, c.id, d.id), b)
        assertEquals(listOf(a, c, d), p.favorites)
    }

    @Test fun removeUnknownIsNoOp() {
        val start = favs(a.id, b.id)
        assertSame(start, FavoritesOps.removeFavorite(start, c))
    }

    @Test fun removeDoesNotHide() {
        val p = FavoritesOps.removeFavorite(favs(a.id), a)
        assertTrue(p.hidden.isEmpty())
    }

    // move

    @Test fun moveRightByOne() {
        assertEquals(listOf(b, a, c), FavoritesOps.move(favs(a.id, b.id, c.id), a, 1).favorites)
    }

    @Test fun moveLeftByOne() {
        assertEquals(listOf(a, c, b), FavoritesOps.move(favs(a.id, b.id, c.id), c, -1).favorites)
    }

    @Test fun moveClampsAtEnd() {
        assertEquals(listOf(b, c, a), FavoritesOps.move(favs(a.id, b.id, c.id), a, 10).favorites)
    }

    @Test fun moveClampsAtStart() {
        assertEquals(listOf(c, a, b), FavoritesOps.move(favs(a.id, b.id, c.id), c, -10).favorites)
    }

    @Test fun moveExtremeDeltaDoesNotOverflow() {
        assertEquals(listOf(b, c, a), FavoritesOps.move(favs(a.id, b.id, c.id), a, Int.MAX_VALUE).favorites)
        assertEquals(listOf(c, a, b), FavoritesOps.move(favs(a.id, b.id, c.id), c, Int.MIN_VALUE).favorites)
    }

    @Test fun movePastBoundIsNoOp() {
        val start = favs(a.id, b.id, c.id)
        assertSame(start, FavoritesOps.move(start, a, -1))
        assertSame(start, FavoritesOps.move(start, c, 1))
        assertSame(start, FavoritesOps.move(start, b, 0))
    }

    @Test fun moveUnknownIsNoOp() {
        val start = favs(a.id, b.id)
        assertSame(start, FavoritesOps.move(start, c, 1))
    }

    @Test fun moveSingleFavoriteIsNoOp() {
        val start = favs(a.id)
        assertSame(start, FavoritesOps.move(start, a, 1))
    }

    // moveTo

    @Test fun moveToAbsoluteIndex() {
        assertEquals(listOf(b, c, a, d), FavoritesOps.moveTo(favs(a.id, b.id, c.id, d.id), a, 2).favorites)
        assertEquals(listOf(d, a, b, c), FavoritesOps.moveTo(favs(a.id, b.id, c.id, d.id), d, 0).favorites)
    }

    @Test fun moveToClamps() {
        assertEquals(listOf(b, c, a), FavoritesOps.moveTo(favs(a.id, b.id, c.id), a, 99).favorites)
        assertEquals(listOf(c, a, b), FavoritesOps.moveTo(favs(a.id, b.id, c.id), c, -5).favorites)
    }

    @Test fun moveToUnknownIsNoOp() {
        val start = favs(a.id)
        assertSame(start, FavoritesOps.moveTo(start, b, 0))
    }

    @Test fun moveToKeepsSize() {
        val p = FavoritesOps.moveTo(favs(a.id, b.id, c.id, d.id), b, 3)
        assertEquals(listOf(a, c, d, b), p.favorites)
    }

    // hide / unhide

    @Test fun hideRemovesFromFavoritesAndAddsHidden() {
        val p = FavoritesOps.hide(favs(a.id, b.id, c.id), b)
        assertEquals(listOf(a, c), p.favorites)
        assertEquals(setOf(b), p.hidden)
    }

    @Test fun hideNonFavorite() {
        val p = FavoritesOps.hide(favs(a.id), c)
        assertEquals(listOf(a), p.favorites)
        assertEquals(setOf(c), p.hidden)
    }

    @Test fun hideTwiceIsNoOp() {
        val once = FavoritesOps.hide(favs(a.id), a)
        assertSame(once, FavoritesOps.hide(once, a))
    }

    @Test fun unhideDoesNotReAdd() {
        val hidden = FavoritesOps.hide(favs(a.id, b.id), a)
        val p = FavoritesOps.unhide(hidden, a)
        assertEquals(listOf(b), p.favorites)
        assertTrue(p.hidden.isEmpty())
    }

    @Test fun unhideUnknownIsNoOp() {
        val start = HearthPrefs(hidden = setOf(a))
        assertSame(start, FavoritesOps.unhide(start, b))
    }

    // seed

    @Test fun seedTakesFirstTvAppsInOrder() {
        val p = FavoritesOps.seed(HearthPrefs(), listOf(c, a, b))
        assertEquals(listOf(c, a, b), p.favorites)
        assertTrue(p.seeded)
    }

    @Test fun seedRespectsMax() {
        val many = (1..10).map { AppKey("pkg.$it/.Main") }
        assertEquals(many.take(6), FavoritesOps.seed(HearthPrefs(), many).favorites)
        assertEquals(many.take(2), FavoritesOps.seed(HearthPrefs(), many, max = 2).favorites)
    }

    @Test fun seedNonPositiveMaxAddsNothingButMarksSeeded() {
        val p = FavoritesOps.seed(HearthPrefs(), listOf(a, b), max = 0)
        assertTrue(p.favorites.isEmpty())
        assertTrue(p.seeded)
        assertTrue(FavoritesOps.seed(HearthPrefs(), listOf(a), max = -3).favorites.isEmpty())
    }

    @Test fun seedSkipsHiddenAndStillFillsToMax() {
        val p = FavoritesOps.seed(HearthPrefs(hidden = setOf(a)), listOf(a, b, c, d), max = 2)
        assertEquals(listOf(b, c), p.favorites)
        assertEquals(setOf(a), p.hidden)
    }

    @Test fun seedSkipsDuplicates() {
        val p = FavoritesOps.seed(HearthPrefs(), listOf(a, a, b, a, c), max = 3)
        assertEquals(listOf(a, b, c), p.favorites)
    }

    @Test fun seedOnlyOnce() {
        val first = FavoritesOps.seed(HearthPrefs(), listOf(a, b))
        val cleared = FavoritesOps.removeFavorite(FavoritesOps.removeFavorite(first, a), b)
        val again = FavoritesOps.seed(cleared, listOf(c, d))
        assertSame(cleared, again)
        assertTrue(again.favorites.isEmpty())
    }

    @Test fun seedWithEmptyListMarksSeeded() {
        val p = FavoritesOps.seed(HearthPrefs(), emptyList())
        assertTrue(p.seeded)
        assertTrue(p.favorites.isEmpty())
    }

    @Test fun seedKeepsFavoritesTheUserAlreadyChose() {
        val p = FavoritesOps.seed(favs(d.id), listOf(a, b, c))
        assertEquals(listOf(d), p.favorites)
        assertTrue(p.seeded)
    }

    @Test fun defaultsAreUnseeded() {
        assertFalse(HearthPrefs().seeded)
    }

    @Test fun inputKeysAreFavoritesAndHiddenLikeApps() {
        val input = AppKey.forInput("tv.example/.HdmiService/HW1")
        val added = FavoritesOps.addFavorite(favs(a.id), input)
        assertEquals(listOf(a, input), added.favorites)
        assertEquals(listOf(input, a), FavoritesOps.move(added, input, -1).favorites)
        val hidden = FavoritesOps.hide(added, input)
        assertEquals(listOf(a), hidden.favorites)
        assertTrue(input in hidden.hidden)
        assertFalse(input in FavoritesOps.unhide(hidden, input).hidden)
    }
}
