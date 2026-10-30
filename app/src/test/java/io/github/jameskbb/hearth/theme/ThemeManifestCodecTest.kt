package io.github.jameskbb.hearth.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ThemeManifestCodecTest {

    /** The example from docs/theme-packs.md, plus every optional layer field. */
    private val full = """
        {
          "format": 1,
          "id": "misty-forest",
          "name": "Misty Forest",
          "author": "Hearth",
          "description": "Pine ridges in drifting fog",
          "dayparts": ["morning"],
          "palette": {
            "ink": "#FFEEF4F0",
            "inkMuted": "#B8EEF4F0",
            "accent": "#FF8FD9B8",
            "glow": "#5C8FD9B8",
            "shade": "#E0071017",
            "shadeMid": "#73071017",
            "window": "#FF071017"
          },
          "preview": "preview.webp",
          "motion": { "sway": { "amplitude": 10, "period": 90 } },
          "layers": [
            { "image": "sky.webp" },
            { "image": "mountains.webp", "parallax": 0.2 },
            { "image": "fog-far.webp", "parallax": 0.3, "wrap": true,
              "drift": { "x": 80, "period": 150 },
              "opacity": { "min": 0.55, "max": 0.85, "period": 40 } },
            { "image": "stars.webp", "y": 12.5, "blend": "screen",
              "opacity": { "min": 0.75, "max": 1, "period": 30, "phase": 0.5 },
              "fade": { "from": 0, "to": 1, "delay": 110, "duration": 120 } },
            { "image": "pines-near.webp", "parallax": 1.0 }
          ]
        }
    """.trimIndent()

    private fun minimal(extra: String = "", layers: String = """[{ "image": "sky.webp" }]""", palette: String? = null) = """
        {
          "format": 1, "id": "plain", "name": "Plain",
          "palette": ${palette ?: """{ "window": "#0A0E24", "accent": "#FFB45C" }"""},
          "layers": $layers
          $extra
        }
    """.trimIndent()

    private fun problemOf(json: String): ThemeProblem = try {
        ThemeManifestCodec.parse(json)
        fail("expected a ThemeException")
        throw AssertionError()
    } catch (e: ThemeException) {
        e.problem
    }

    @Test fun rotationEligibilityDefaultsToTrueAndRequiresABoolean() {
        assertTrue(ThemeManifestCodec.parse(minimal()).autoRotationEligible)
        val manual = ThemeManifestCodec.parse(minimal(extra = ", \"autoRotationEligible\": false"))
        assertFalse(manual.autoRotationEligible)
        assertFalse(ThemeOrder.info(manual, bundled = false).autoRotationEligible)
        for (bad in listOf("null", "0", "\"false\"")) {
            assertEquals(ThemeProblem.BadValue("autoRotationEligible"),
                problemOf(minimal(extra = ", \"autoRotationEligible\": $bad")))
        }
    }

    @Test fun parsesEveryFieldOfTheSpecExample() {
        val m = ThemeManifestCodec.parse(full)
        assertEquals(1, m.format)
        assertEquals(ThemeId("misty-forest"), m.id)
        assertEquals("Misty Forest", m.name)
        assertEquals("Hearth", m.author)
        assertEquals("Pine ridges in drifting fog", m.description)
        assertEquals(setOf(Daypart.Morning), m.dayparts)
        assertEquals(
            PaletteSpec(
                window = 0xFF071017.toInt(),
                accent = 0xFF8FD9B8.toInt(),
                ink = 0xFFEEF4F0.toInt(),
                inkMuted = 0xB8EEF4F0.toInt(),
                glow = 0x5C8FD9B8,
                shade = 0xE0071017.toInt(),
                shadeMid = 0x73071017,
            ),
            m.palette,
        )
        assertEquals("preview.webp", m.preview)
        assertEquals(Sway(10f, 90f), m.sway)
        assertEquals(5, m.layers.size)
        assertEquals(LayerSpec("sky.webp"), m.layers[0])
        assertEquals(LayerSpec("mountains.webp", parallax = 0.2f), m.layers[1])
        assertEquals(
            LayerSpec(
                "fog-far.webp", parallax = 0.3f, wrap = true,
                drift = Drift(80f, 150f), opacity = Breath(0.55f, 0.85f, 40f),
            ),
            m.layers[2],
        )
        assertEquals(
            LayerSpec(
                "stars.webp", yDp = 12.5f, blend = LayerBlend.Screen,
                opacity = Breath(0.75f, 1f, 30f, 0.5f), fade = Fade(0f, 1f, 110f, 120f),
            ),
            m.layers[3],
        )
        assertEquals(1f, m.layers[4].parallax)
    }

    @Test fun minimalManifestGetsTheDocumentedDefaults() {
        val m = ThemeManifestCodec.parse(minimal())
        assertNull(m.author)
        assertNull(m.description)
        assertNull(m.preview)
        assertEquals(emptySet<Daypart>(), m.dayparts)
        assertEquals(Sway(0f, 90f), m.sway)
        val p = m.palette
        assertEquals(0xFF0A0E24.toInt(), p.window) // #RRGGBB is opaque
        assertEquals(0xFFFFB45C.toInt(), p.accent)
        assertEquals(0xFFF4F1EC.toInt(), p.ink)
        assertEquals(0xB8F4F1EC.toInt(), p.inkMuted)
        assertEquals(0x66FFB45C, p.glow)
        assertEquals(0xE00A0E24.toInt(), p.shade)
        assertEquals(0x730A0E24, p.shadeMid)
        assertEquals(listOf(LayerSpec("sky.webp")), m.layers)
    }

    @Test fun inkMutedFollowsACustomInk() {
        val m = ThemeManifestCodec.parse(minimal(palette = """{ "window": "#000000", "accent": "#FFFFFF", "ink": "#FF102030" }"""))
        assertEquals(0xB8102030.toInt(), m.palette.inkMuted)
    }

    @Test fun swayPeriodDefaultsWhenOnlyAmplitudeIsGiven() {
        val m = ThemeManifestCodec.parse(minimal(extra = """, "motion": { "sway": { "amplitude": 6 } }"""))
        assertEquals(Sway(6f, 90f), m.sway)
    }

    @Test fun aNewerFormatNeedsANewerHearth() {
        // Checked first, so a future manifest with a different shape still says why.
        assertEquals(ThemeProblem.NeedsNewerHearth(2), problemOf("""{ "format": 2, "layers": "something new" }"""))
    }

    @Test fun formatIsRequired() {
        assertEquals(ThemeProblem.MissingField("format"), problemOf(minimal().replace("\"format\": 1,", "")))
        assertEquals(ThemeProblem.BadValue("format"), problemOf(minimal().replace("\"format\": 1", "\"format\": 0")))
    }

    @Test fun autoIsReserved() {
        assertEquals(ThemeProblem.BadId("auto"), problemOf(minimal().replace("\"plain\"", "\"auto\"")))
    }

    @Test fun idsAreLowercaseLettersDigitsAndHyphens() {
        assertEquals(ThemeProblem.BadId("Misty Forest"), problemOf(minimal().replace("\"plain\"", "\"Misty Forest\"")))
        assertEquals(ThemeProblem.BadId("x"), problemOf(minimal().replace("\"plain\"", "\"x\"")))
        assertTrue(ThemeId.isValidPackId("desert-twilight"))
        assertTrue(ThemeId.isValidPackId("a".repeat(40)))
        assertFalse(ThemeId.isValidPackId("a".repeat(41)))
        assertFalse(ThemeId.isValidPackId("auto"))
        assertFalse(ThemeId.isValidPackId("dusk_2"))
    }

    @Test fun nineLayersAreTooMany() {
        val nine = (1..9).joinToString(",", "[", "]") { """{ "image": "l$it.webp" }""" }
        assertEquals(ThemeProblem.LayerCount(9), problemOf(minimal(layers = nine)))
    }

    @Test fun zeroLayersAreTooFew() {
        assertEquals(ThemeProblem.LayerCount(0), problemOf(minimal(layers = "[]")))
    }

    @Test fun opacityAboveOneIsRejected() {
        val layers = """[{ "image": "a.webp", "opacity": { "min": 0.5, "max": 1.2, "period": 40 } }]"""
        assertEquals(ThemeProblem.BadValue("layers[0].opacity.max"), problemOf(minimal(layers = layers)))
    }

    @Test fun opacityMinAboveMaxIsRejected() {
        val layers = """[{ "image": "a.webp", "opacity": { "min": 0.9, "max": 0.5, "period": 40 } }]"""
        assertEquals(ThemeProblem.BadValue("layers[0].opacity.min"), problemOf(minimal(layers = layers)))
    }

    @Test fun periodsMustBePositive() {
        val layers = """[{ "image": "a.webp", "drift": { "x": 10, "period": 0 } }]"""
        assertEquals(ThemeProblem.BadValue("layers[0].drift.period"), problemOf(minimal(layers = layers)))
        assertEquals(
            ThemeProblem.BadValue("motion.sway.period"),
            problemOf(minimal(extra = """, "motion": { "sway": { "amplitude": 6, "period": -1 } }""")),
        )
    }

    @Test fun parallaxOutsideZeroToOneIsRejected() {
        val layers = """[{ "image": "a.webp", "parallax": 1.5 }]"""
        assertEquals(ThemeProblem.BadValue("layers[0].parallax"), problemOf(minimal(layers = layers)))
    }

    @Test fun malformedJsonIsReported() {
        assertEquals(ThemeProblem.MalformedManifest, problemOf("{ \"format\": 1, "))
        assertEquals(ThemeProblem.MalformedManifest, problemOf("[1, 2]"))
    }

    @Test fun windowIsRequired() {
        assertEquals(
            ThemeProblem.MissingField("palette.window"),
            problemOf(minimal(palette = """{ "accent": "#FFB45C" }""")),
        )
    }

    @Test fun paletteIsRequired() {
        val json = """{ "format": 1, "id": "plain", "name": "Plain", "layers": [{ "image": "a.webp" }] }"""
        assertEquals(ThemeProblem.MissingField("palette"), problemOf(json))
    }

    @Test fun badColorsAreRejected() {
        assertEquals(
            ThemeProblem.BadValue("palette.accent"),
            problemOf(minimal(palette = """{ "window": "#000000", "accent": "orange" }""")),
        )
        assertEquals(
            ThemeProblem.BadValue("palette.ink"),
            problemOf(minimal(palette = """{ "window": "#000000", "accent": "#FFFFFF", "ink": "#FFF" }""")),
        )
    }

    @Test fun unknownDaypartsAndBlendsAreRejected() {
        assertEquals(ThemeProblem.BadValue("dayparts"), problemOf(minimal(extra = """, "dayparts": ["dawn"]""")))
        val layers = """[{ "image": "a.webp", "blend": "multiply" }]"""
        assertEquals(ThemeProblem.BadValue("layers[0].blend"), problemOf(minimal(layers = layers)))
    }

    @Test fun nameIsRequiredAndShort() {
        assertEquals(ThemeProblem.MissingField("name"), problemOf(minimal().replace("\"name\": \"Plain\",", "")))
        assertEquals(ThemeProblem.BadValue("name"), problemOf(minimal().replace("\"Plain\"", "\"${"n".repeat(41)}\"")))
    }

    @Test fun imagePathsMustStayInsideThePack() {
        listOf("../escape.webp", "/abs.webp", "a/../../b.webp", "a\\\\b.webp", "").forEach { path ->
            val layers = """[{ "image": "$path" }]"""
            assertEquals(path, ThemeProblem.BadValue("layers[0].image"), problemOf(minimal(layers = layers)))
        }
        assertEquals(
            ThemeProblem.BadValue("preview"),
            problemOf(minimal(extra = """, "preview": "../p.webp"""")),
        )
    }

    @Test fun imagesInSubfoldersAreFine() {
        val m = ThemeManifestCodec.parse(minimal(layers = """[{ "image": "art/sky.webp" }]"""))
        assertEquals("art/sky.webp", m.layers[0].image)
    }

    @Test fun wrongTypesAreBadValues() {
        assertEquals(ThemeProblem.BadValue("layers[0].wrap"), problemOf(minimal(layers = """[{ "image": "a.webp", "wrap": "yes" }]""")))
        assertEquals(ThemeProblem.BadValue("layers[0].y"), problemOf(minimal(layers = """[{ "image": "a.webp", "y": "top" }]""")))
        assertEquals(ThemeProblem.BadValue("layers"), problemOf(minimal(layers = "{}")))
    }

    @Test fun unknownFieldsAreIgnored() {
        val m = ThemeManifestCodec.parse(minimal(extra = """, "license": "CC0", "x-notes": { "a": 1 }"""))
        assertEquals("Plain", m.name)
    }
}
