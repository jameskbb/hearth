package io.github.jameskbb.hearth.theme

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Weather variants (decision 0013, section 2) against the fixture pack in test resources
 * (themes/weather-fixture): parsing, rejection, validation and budgets, matching with the sky
 * widening, and the resolved layer list and palette.
 */
class ThemeWeatherTest {
    private val fixtureText: String =
        requireNotNull(javaClass.getResource("/themes/weather-fixture/theme.json")).readText()
    private val fixture = ThemeManifestCodec.parse(fixtureText)

    /** The fixture's images by size: the base at full size, overlays at half or as strips. */
    private val sizes = mapOf(
        "sky.webp" to ImageBounds(1920, 1080),
        "haze.webp" to ImageBounds(960, 200),
        "valley.webp" to ImageBounds(1920, 520),
        "sky-snow.webp" to ImageBounds(1920, 1080),
        "sky-overcast.webp" to ImageBounds(1920, 1080),
        "snow-ground.webp" to ImageBounds(960, 240),
        "snowfall.webp" to ImageBounds(960, 540),
        "snow-clouds.webp" to ImageBounds(960, 300),
        "rain.webp" to ImageBounds(960, 540),
        "heat-haze.webp" to ImageBounds(960, 200),
    )

    private fun check(m: ThemeManifest, bounds: Map<String, ImageBounds> = sizes) =
        ThemeValidation.check(m, exists = { it in bounds }, bounds = { bounds[it] })

    private fun edited(edit: (JSONObject) -> Unit): String = JSONObject(fixtureText).apply(edit).toString()

    private fun variantEdited(index: Int, edit: (JSONObject) -> Unit): String =
        edited { root -> edit(root.getJSONArray("weather").getJSONObject(index)) }

    private fun problem(json: String): ThemeProblem = try {
        ThemeManifestCodec.parse(json)
        fail("expected a problem")
        throw AssertionError()
    } catch (e: ThemeException) {
        e.problem
    }

    // region codec

    @Test fun theFixtureParsesWithEveryPart() {
        assertEquals(listOf("snow", "snow-coming", "rain", "overcast", "hot"), fixture.weather.map { it.id })
        val snow = fixture.weather[0]
        assertEquals(MoodRule(setOf(SceneSky.Snow), setOf(SceneTemp.Freezing, SceneTemp.Cold), null), snow.rule)
        assertEquals(PalettePatch(window = 0xFF0C2236.toInt()), snow.palette)
        assertEquals(mapOf("sky.webp" to "sky-snow.webp"), snow.replace)
        assertEquals(setOf("haze.webp"), snow.hide)
        assertEquals("valley.webp", snow.add[0].after)
        assertNull(snow.add[1].after)
        assertEquals(Fall(40f, 60f), snow.add[1].spec.fall)
        assertTrue(snow.add[1].spec.wrap)
        assertEquals(setOf<SceneForecast?>(null), fixture.weather[3].rule.forecast)
    }

    @Test fun aPackWithoutWeatherHasNoVariants() {
        val plain = ThemeManifestCodec.parse(edited { it.remove("weather") })
        assertTrue(plain.weather.isEmpty())
        assertTrue(ThemeOrder.info(plain, bundled = false).moods.isEmpty())
    }

    @Test fun accentAndGlowCannotChangeInAVariant() {
        for (key in listOf("accent", "glow")) {
            val json = variantEdited(0) { it.getJSONObject("palette").put(key, "#FF00FF00") }
            assertEquals(ThemeProblem.BadValue("weather.snow.palette.$key"), problem(json))
        }
        val unknown = variantEdited(0) { it.getJSONObject("palette").put("sparkle", "#FF00FF00") }
        assertEquals(ThemeProblem.BadValue("weather.snow.palette.sparkle"), problem(unknown))
        val badColor = variantEdited(0) { it.getJSONObject("palette").put("ink", "white") }
        assertEquals(ThemeProblem.BadValue("weather.snow.palette.ink"), problem(badColor))
    }

    @Test fun variantIdsAreShortLowercaseAndUnique() {
        for (bad in listOf("", "Snow", "snow day", "a".repeat(33), "snow_day")) {
            assertEquals(bad, ThemeProblem.BadValue("weather[0].id"), problem(variantEdited(0) { it.put("id", bad) }))
        }
        assertEquals(ThemeProblem.BadValue("weather[1].id"), problem(variantEdited(1) { it.put("id", "snow") }))
        assertEquals(ThemeProblem.MissingField("weather[2].id"), problem(variantEdited(2) { it.remove("id") }))
        // 1 to 32 characters.
        ThemeManifestCodec.parse(variantEdited(0) { it.put("id", "a") })
        ThemeManifestCodec.parse(variantEdited(0) { it.put("id", "a".repeat(32)) })
    }

