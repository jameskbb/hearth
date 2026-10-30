package io.github.jameskbb.hearth.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipThemeSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val f = tmp.newFile("pack-${entries.size}-${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return f
    }

    private fun problemOf(f: File): ThemeProblem = try {
        ZipThemeSource(f)
        fail("expected a ThemeException")
        throw AssertionError()
    } catch (e: ThemeException) {
        e.problem
    }

    @Test fun manifestAtTheRoot() {
        val src = ZipThemeSource(zip("theme.json" to "{}".toByteArray(), "sky.webp" to byteArrayOf(1, 2, 3)))
        assertEquals("{}", src.readManifest())
        assertTrue(src.exists("sky.webp"))
        assertEquals(listOf<Byte>(1, 2, 3), src.open("sky.webp")!!.use { it.readBytes() }.toList())
        assertFalse(src.exists("other.webp"))
        assertNull(src.open("other.webp"))
    }

    @Test fun manifestInsideOneTopLevelFolder() {
        val src = ZipThemeSource(
            zip("misty/theme.json" to "{\"a\":1}".toByteArray(), "misty/art/sky.webp" to byteArrayOf(9)),
        )
        assertEquals("{\"a\":1}", src.readManifest())
        assertTrue(src.exists("art/sky.webp"))
        assertFalse(src.exists("misty/art/sky.webp"))
    }

    @Test fun noManifestIsReported() {
        assertEquals(ThemeProblem.MissingManifest, problemOf(zip("sky.webp" to byteArrayOf(1))))
        assertEquals(ThemeProblem.MissingManifest, problemOf(zip("a/b/theme.json" to byteArrayOf(1))))
    }

    @Test fun twoCandidateFoldersAreAmbiguous() {
        assertEquals(
            ThemeProblem.MissingManifest,
            problemOf(zip("a/theme.json" to byteArrayOf(1), "b/theme.json" to byteArrayOf(1))),
        )
    }

    @Test fun entriesThatLeaveThePackAreNeverServed() {
        val src = ZipThemeSource(zip("theme.json" to "{}".toByteArray(), "../evil.webp" to byteArrayOf(1)))
        assertFalse(src.exists("../evil.webp"))
        assertNull(src.open("../evil.webp"))
    }

    @Test fun aByteOrderMarkBeforeTheManifestIsDropped() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val src = ZipThemeSource(zip("theme.json" to bom + "{}".toByteArray()))
        assertEquals("{}", src.readManifest())
    }

    @Test fun anOversizedManifestIsRefused() {
        val src = ZipThemeSource(zip("theme.json" to ByteArray(ThemeSource.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }))
        try {
            src.readManifest()
            fail("expected a ThemeException")
        } catch (e: ThemeException) {
            assertEquals(ThemeProblem.BadValue("theme.json"), e.problem)
        }
    }

    @Test fun notAZipIsUnreadable() {
        val f = tmp.newFile("broken.zip").apply { writeText("this is not a zip") }
        assertEquals(ThemeProblem.UnreadableArchive, problemOf(f))
    }

    @Test fun oversizedEntryIsRejected() {
        val big = ByteArray((ZipThemeSource.MAX_ENTRY_BYTES + 1).toInt())
        assertEquals(ThemeProblem.ArchiveTooLarge, problemOf(zip("theme.json" to "{}".toByteArray(), "sky.webp" to big)))
    }

    @Test fun oversizedTotalIsRejected() {
        val chunk = ByteArray(20 * 1024 * 1024)
        val f = zip(
            "theme.json" to "{}".toByteArray(),
            "a.webp" to chunk, "b.webp" to chunk, "c.webp" to chunk, "d.webp" to chunk,
        )
        assertEquals(ThemeProblem.ArchiveTooLarge, problemOf(f))
    }

    @Test fun directorySourceRefusesPathsOutsideIt() {
        val dir = tmp.newFolder("pack")
        File(dir, "theme.json").writeText("{}")
        tmp.newFile("secret.txt").writeText("no")
        val src = DirectoryThemeSource(dir)
        assertEquals("{}", src.readManifest())
        assertNull(src.open("../secret.txt"))
        assertFalse(src.exists("../secret.txt"))
        assertEquals("pack", src.label)
    }

    @Test fun directoryRevisionChangesWhenArtworkChangesButManifestDoesNot() {
        val dir = tmp.newFolder("revisable-pack")
        File(dir, "theme.json").writeText("{}")
        val art = File(dir, "sky.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val source = DirectoryThemeSource(dir)
        val before = source.contentRevision(listOf("theme.json", "sky.webp"))

        art.writeBytes(byteArrayOf(1, 9, 3))

        assertFalse(before == source.contentRevision(listOf("theme.json", "sky.webp")))
    }

    @Test fun revisionIsStableWhenPackBytesAreUnchanged() {
        val source = ZipThemeSource(zip("theme.json" to "{}".toByteArray(), "sky.webp" to byteArrayOf(4, 5, 6)))
        val paths = listOf("theme.json", "sky.webp")
        assertEquals(source.contentRevision(paths), source.contentRevision(paths.reversed()))
    }
}
