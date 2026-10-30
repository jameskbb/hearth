package io.github.jameskbb.hearth.prefs

/** One-line local display text. Count code points so a supplementary glyph is never split. */
object HomeLabel {
    const val MAX_LENGTH = 40

    fun sanitize(raw: String): String = buildString {
        var offset = 0
        var count = 0
        while (offset < raw.length && count < MAX_LENGTH) {
            val point = Character.codePointAt(raw, offset)
            val type = Character.getType(point)
            val forbidden = Character.isISOControl(point) || type == Character.FORMAT.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                type == Character.SURROGATE.toInt()
            append(if (forbidden) " " else String(Character.toChars(point)))
            offset += Character.charCount(point)
            count++
        }
    }

    fun clean(raw: String): String = sanitize(raw.trim()).trim()

    fun visible(prefs: HearthPrefs?): String? =
        prefs?.takeIf { it.homeLabelEnabled }?.homeLabel?.let(::clean)?.takeIf { it.isNotEmpty() }
}