    @Test fun whenPartsAreNonEmptyListsOfKnownNames() {
        val cases = listOf(
            "sky" to JSONArray(), "sky" to JSONArray(listOf("sunny")), "sky" to "snow",
            "temp" to JSONArray(listOf("chilly")), "forecast" to JSONArray(listOf("hail")), "forecast" to JSONArray(),
        )
        for ((key, value) in cases) {
            val json = variantEdited(0) { it.getJSONObject("when").put(key, value) }
            assertEquals("$key $value", ThemeProblem.BadValue("weather.snow.when.$key"), problem(json))
        }
        val noWhen = ThemeManifestCodec.parse(variantEdited(0) { it.remove("when") })
        assertEquals(MoodRule(), noWhen.weather[0].rule)
    }

    @Test fun replaceHideAndAfterNameBaseLayers() {
        assertEquals(
            ThemeProblem.BadValue("weather.snow.replace"),
            problem(variantEdited(0) { it.put("replace", JSONObject().put("nope.webp", "x.webp")) }),
        )
        assertEquals(
            ThemeProblem.BadValue("weather.snow.replace"),
            problem(variantEdited(0) { it.put("replace", JSONObject().put("sky.webp", "../x.webp")) }),
        )
        assertEquals(
            ThemeProblem.BadValue("weather.snow.hide"),
            problem(variantEdited(0) { it.put("hide", JSONArray(listOf("nope.webp"))) }),
        )
        assertEquals(
            "a layer cannot be both hidden and replaced",
            ThemeProblem.BadValue("weather.snow.hide"),
            problem(variantEdited(0) { it.put("hide", JSONArray(listOf("sky.webp"))) }),
        )
        assertEquals(
            ThemeProblem.BadValue("weather.snow.add[0].after"),
            problem(variantEdited(0) { it.getJSONArray("add").getJSONObject(0).put("after", "snowfall.webp") }),
        )
    }

    @Test fun addedLayersAreCheckedLikeAnyLayer() {
        assertEquals(
            ThemeProblem.BadValue("weather.snow.add[1].fall.y"),
            problem(variantEdited(0) { it.getJSONArray("add").getJSONObject(1).put("fall", JSONObject("""{"y":0,"period":60}""")) }),
        )
        assertEquals(
            ThemeProblem.BadValue("weather.snow.add[1].fall.period"),
            problem(variantEdited(0) { it.getJSONArray("add").getJSONObject(1).put("fall", JSONObject("""{"y":10,"period":0}""")) }),
        )
        assertEquals(
            ThemeProblem.MissingField("weather.snow.add[0].image"),
            problem(variantEdited(0) { it.getJSONArray("add").getJSONObject(0).remove("image") }),
        )
    }

    @Test fun fallWorksOnBaseLayersToo() {
        val m = ThemeManifestCodec.parse(edited { it.getJSONArray("layers").getJSONObject(1).put("fall", JSONObject("""{"y":20,"period":90}""")) })
        assertEquals(Fall(20f, 90f), m.layers[1].fall)
        assertEquals(ThemeProblem.BadValue("layers[1].fall"), problem(edited { it.getJSONArray("layers").getJSONObject(1).put("fall", 3) }))
    }

    @Test fun weatherMustBeAnArrayOfObjectsWithinTheLimit() {
        assertEquals(ThemeProblem.BadValue("weather"), problem(edited { it.put("weather", JSONObject()) }))
        assertEquals(ThemeProblem.BadValue("weather[0]"), problem(edited { it.put("weather", JSONArray(listOf("snow"))) }))
        val many = JSONArray((0..ThemeManifestCodec.MAX_VARIANTS).map { JSONObject().put("id", "v$it") })
        assertEquals(ThemeProblem.BadValue("weather"), problem(edited { it.put("weather", many) }))
    }

    // endregion

    // region validation

    @Test fun theFixtureIsValid() {
        assertNull(check(fixture)?.message, check(fixture))
    }

    @Test fun aMissingOrTooWideVariantImageNamesTheVariant() {
        assertEquals(
            ThemeProblem.BadVariant("rain", ThemeProblem.MissingImage("rain.webp")),
            check(fixture, sizes - "rain.webp"),
        )
        assertEquals(
            ThemeProblem.BadVariant("hot", ThemeProblem.ImageTooWide("heat-haze.webp", 2000)),
            check(fixture, sizes + ("heat-haze.webp" to ImageBounds(2000, 200))),
        )
        assertEquals(
            ThemeProblem.BadVariant("snow", ThemeProblem.UnreadableImage("snowfall.webp")),
            ThemeValidation.check(fixture, exists = { it in sizes }, bounds = { if (it == "snowfall.webp") null else sizes[it] }),
        )
    }

