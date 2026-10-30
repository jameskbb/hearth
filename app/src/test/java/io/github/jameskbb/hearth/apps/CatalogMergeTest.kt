package io.github.jameskbb.hearth.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.util.Locale

class CatalogMergeTest {
    private val own = "io.example.hearth"
    private val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.SECONDARY }
    private val order = Comparator<String> { a, b -> collator.compare(a, b) }

    private fun l(pkg: String, act: String = ".Main", label: String = pkg, updated: Long = 1L) =
        Launchable(pkg, act, label, updated)

    private fun merge(tv: List<Launchable>, other: List<Launchable>) =
        CatalogMerge.merge(tv, other, own, order)

    @Test fun activityInBothCategoriesIsOneTvApp() {
        val both = l("com.example.video", label = "Video")
        val result = merge(listOf(both), listOf(both))
        assertEquals(1, result.size)
        assertTrue(result.single().isTvApp)
    }

    @Test fun launcherOnlyAppIsNotTv() {
        val result = merge(emptyList(), listOf(l("com.example.phone", label = "Phone app")))
        assertFalse(result.single().isTvApp)
    }

    @Test fun excludesHearthItself() {
        val result = merge(listOf(l(own, label = "Hearth")), listOf(l(own, label = "Hearth")))
        assertTrue(result.isEmpty())
    }

    @Test fun phoneEntryOfATvAppIsDropped() {
        val tv = l("com.example.music", ".TvMain", "Music")
        val phone = l("com.example.music", ".PhoneMain", "Music")
        val result = merge(listOf(tv), listOf(phone))
        assertEquals(listOf(AppKey("com.example.music/.TvMain")), result.map { it.key })
    }

    @Test fun twoTvActivitiesOfOnePackageBothListed() {
        val result = merge(listOf(l("com.example.a", ".One", "One"), l("com.example.a", ".Two", "Two")), emptyList())
        assertEquals(2, result.size)
    }

    @Test fun sameLabelActivitiesOfOnePackageAreOneApp() {
        val result = merge(
            listOf(
                l("com.example.cast", ".Main", "Cast"),
                l("com.example.cast", ".PowerDialog", "Cast"),
                l("com.example.cast", ".Awareness", " Cast "),
            ),
            emptyList(),
        )
        assertEquals(listOf(AppKey.of("com.example.cast", ".Main")), result.map { it.key })
    }

    @Test fun sortedByLabelCaseInsensitiveThenKey() {
        val result = merge(
            listOf(l("p.zeta", label = "zeta"), l("p.alpha", label = "Alpha")),
            listOf(l("p.beta", label = "beta"), l("p.beta2", label = "Beta")),
        )
        assertEquals(listOf("Alpha", "beta", "Beta", "zeta"), result.map { it.label })
        assertEquals("p.beta/.Main", result[1].key.id)
    }

    @Test fun accentedLabelsSortWithTheirBaseLetter() {
        val result = merge(emptyList(), listOf(l("p.z", label = "Zoo"), l("p.e", label = "Écran"), l("p.f", label = "Film")))
        assertEquals(listOf("Écran", "Film", "Zoo"), result.map { it.label })
    }

    @Test fun blankLabelFallsBackToPackageName() {
        val result = merge(listOf(l("com.example.nolabel", label = "  ")), emptyList())
        assertEquals("com.example.nolabel", result.single().label)
    }

    @Test fun keepsLastUpdateTimeAndKey() {
        val entry = merge(listOf(l("com.example.x", ".Act", "X", updated = 42L)), emptyList()).single()
        assertEquals(42L, entry.lastUpdateTime)
        assertEquals("com.example.x", entry.key.packageName)
        assertEquals(".Act", entry.key.activityName)
    }

    @Test fun duplicateResultsCollapse() {
        val x = l("com.example.x")
        assertEquals(1, merge(listOf(x, x), emptyList()).size)
        assertEquals(1, merge(emptyList(), listOf(x, x)).size)
    }
}
