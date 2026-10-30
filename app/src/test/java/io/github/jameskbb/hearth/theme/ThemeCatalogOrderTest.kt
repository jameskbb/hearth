package io.github.jameskbb.hearth.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeCatalogOrderTest {
    private fun manifest(id: String) = ThemeManifest(
        format = 1, id = ThemeId(id), name = id, author = null, description = null, dayparts = emptySet(),
        palette = PaletteSpec(0, 0, 0, 0, 0, 0, 0), preview = null, sway = Sway(), layers = listOf(LayerSpec("sky.webp")),
    )

    private fun ok(location: String, id: String, bundled: Boolean) =
        ThemeOrder.Scanned(location, bundled, manifest(id), null, location)

    private fun broken(location: String, bundled: Boolean, problem: ThemeProblem) =
        ThemeOrder.Scanned(location, bundled, null, problem, location)

    private val order = listOf("misty-forest", "desert-twilight", "dusk", "aurora")

    @Test fun bundledFollowOrderFileThenInstalledById() {
        val result = ThemeOrder.arrange(
            listOf(
                ok("zz.zip", "zebra", bundled = false),
                ok("aurora", "aurora", bundled = true),
                ok("dusk", "dusk", bundled = true),
                ok("extra", "extra", bundled = true),
                ok("alpine", "alpine", bundled = false),
                ok("misty-forest", "misty-forest", bundled = true),
            ),
            order,
        )
        assertEquals(
            listOf("misty-forest", "dusk", "aurora", "extra", "alpine", "zebra"),
            result.accepted.map { it.manifest!!.id.name },
        )
        assertEquals(emptyList<ThemeFailure>(), result.failures)
    }

    @Test fun withoutAnOrderFileBundledAreAlphabetical() {
        val result = ThemeOrder.arrange(listOf(ok("dusk", "dusk", true), ok("aurora", "aurora", true)), emptyList())
        assertEquals(listOf("aurora", "dusk"), result.accepted.map { it.manifest!!.id.name })
    }

    @Test fun anInstalledPackCannotTakeABundledId() {
        val result = ThemeOrder.arrange(listOf(ok("my-dusk.zip", "dusk", false), ok("dusk", "dusk", true)), order)
        assertEquals(listOf("dusk"), result.accepted.map { it.location })
        assertEquals(listOf(ThemeFailure("my-dusk.zip", ThemeProblem.DuplicateId("dusk", byBundled = true))), result.failures)
        assertEquals("id dusk already used by a bundled theme", result.failures.single().problem.message)
    }

    @Test fun twoInstalledPacksWithOneIdKeepTheFirstByLocation() {
        val result = ThemeOrder.arrange(listOf(ok("b-copy", "glow", false), ok("a-copy", "glow", false)), order)
        assertEquals(listOf("a-copy"), result.accepted.map { it.location })
        assertEquals(ThemeProblem.DuplicateId("glow", byBundled = false), result.failures.single().problem)
    }

    @Test fun brokenPacksAreFailuresBundledFirst() {
        val result = ThemeOrder.arrange(
            listOf(
                broken("z.zip", false, ThemeProblem.MissingManifest),
                broken("old", true, ThemeProblem.NeedsNewerHearth(2)),
                ok("dusk", "dusk", true),
            ),
            order,
        )
        assertEquals(listOf("old", "z.zip"), result.failures.map { it.location })
        assertEquals(listOf("dusk"), result.accepted.map { it.location })
    }

    @Test fun orderFileSkipsBlankLinesAndComments() {
        assertEquals(listOf("a", "b"), ThemeOrder.parseOrder("# bundled order\na\n\n  b  \n"))
        assertEquals(emptyList<String>(), ThemeOrder.parseOrder(null))
    }
}
