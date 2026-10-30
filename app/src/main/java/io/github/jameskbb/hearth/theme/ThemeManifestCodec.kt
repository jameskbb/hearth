package io.github.jameskbb.hearth.theme

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads `theme.json` as docs/theme-packs.md specifies it. Pure (org.json only), so it runs in
 * JVM unit tests. Every value is range-checked here; whether the named images exist and fit
 * the memory budget is [ThemeValidation]'s job. Unknown fields are ignored, so a pack can
 * carry notes of its own.
 */
object ThemeManifestCodec {
    const val FORMAT = 1
    const val MAX_LAYERS = 8
    const val MAX_NAME_LENGTH = 40

    private const val DEFAULT_INK = 0xFFF4F1EC.toInt()
    private const val ALPHA_INK_MUTED = 0xB8
    private const val ALPHA_GLOW = 0x66
    private const val ALPHA_SHADE = 0xE0
    private const val ALPHA_SHADE_MID = 0x73

    /** Parses [json]. Throws [ThemeException] with the first problem found. */
    fun parse(json: String): ThemeManifest {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw ThemeException(ThemeProblem.MalformedManifest)
        }
        // Format first: a newer manifest may be shaped differently, and should say so.
        if (!root.has("format")) fail(ThemeProblem.MissingField("format"))
        val format = root.int("format")
        if (format > FORMAT) fail(ThemeProblem.NeedsNewerHearth(format))
        if (format < FORMAT) bad("format")

        val idText = root.requiredString("id")
        if (!ThemeId.isValidPackId(idText)) fail(ThemeProblem.BadId(idText))

