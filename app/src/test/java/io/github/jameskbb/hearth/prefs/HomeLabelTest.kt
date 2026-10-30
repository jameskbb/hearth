package io.github.jameskbb.hearth.prefs

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLabelTest {
    @Test fun defaultsAndBlankNeverShowAnything() {
        assertFalse(HearthPrefs().homeLabelEnabled)
        assertNull(HomeLabel.visible(HearthPrefs()))
        assertNull(HomeLabel.visible(HearthPrefs(homeLabel = "My Workshop")))
        assertNull(HomeLabel.visible(HearthPrefs(homeLabelEnabled = true, homeLabel = " \n\t ")))
        assertEquals("Game Room", HomeLabel.visible(HearthPrefs(homeLabelEnabled = true, homeLabel = " Game Room ")))
    }

    @Test fun textIsTrimmedAndControlAndDirectionCharactersAreSanitized() {
        assertEquals("Welcome Home", HomeLabel.clean("  Welcome\nHome\u0000  "))
        assertEquals("Game Room", HomeLabel.clean("Game\u202eRoom"))
        assertEquals("x".repeat(40), HomeLabel.clean("x".repeat(60)))
        val glyph = String(Character.toChars(0x1f3e0))
        assertEquals(glyph.repeat(40), HomeLabel.clean(glyph.repeat(41)))
        assertEquals("", HomeLabel.clean("\uD800"))
    }

    @Test fun codecSanitizesOnBothReadAndWriteAndRetainsDisabledText() {
        val stored = mutablePreferencesOf(PrefsCodec.HOME_LABEL to " My\nWorkshop ", PrefsCodec.HOME_LABEL_ENABLED to true)
        assertEquals("My Workshop", PrefsCodec.read(stored).homeLabel)
        PrefsCodec.write(stored, HearthPrefs(homeLabel = " Game\tRoom "))
        val read = PrefsCodec.read(stored)
        assertEquals("Game Room", read.homeLabel)
        assertFalse(read.homeLabelEnabled)
        assertNull(HomeLabel.visible(read))
    }
}