    @Test fun aReplacedBaseMustStillBeFullSize() {
        assertEquals(
            ThemeProblem.BadVariant("snow", ThemeProblem.BaseLayerSize("sky-snow.webp", 960, 540)),
            check(fixture, sizes + ("sky-snow.webp" to ImageBounds(960, 540))),
        )
    }

    @Test fun atMostOneReplacementIsFullSize() {
        val json = variantEdited(0) { it.put("replace", JSONObject().put("sky.webp", "sky-snow.webp").put("valley.webp", "valley-snow.webp")) }
        val m = ThemeManifestCodec.parse(json)
        assertEquals(
            ThemeProblem.BadVariant("snow", ThemeProblem.FullSizeReplacements(2)),
            check(m, sizes + ("valley-snow.webp" to ImageBounds(1920, 520))),
        )
        assertNull(check(m, sizes + ("valley-snow.webp" to ImageBounds(960, 260))))
    }

    @Test fun eachResolvedSetHasItsOwnLayerCountAndBudget() {
        // Ten resolved layers are fine for a variant (the base allows eight); eleven are not.
        fun adding(n: Int) = ThemeManifestCodec.parse(
            variantEdited(4) { v -> v.put("add", JSONArray((1..n).map { JSONObject().put("image", "heat-haze.webp") })) },
        )
        assertNull(check(adding(7)))
        assertEquals(ThemeProblem.BadVariant("hot", ThemeProblem.LayerCount(11, 10)), check(adding(8)))
        // Hiding everything leaves nothing to draw.
        val empty = ThemeManifestCodec.parse(
            variantEdited(3) { v ->
                v.remove("replace")
                v.put("hide", JSONArray(listOf("sky.webp", "haze.webp", "valley.webp")))
            },
        )
        assertEquals(ThemeProblem.BadVariant("overcast", ThemeProblem.LayerCount(0, 10)), check(empty))
        // The base alone is within budget; the variant's own set is over it.
        val heavy = sizes + ("valley.webp" to ImageBounds(1920, 1080)) +
            ("snowfall.webp" to ImageBounds(1920, 1080)) + ("snow-ground.webp" to ImageBounds(1920, 1080))
        assertNull(check(fixture.copy(weather = emptyList()), heavy))
        val problem = check(fixture, heavy)
        assertTrue(problem.toString(), problem is ThemeProblem.BadVariant && problem.variant == "snow" && problem.problem is ThemeProblem.TooLarge)
    }

    @Test fun aVariantsBudgetCountsOnlyWhatItDraws() {
        // Hidden and replaced images are not decoded for the variant, so a pack whose base is near
        // the budget can still swap in a full-size sky.
        val nearBudget = sizes + ("valley.webp" to ImageBounds(1920, 1080)) + ("haze.webp" to ImageBounds(1920, 1080))
        val m = ThemeManifestCodec.parse(variantEdited(0) { it.put("add", JSONArray()) })
        assertNull(check(m, nearBudget))
    }

    // endregion

    // region matching

    private val rules = fixture.weather.map { it.matchRule }
    private fun pick(sky: SceneSky, temp: SceneTemp, forecast: SceneForecast? = null) =
        ThemeVariants.pick(rules, SceneMood(sky, temp, forecast))

    @Test fun theFirstMatchingEntryWins() {
        assertEquals("snow", pick(SceneSky.Snow, SceneTemp.Freezing))
        assertEquals("snow-coming", pick(SceneSky.Cloudy, SceneTemp.Cold, SceneForecast.Snow))
        assertEquals("rain", pick(SceneSky.Rain, SceneTemp.Warm))
        assertEquals("overcast", pick(SceneSky.Cloudy, SceneTemp.Mild))
        assertEquals("hot", pick(SceneSky.Clear, SceneTemp.Hot))
        assertNull(pick(SceneSky.Clear, SceneTemp.Mild))
        assertNull(ThemeVariants.pick(rules, null))
        assertNull(ThemeVariants.pick(emptyList(), SceneMood(SceneSky.Snow, SceneTemp.Cold)))
    }