        val name = root.requiredString("name").trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) bad("name")

        return ThemeManifest(
            format = format,
            id = ThemeId(idText),
            name = name,
            author = root.optionalText("author"),
            description = root.optionalText("description"),
            dayparts = dayparts(root),
            palette = palette(root),
            preview = root.optionalText("preview")?.also { if (!isSafePath(it)) bad("preview") },
            sway = sway(root),
            layers = layers(root),
            autoRotationEligible = if (root.has("autoRotationEligible")) {
                root.opt("autoRotationEligible") as? Boolean ?: bad("autoRotationEligible")
            } else true,
        )
    }

    /** A path inside the pack: relative, forward slashes, no `..`, no empty segments. */
    fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.contains('\\') || path.contains(':')) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun dayparts(root: JSONObject): Set<Daypart> {
        if (!root.has("dayparts")) return emptySet()
        val array = root.opt("dayparts") as? JSONArray ?: bad("dayparts")
        return (0 until array.length()).mapTo(LinkedHashSet()) { i ->
            val s = array.opt(i) as? String ?: bad("dayparts")
            Daypart.fromManifest(s) ?: bad("dayparts")
        }
    }

    private fun palette(root: JSONObject): PaletteSpec {
        if (!root.has("palette")) fail(ThemeProblem.MissingField("palette"))
        val p = root.opt("palette") as? JSONObject ?: bad("palette")
        fun color(key: String): Int? {
            if (!p.has(key)) return null
            val s = p.opt(key) as? String ?: bad("palette.$key")
            return parseColor(s) ?: bad("palette.$key")
        }
        fun required(key: String): Int = color(key) ?: fail(ThemeProblem.MissingField("palette.$key"))
        val window = required("window")
        val accent = required("accent")
        val ink = color("ink") ?: DEFAULT_INK
        return PaletteSpec(
            window = window,
            accent = accent,
            ink = ink,
            inkMuted = color("inkMuted") ?: withAlpha(ink, ALPHA_INK_MUTED),
            glow = color("glow") ?: withAlpha(accent, ALPHA_GLOW),
            shade = color("shade") ?: withAlpha(window, ALPHA_SHADE),
            shadeMid = color("shadeMid") ?: withAlpha(window, ALPHA_SHADE_MID),
        )
    }

    /** `#AARRGGBB` or `#RRGGBB` (opaque), any case. */
    fun parseColor(s: String): Int? {
        val hex = s.trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val value = hex.toLong(16)
        return if (hex.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }

    private fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha shl 24)

    private fun sway(root: JSONObject): Sway {
        if (!root.has("motion")) return Sway()
        val motion = root.opt("motion") as? JSONObject ?: bad("motion")
        if (!motion.has("sway")) return Sway()
        val s = motion.opt("sway") as? JSONObject ?: bad("motion.sway")
        val amplitude = s.number("amplitude", "motion.sway.amplitude", default = 0f)
        if (amplitude < 0f) bad("motion.sway.amplitude")
        val period = s.number("period", "motion.sway.period", default = Sway().periodSeconds)
        if (period <= 0f) bad("motion.sway.period")
        return Sway(amplitude, period)
    }

    private fun layers(root: JSONObject): List<LayerSpec> {
        if (!root.has("layers")) fail(ThemeProblem.MissingField("layers"))
        val array = root.opt("layers") as? JSONArray ?: bad("layers")
        val n = array.length()
        if (n < 1 || n > MAX_LAYERS) fail(ThemeProblem.LayerCount(n))
        return (0 until n).map { i -> layer(array.opt(i) as? JSONObject ?: bad("layers[$i]"), "layers[$i]") }
    }

    private fun layer(o: JSONObject, at: String): LayerSpec {
        if (!o.has("image")) fail(ThemeProblem.MissingField("$at.image"))
        val image = o.opt("image") as? String ?: bad("$at.image")
        if (!isSafePath(image)) bad("$at.image")
        val y = o.number("y", "$at.y", default = 0f)
        if (y < -540f || y > 540f) bad("$at.y")
        val parallax = o.number("parallax", "$at.parallax", default = 0f).unit("$at.parallax")
        val wrap = if (o.has("wrap")) o.opt("wrap") as? Boolean ?: bad("$at.wrap") else false
        val blend = when (if (o.has("blend")) o.opt("blend") else "normal") {
            "normal" -> LayerBlend.Normal
            "screen" -> LayerBlend.Screen
            else -> bad("$at.blend")
        }
        return LayerSpec(
            image = image,
            yDp = y,
            parallax = parallax,
            drift = o.child("drift", at)?.let { d ->
                Drift(
                    xDp = d.number("x", "$at.drift.x"),
                    periodSeconds = d.number("period", "$at.drift.period").positive("$at.drift.period"),
                )
            },
            wrap = wrap,
            opacity = o.child("opacity", at)?.let { b ->
                val min = b.number("min", "$at.opacity.min").unit("$at.opacity.min")
                val max = b.number("max", "$at.opacity.max").unit("$at.opacity.max")
                if (min > max) bad("$at.opacity.min")
                Breath(
                    min = min,
                    max = max,
                    periodSeconds = b.number("period", "$at.opacity.period").positive("$at.opacity.period"),
                    phase = b.number("phase", "$at.opacity.phase", default = 0f).unit("$at.opacity.phase"),
                )
            },
            fade = o.child("fade", at)?.let { f ->
                val delay = f.number("delay", "$at.fade.delay", default = 0f)
                if (delay < 0f) bad("$at.fade.delay")
                val duration = f.number("duration", "$at.fade.duration")
                if (duration < 0f) bad("$at.fade.duration")
                Fade(
                    from = f.number("from", "$at.fade.from").unit("$at.fade.from"),
                    to = f.number("to", "$at.fade.to").unit("$at.fade.to"),
                    delaySeconds = delay,
                    durationSeconds = duration,
                )
            },
            blend = blend,
        )
    }

    // region helpers

    private fun fail(problem: ThemeProblem): Nothing = throw ThemeException(problem)

    private fun bad(field: String): Nothing = fail(ThemeProblem.BadValue(field))

    private fun JSONObject.child(key: String, at: String): JSONObject? {
        if (!has(key)) return null
        return opt(key) as? JSONObject ?: bad("$at.$key")
    }

    private fun JSONObject.requiredString(key: String): String {
        if (!has(key)) fail(ThemeProblem.MissingField(key))
        return opt(key) as? String ?: bad(key)
    }

    private fun JSONObject.optionalText(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val s = opt(key) as? String ?: bad(key)
        return s.trim().ifEmpty { null }
    }

    private fun JSONObject.int(key: String): Int {
        val n = opt(key) as? Number ?: bad(key)
        val d = n.toDouble()
        if (d != Math.floor(d) || d > Int.MAX_VALUE || d < Int.MIN_VALUE) bad(key)
        return d.toInt()
    }

    /** A finite number at [key]; [field] names it in errors. Required unless [default] is given. */
    private fun JSONObject.number(key: String, field: String, default: Float? = null): Float {
        if (!has(key)) return default ?: fail(ThemeProblem.MissingField(field))
        val n = opt(key) as? Number ?: bad(field)
        val f = n.toFloat()
        if (f.isNaN() || f.isInfinite()) bad(field)
        return f
    }

    private fun Float.unit(field: String): Float = if (this in 0f..1f) this else bad(field)

    private fun Float.positive(field: String): Float = if (this > 0f) this else bad(field)

    // endregion
}
