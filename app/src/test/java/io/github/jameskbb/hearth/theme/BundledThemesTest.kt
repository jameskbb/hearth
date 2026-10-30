package io.github.jameskbb.hearth.theme

import io.github.jameskbb.hearth.ui.theme.DefaultPalette
import io.github.jameskbb.hearth.ui.theme.ThemeMotion
import io.github.jameskbb.hearth.ui.theme.toHearthPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Every pack in app/src/main/assets/themes passes the same checks the app runs at launch, with
 * image sizes read from the WebP or PNG headers here (the app uses BitmapFactory).
 */
class BundledThemesTest {
    private val root = File("src/main/assets/themes")

    private fun packs(): List<File> = root.listFiles().orEmpty().filter { File(it, "theme.json").isFile }.sortedBy { it.name }

    @Test fun everyBundledPackIsValid() {
        val dirs = packs()
        assertTrue("no bundled packs under ${root.path}", dirs.isNotEmpty())
        for (dir in dirs) {
            val manifest = ThemeManifestCodec.parse(File(dir, "theme.json").readText())
            assertEquals("id of ${dir.name}", dir.name, manifest.id.name)
            val problem = ThemeValidation.check(
                manifest,
                exists = { File(dir, it).isFile },
                bounds = { imageBounds(File(dir, it)) },
            )
            assertNull("${dir.name}: ${problem?.message}", problem)
        }
    }

    @Test fun specialThemesAreInTheGalleryButManualByDefault() {
        val manifests = packs().map { ThemeManifestCodec.parse(File(it, "theme.json").readText()) }
        val gallery = manifests.map { ThemeOrder.info(it, bundled = true) }
        val special = setOf("open-bay", "harvest-evening", "christmas", "new-year", "easter", "july-fourth")
            .map(::ThemeId).toSet()
        assertEquals(13, gallery.size)
        assertEquals(special, gallery.filterNot { it.autoRotationEligible }.map { it.id }.toSet())
        assertTrue(manifests.filter { it.id in special }.all { it.dayparts.isEmpty() })
        assertTrue(ThemeScheduleConfig.Recommended.periods.none { period -> period.themeIds.any { it in special } })
        val available = gallery.map { it.id }
        val automatic = gallery.filter { it.autoRotationEligible }.map { it.id }
        assertEquals(available.toSet() - special, ThemeSchedule.surprisePool(emptySet(), gallery))
        for (index in 0..20) {
            val implicit = ThemeSchedule.resolveSurprise(emptySet(), null, available, 0L,
                java.time.ZoneOffset.UTC, autoEligible = automatic) { index }
            assertTrue(implicit.themeId !in special)
        }
        for (id in special) {
            assertEquals(id, ThemeSchedule.resolveSingle(id, available).themeId)
            val explicit = ThemeSchedule.resolveSurprise(setOf(id), null, available, 0L,
                java.time.ZoneOffset.UTC, autoEligible = automatic)
            assertEquals(id, explicit.themeId)
        }
    }

    @Test fun duskIsBundledAndMatchesTheFallbackPalette() {
        val dusk = ThemeManifestCodec.parse(File(root, "dusk/theme.json").readText())
        assertEquals(DefaultPalette, dusk.palette.toHearthPalette())
    }

    @Test fun theOrderFileListsEveryBundledPack() {
        val order = ThemeOrder.parseOrder(File(root, "order.txt").readText())
        for (dir in packs()) assertTrue("${dir.name} is not in order.txt", dir.name in order)
    }

    @Test fun sourceAndShippedManifestsStayInSync() {
        for (dir in packs()) {
            val source = File("../design/art/${dir.name}/theme.json")
            assertTrue("${dir.name} has no source manifest", source.isFile)
            assertEquals(
                "${dir.name}: render source and shipped manifest differ",
                ThemeManifestCodec.parse(source.readText()),
                ThemeManifestCodec.parse(File(dir, "theme.json").readText()),
            )
        }
    }

    @Test fun everyBundledSceneKeepsMovingAfterItsIntro() {
        for (dir in packs()) {
            val m = ThemeManifestCodec.parse(File(dir, "theme.json").readText())
            // Check a late interval too: Desert's one-time sky fade is not its only motion.
            for (start in listOf(0.0, 240.0, 600.0)) {
                val moves = m.layers.any { layer ->
                    val bounds = imageBounds(File(dir, layer.image))!!
                    val first = ThemeMotion.frame(layer, bounds.width, bounds.height, 960f, m.sway, start)
                    val next = ThemeMotion.frame(layer, bounds.width, bounds.height, 960f, m.sway, start + 10.0)
                    maxOf(first.alpha, next.alpha) > 0.1f &&
                        (abs(first.left - next.left) >= 1f || abs(first.alpha - next.alpha) >= 0.02f)
                }
                assertTrue("${dir.name} has no sustained motion at $start seconds", moves)
            }
        }
    }

    @Test fun daylightCloudsTravelSlowlyButVisibly() {
        for (id in listOf("morning-glow", "sunlit-valley", "golden-prairie")) {
            val m = ThemeManifestCodec.parse(File(root, "$id/theme.json").readText())
            val clouds = m.layers.single { it.image == "clouds.webp" }
            assertTrue("$id clouds must wrap continuously", clouds.wrap)
            val drift = clouds.drift!!
            val dpPerMinute = abs(drift.xDp) * 60f / drift.periodSeconds
            assertTrue("$id cloud travel is $dpPerMinute dp/min", dpPerMinute in 20f..24f)
        }
    }

    @Test fun everyBundledLayerCoversItsHorizontalMotionRange() {
        for (dir in packs()) {
            val m = ThemeManifestCodec.parse(File(dir, "theme.json").readText())
            for (layer in m.layers) {
                val bounds = imageBounds(File(dir, layer.image))!!
                for (second in 0..900 step 7) {
                    val f = ThemeMotion.frame(layer, bounds.width, bounds.height, 1920f, m.sway, second.toDouble())
                    assertTrue("${dir.name}/${layer.image}: left edge at $second", f.left <= 0.01f)
                    if (f.tiled) {
                        assertTrue("${dir.name}/${layer.image}: repeat origin at $second", f.left > -f.width - 0.01f)
                    } else {
                        assertTrue("${dir.name}/${layer.image}: right edge at $second", f.left + f.width >= 1919.99f)
                    }
                }
            }
        }
    }

    /** Width and height from a WebP (VP8, VP8L, VP8X) or PNG header; null for anything else. */
    private fun imageBounds(f: File): ImageBounds? {
        if (!f.isFile) return null
        val b = f.inputStream().use { it.readNBytes(32) }
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun le24(i: Int) = le16(i) or (u8(i + 2) shl 16)
        fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
        val tag = String(b, 0, 4, Charsets.ISO_8859_1)
        if (tag == "\u0089PNG") return ImageBounds(be32(16), be32(20))
        if (tag != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WEBP") return null
        return when (String(b, 12, 4, Charsets.ISO_8859_1)) {
            "VP8L" -> {
                val bits = u8(21).toLong() or (u8(22).toLong() shl 8) or (u8(23).toLong() shl 16) or (u8(24).toLong() shl 24)
                ImageBounds(((bits and 0x3FFF) + 1).toInt(), (((bits shr 14) and 0x3FFF) + 1).toInt())
            }
            "VP8X" -> ImageBounds(le24(24) + 1, le24(27) + 1)
            "VP8 " -> ImageBounds(le16(26) and 0x3FFF, le16(28) and 0x3FFF)
            else -> null
        }
    }
}
