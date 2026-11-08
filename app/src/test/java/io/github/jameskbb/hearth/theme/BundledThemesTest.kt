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
        val special = setOf("open-bay", "harvest-evening", "halloween", "christmas", "new-year", "easter", "july-fourth")
            .map(::ThemeId).toSet()
        assertEquals(14, gallery.size)
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

    // region weather variants (decision 0013); trivially true for packs without a weather block

    private val occasionPacks = setOf("harvest-evening", "halloween", "christmas", "new-year", "easter", "july-fourth")

    private fun manifests() = packs().map { it to ThemeManifestCodec.parse(File(it, "theme.json").readText()) }

    @Test fun everyBundledVariantResolvesAndCoversItsMotionRange() {
        for ((dir, m) in manifests()) {
            for (variant in m.weather) {
                val layers = ThemeVariants.layers(m, variant)
                assertTrue("${dir.name}/${variant.id}: ${layers.size} layers", layers.size in 1..ThemeManifestCodec.MAX_VARIANT_LAYERS)
                for (layer in layers) {
                    val bounds = imageBounds(File(dir, layer.image))
                    assertTrue("${dir.name}/${variant.id}: ${layer.image} is not readable", bounds != null)
                    for (second in 0..900 step 7) {
                        val f = ThemeMotion.frame(layer, bounds!!.width, bounds.height, 1920f, m.sway, second.toDouble())
                        val at = "${dir.name}/${variant.id}/${layer.image} at $second"
                        assertTrue("$at: left edge", f.left <= 0.01f)
                        if (f.tiled) assertTrue("$at: repeat origin", f.left > -f.width - 0.01f)
                        else assertTrue("$at: right edge", f.left + f.width >= 1919.99f)
                        if (f.tiledVertically) assertTrue("$at: fall origin", f.top <= 0.01f && f.top > -f.height - 0.01f)
                    }
                }
            }
        }
    }

    @Test fun everyPaletteKeepsTextReadableOverItsWindowColor() {
        for ((dir, m) in manifests()) {
            val palettes = listOf("base" to m.palette) + m.weather.map { it.id to ThemeVariants.palette(m, it) }
            for ((name, p) in palettes) {
                val ink = contrast(p.ink, p.window)
                val muted = contrast(over(p.inkMuted, p.window), p.window)
                assertTrue("${dir.name}/$name: ink contrast $ink", ink >= 4.5)
                assertTrue("${dir.name}/$name: muted ink contrast $muted", muted >= 4.5)
            }
        }
    }

    @Test fun variantArtStaysWithinItsEncodedBudget() {
        for ((dir, m) in manifests()) {
            val base = m.layers.mapTo(HashSet()) { it.image } + listOfNotNull(m.preview)
            val variantOnly = m.weather.flatMap { it.images }.toSet() - base
            val bytes = variantOnly.sumOf { File(dir, it).length() }
            val budget = if (dir.name in occasionPacks) OCCASION_VARIANT_BYTES else EVERYDAY_VARIANT_BYTES
            assertTrue("${dir.name}: $bytes bytes of weather art, budget $budget", bytes <= budget)
        }
    }

    @Test fun everyVariantHasNotes() {
        for ((dir, m) in manifests()) {
            if (m.weather.isEmpty()) continue
            val notes = File(dir, "notes.txt")
            assertTrue("${dir.name} has weather variants but no notes.txt", notes.isFile)
            val text = notes.readText()
            for (variant in m.weather) assertTrue("${dir.name}: notes.txt does not mention ${variant.id}", variant.id in text)
        }
    }

    /** WCAG contrast of two opaque sRGB colors. */
    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** [top] with its alpha over opaque [bottom]. */
    private fun over(top: Int, bottom: Int): Int {
        val a = ((top ushr 24) and 0xFF) / 255.0
        fun mix(shift: Int) = (((top shr shift) and 0xFF) * a + ((bottom shr shift) and 0xFF) * (1 - a)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private companion object {
        /** About 2.5 MB of encoded variant art per everyday pack and 1 MB per occasion pack (decision 0013). */
        const val EVERYDAY_VARIANT_BYTES = 2_621_440L
        const val OCCASION_VARIANT_BYTES = 1_048_576L
    }

    // endregion

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