    @Test fun withNoMatchTheSkyWidensAndTheRestStays() {
        assertEquals(listOf(SceneSky.Storm, SceneSky.Rain, SceneSky.Cloudy), ThemeVariants.widenedSkies(SceneSky.Storm))
        assertEquals(listOf(SceneSky.Fog, SceneSky.Cloudy), ThemeVariants.widenedSkies(SceneSky.Fog))
        assertEquals(listOf(SceneSky.Snow, SceneSky.Cloudy), ThemeVariants.widenedSkies(SceneSky.Snow))
        assertEquals(listOf(SceneSky.Clear), ThemeVariants.widenedSkies(SceneSky.Clear))
        assertEquals(listOf(SceneSky.Cloudy), ThemeVariants.widenedSkies(SceneSky.Cloudy))
        assertEquals("rain", pick(SceneSky.Storm, SceneTemp.Mild))
        assertEquals("overcast", pick(SceneSky.Fog, SceneTemp.Mild))
        // Snow while mild: the snow entry wants freezing or cold, so the sky widens to cloudy.
        assertEquals("overcast", pick(SceneSky.Snow, SceneTemp.Mild))
        // The forecast is kept while widening: fog with snow coming finds snow-coming.
        assertEquals("snow-coming", pick(SceneSky.Fog, SceneTemp.Cold, SceneForecast.Snow))
        // Without a storm or rain entry, a storm reaches cloudy.
        val noRain = rules.filterNot { it.id == "rain" }
        assertEquals("overcast", ThemeVariants.pick(noRain, SceneMood(SceneSky.Storm, SceneTemp.Mild)))
        // Clear never widens.
        assertNull(ThemeVariants.pick(rules.filter { it.id == "overcast" }, SceneMood(SceneSky.Clear, SceneTemp.Mild)))
    }

    @Test fun theMoodSetListsExactMatchesOnly() {
        val info = ThemeOrder.info(fixture, bundled = false)
        assertTrue(SceneMood(SceneSky.Snow, SceneTemp.Cold) in info.moods)
        assertTrue(SceneMood(SceneSky.Rain, SceneTemp.Hot, SceneForecast.Snow) in info.moods)
        assertFalse("storm only matches after widening", SceneMood(SceneSky.Storm, SceneTemp.Mild) in info.moods)
        assertFalse(SceneMood(SceneSky.Clear, SceneTemp.Mild) in info.moods)
        assertEquals("rain", info.variantFor(SceneMood(SceneSky.Storm, SceneTemp.Mild)))
        assertEquals(SceneMood.all.filter { m -> rules.any { it.rule.matches(m) } }.toSet(), info.moods)
    }

    // endregion

    // region resolution

    private fun images(id: String?) = ThemeVariants.layers(fixture, ThemeVariants.variant(fixture, id)).map { it.image }

    @Test fun resolvedLayersReplaceHideAndInsert() {
        assertEquals(listOf("sky.webp", "haze.webp", "valley.webp"), images(null))
        assertEquals(listOf("sky-snow.webp", "valley.webp", "snow-ground.webp", "snowfall.webp"), images("snow"))
        assertEquals(listOf("sky.webp", "snow-clouds.webp", "haze.webp", "valley.webp"), images("snow-coming"))
        assertEquals(listOf("sky-overcast.webp", "haze.webp", "valley.webp", "rain.webp"), images("rain"))
        assertEquals(listOf("sky.webp", "haze.webp", "valley.webp", "heat-haze.webp"), images("hot"))
        assertEquals("an unknown id is the plain scene", images(null), images("hail"))
    }

    @Test fun aReplacementKeepsPlacementUnlessAFullLayerIsGiven() {
        val rain = ThemeVariants.layers(fixture, ThemeVariants.variant(fixture, "rain"))
        assertEquals(fixture.layers[0].copy(image = "sky-overcast.webp"), rain[0])
        val m = ThemeManifestCodec.parse(
            edited { root ->
                val layers = root.getJSONArray("layers")
                val v = root.getJSONArray("weather").getJSONObject(3)
                v.put("replace", JSONObject().put("valley.webp", "valley-wet.webp"))
                v.put("add", JSONArray().put(JSONObject("""{"image":"valley-wet.webp","y":290,"parallax":0.4}""")))
                assertEquals(3, layers.length())
            },
        )
        val resolved = ThemeVariants.layers(m, ThemeVariants.variant(m, "overcast"))
        assertEquals(listOf("sky.webp", "haze.webp", "valley-wet.webp"), resolved.map { it.image })
        assertEquals(290f, resolved[2].yDp)
        assertEquals(0.4f, resolved[2].parallax)
    }

    @Test fun aVariantPaletteChangesOnlyWhatItNames() {
        val base = fixture.palette
        val snow = ThemeVariants.palette(fixture, ThemeVariants.variant(fixture, "snow"))
        assertEquals(base.copy(window = 0xFF0C2236.toInt()), snow)
        val rain = ThemeVariants.palette(fixture, ThemeVariants.variant(fixture, "rain"))
        assertEquals(base.copy(ink = 0xFFF8F6F2.toInt(), shade = 0xE0102030.toInt()), rain)
        assertEquals(base.accent, rain.accent)
        assertEquals(base.glow, rain.glow)
        assertEquals(base, ThemeVariants.palette(fixture, null))
    }

    // endregion
}
