package io.github.jameskbb.hearth.ui.state

import io.github.jameskbb.hearth.apps.AppEntry
import io.github.jameskbb.hearth.apps.AppKey
import io.github.jameskbb.hearth.apps.EntryKind
import io.github.jameskbb.hearth.prefs.HearthPrefs
import io.github.jameskbb.hearth.system.DisplayFacts
import io.github.jameskbb.hearth.system.TvInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiLogicTest {
    private val a = AppKey("a/A")
    private val b = AppKey("b/B")
    private val c = AppKey("c/C")
    private val d = AppKey("d/D")

    private fun entry(key: AppKey, tv: Boolean = true) = AppEntry(key, key.packageName, tv, 0L)

    @Test
    fun visibleFavoritesKeepsOrderAndDropsMissingAndHidden() {
        val prefs = HearthPrefs(favorites = listOf(c, a, d, b), hidden = setOf(b))
        val apps = listOf(entry(a), entry(b), entry(c))
        assertEquals(listOf(c, a), UiLogic.visibleFavorites(prefs, apps).map { it.key })
    }

    @Test
    fun allAppsSectionsSplitTvFirstAndExcludeHidden() {
        val apps = listOf(entry(a), entry(b, tv = false), entry(c), entry(d, tv = false))
        val sections = UiLogic.allAppsSections(apps, setOf(c))
        assertEquals(emptyList<AppEntry>(), sections.inputs)
        assertEquals(listOf(a), sections.tvApps.map { it.key })
        assertEquals(listOf(b, d), sections.otherApps.map { it.key })
    }

    private val hdmi1 = TvInput("tv.example/.HdmiService/HW1", "HDMI 1", 1)
    private val hdmi2 = TvInput("tv.example/.HdmiService/HW2", "HDMI 2", 2)

    @Test
    fun inputEntriesUseTheUsersNameOverTheTvsAndKeepOrder() {
        val entries = UiLogic.inputEntries(listOf(hdmi1, hdmi2), mapOf(hdmi2.id to "Game console", "gone" to "Old"))
        assertEquals(listOf(AppKey.forInput(hdmi1.id), AppKey.forInput(hdmi2.id)), entries.map { it.key })
        assertEquals(listOf("HDMI 1", "Game console"), entries.map { it.label })
        assertEquals(listOf(EntryKind.Input, EntryKind.Input), entries.map { it.kind })
        assertEquals(hdmi1.id, entries[0].key.inputId)
    }

    @Test
    fun allAppsSectionsPutInputsFirstAndHideHiddenOnes() {
        val inputs = UiLogic.inputEntries(listOf(hdmi1, hdmi2), emptyMap())
        val sections = UiLogic.allAppsSections(listOf(entry(a), entry(b, tv = false)), setOf(inputs[1].key), inputs)
        assertEquals(listOf(inputs[0]), sections.inputs)
        assertEquals(listOf(a), sections.tvApps.map { it.key })
        assertEquals(listOf(b), sections.otherApps.map { it.key })
    }

    @Test
    fun visibleFavoritesResolveInputsAndSkipMissingOnes() {
        val inputs = UiLogic.inputEntries(listOf(hdmi1), emptyMap())
        val gone = AppKey.forInput(hdmi2.id)
        val prefs = HearthPrefs(favorites = listOf(gone, a, inputs[0].key))
        assertEquals(listOf(a, inputs[0].key), UiLogic.visibleFavorites(prefs, listOf(entry(a)), inputs).map { it.key })
        assertEquals(listOf(a), UiLogic.visibleFavorites(prefs, listOf(entry(a))).map { it.key })
    }

    @Test
    fun hiddenLabelsFallBackToTheUsersNameForAGoneInput() {
        val key = AppKey.forInput(hdmi1.id)
        assertEquals("Console", UiLogic.hiddenLabel(key, null, mapOf(hdmi1.id to "Console")))
        assertEquals(hdmi1.id, UiLogic.hiddenLabel(key, null, emptyMap()))
        assertEquals("a", UiLogic.hiddenLabel(a, null, emptyMap()))
        assertEquals("Cinema", UiLogic.hiddenLabel(a, entry(a).copy(label = "Cinema"), emptyMap()))
    }

    @Test
    fun seedCandidatesAreTvAppsOnly() {
        assertEquals(listOf(a, c), UiLogic.seedCandidates(listOf(entry(a), entry(b, tv = false), entry(c))))
        val input = AppEntry(AppKey.forInput(hdmi1.id), "HDMI 1", isTvApp = true, lastUpdateTime = 0L, kind = EntryKind.Input)
        assertEquals(listOf(a), UiLogic.seedCandidates(listOf(entry(a), input)))
    }

    @Test
    fun neighborPrefersNextThenPreviousThenNone() {
        assertEquals(c, UiLogic.neighborAfterRemoval(listOf(a, b, c), listOf(a, c), b))
        assertEquals(b, UiLogic.neighborAfterRemoval(listOf(a, b, c), listOf(a, b), c))
        assertNull(UiLogic.neighborAfterRemoval(listOf(a), emptyList(), a))
        // Skips items that also disappeared.
        assertEquals(d, UiLogic.neighborAfterRemoval(listOf(a, b, c, d), listOf(a, d), b))
    }

    @Test
    fun arrangeDeltaStepsOverInvisibleFavorites() {
        val full = listOf(a, b, c, d)
        val visible = setOf(a, c, d)
        assertEquals(2, UiLogic.arrangeDelta(full, { it in visible }, a, 1))
        assertEquals(-2, UiLogic.arrangeDelta(full, { it in visible }, c, -1))
        assertEquals(1, UiLogic.arrangeDelta(full, { it in visible }, c, 1))
        assertEquals(0, UiLogic.arrangeDelta(full, { it in visible }, d, 1))
        assertEquals(0, UiLogic.arrangeDelta(full, { it in visible }, a, -1))
    }

    @Test
    fun homeFocusFallsBackToFirstFavoriteThenAllApps() {
        val actions = listOf(HomeAction.AllApps, HomeAction.Settings)
        assertEquals(FocusTarget.Tile(b), UiLogic.resolveHomeFocus(FocusTarget.Tile(b), listOf(a, b), actions))
        assertEquals(FocusTarget.Tile(a), UiLogic.resolveHomeFocus(FocusTarget.Tile(c), listOf(a, b), actions))
        assertEquals(FocusTarget.Tile(a), UiLogic.resolveHomeFocus(FocusTarget.Default, listOf(a, b), actions))
        assertEquals(
            FocusTarget.Action(HomeAction.AllApps),
            UiLogic.resolveHomeFocus(FocusTarget.Default, emptyList(), actions),
        )
        // An action that is not shown (Arrange with one favorite) falls back too.
        assertEquals(FocusTarget.Tile(a), UiLogic.resolveHomeFocus(FocusTarget.Action(HomeAction.Arrange), listOf(a), actions))
    }

    @Test
    fun scrollDistanceKeepsLeadAndTrail() {
        // Already in view.
        assertEquals(0f, UiLogic.scrollDistanceToReveal(48f, 184f, 960f, 48f, 48f))
        // Past the right edge: scroll forward just enough.
        assertEquals(100f, UiLogic.scrollDistanceToReveal(828f, 184f, 960f, 48f, 48f))
        // Too close to the left: scroll back to the lead.
        assertEquals(-38f, UiLogic.scrollDistanceToReveal(10f, 184f, 960f, 48f, 48f))
    }

    @Test
    fun formatsDisplayFactsPlainly() {
        assertEquals("60 Hz", UiLogic.formatHz(60f))
        assertEquals("59.94 Hz", UiLogic.formatHz(59.94f))
        assertEquals("23.98 Hz", UiLogic.formatHz(23.976f))
        assertEquals("50.5 Hz", UiLogic.formatHz(50.5f))
        val lines = UiLogic.displayFactLines(
            DisplayFacts(1920, 1080, 320, 60f, emptyList(), true, listOf("HDR10", "HLG")),
            yes = "Yes", no = "No", noneReported = "None reported",
        ).toMap()
        assertEquals("1920 x 1080, 320 dpi", lines[UiLogic.DisplayFactLabel.Resolution])
        assertEquals("Yes", lines[UiLogic.DisplayFactLabel.WideColor])
        assertEquals("HDR10, HLG", lines[UiLogic.DisplayFactLabel.Hdr])
        assertNull(lines[UiLogic.DisplayFactLabel.Modes])
    }

    @Test
    fun stripsDayPeriodFromTwelveHourPatterns() {
        assertEquals("h:mm", UiLogic.stripDayPeriod("h:mm a"))
        assertEquals("h:mm", UiLogic.stripDayPeriod("a h:mm"))
        assertEquals("h:mm", UiLogic.stripDayPeriod("h:mm a"))
        assertEquals("H:mm", UiLogic.stripDayPeriod("H:mm"))
        assertEquals("h 'h' mm", UiLogic.stripDayPeriod("h 'h' mm a"))
    }
}
