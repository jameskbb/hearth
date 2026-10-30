package io.github.jameskbb.hearth.theme

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Where one pack's files come from: a bundled assets folder, an installed folder or an
 * installed zip. Paths are relative to the pack (the folder holding `theme.json`) and were
 * checked by [ThemeManifestCodec.isSafePath]; sources refuse anything that would leave it.
 * Packs are read-only: no source ever writes, moves or deletes.
 */
interface ThemeSource {
    /** The folder or file name, shown in Settings when the pack fails. */
    val label: String

    /** A fresh stream for [path], or null when it does not exist. The caller closes it. */
    fun open(path: String): InputStream?

    fun exists(path: String): Boolean = try {
        open(path)?.use { true } ?: false
    } catch (_: IOException) {
        false
    }

    /**
     * A content revision for the files that make up a pack. Installed packs use this to preserve
     * decoded caches when nothing changed and to invalidate them when artwork changes without a
     * manifest edit. The digest is computed only during an explicit catalog refresh.
     */
    fun contentRevision(paths: Collection<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(32 * 1024)
        for (path in paths.distinct().sorted()) {
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            val input = open(path)
            if (input == null) {
                digest.update(1)
                continue
            }
            input.use {
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_REVISION_BYTES) throw ThemeException(ThemeProblem.ArchiveTooLarge)
                    digest.update(buffer, 0, count)
                }
            }
            digest.update(2)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        const val MANIFEST = "theme.json"

        /** Longest `theme.json` Hearth reads. Real manifests are a few kilobytes. */
        const val MAX_MANIFEST_BYTES = 256 * 1024
        /** Keeps a malformed directory pack from making a refresh read without bound. */
        const val MAX_REVISION_BYTES = 64L * 1024 * 1024
    }
}

/** Reads the manifest of [source] as text, refusing oversized files. Null when it is missing. */
fun ThemeSource.readManifest(): String? {
    val stream = try {
        open(ThemeSource.MANIFEST)
    } catch (_: IOException) {
        null
    } ?: return null
    return stream.use { input ->
        val bytes = input.readNBytesCompat(ThemeSource.MAX_MANIFEST_BYTES + 1)
        if (bytes.size > ThemeSource.MAX_MANIFEST_BYTES) throw ThemeException(ThemeProblem.BadValue(ThemeSource.MANIFEST))
        String(bytes, Charsets.UTF_8).removePrefix(BYTE_ORDER_MARK)
    }
}

/** A UTF-8 byte order mark some editors put at the start of a file. */
private const val BYTE_ORDER_MARK = "\uFEFF"

/** readNBytes is Java 9 (API 33 on Android); this works everywhere. */
private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}

/** A pack folder on disk. Symbolic links that point outside it are refused. */
class DirectoryThemeSource(private val dir: File) : ThemeSource {
    override val label: String = dir.name
    private val root: String by lazy { dir.canonicalPath + File.separator }

    override fun open(path: String): InputStream? {
        if (!ThemeManifestCodec.isSafePath(path)) return null
        val f = File(dir, path)
        if (!f.isFile || !f.canonicalPath.startsWith(root)) return null
        return f.inputStream()
    }
}

/**
 * A pack in a zip: `theme.json` at the root or inside one top-level folder. The archive is
 * checked when this is built: each entry of the pack at most [MAX_ENTRY_BYTES] and all of
 * them at most [MAX_TOTAL_BYTES] uncompressed, and reads are cut off at the same limit in case
 * an entry lies about its size. Entries whose names would leave the pack are never served.
 * Each [open] reopens the file, so nothing stays open between reads.
 */
class ZipThemeSource(private val file: File) : ThemeSource {
    override val label: String = file.name
    private val prefix: String
    private val entries: Set<String>

    init {
        try {
            ZipFile(file).use { zip ->
                val names = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .associate { it.name to it.size }
                prefix = when {
                    ThemeSource.MANIFEST in names -> ""
                    else -> {
                        val nested = names.keys.filter {
                            val parts = it.split('/')
                            parts.size == 2 && parts[1] == ThemeSource.MANIFEST && parts[0].isNotEmpty() && parts[0] != ".."
                        }
                        nested.singleOrNull()?.substringBefore('/')?.plus("/")
                            ?: throw ThemeException(ThemeProblem.MissingManifest)
                    }
                }
                var total = 0L
                val inside = HashSet<String>()
                for ((name, size) in names) {
                    if (!name.startsWith(prefix)) continue
                    val rel = name.removePrefix(prefix)
                    if (!ThemeManifestCodec.isSafePath(rel)) continue
                    if (size > MAX_ENTRY_BYTES) throw ThemeException(ThemeProblem.ArchiveTooLarge)
                    total += size.coerceAtLeast(0)
                    if (total > MAX_TOTAL_BYTES) throw ThemeException(ThemeProblem.ArchiveTooLarge)
                    inside += rel
                }
                entries = inside
            }
        } catch (e: ZipException) {
            throw ThemeException(ThemeProblem.UnreadableArchive)
        } catch (e: IOException) {
            throw ThemeException(ThemeProblem.UnreadableArchive)
        }
    }

    override fun exists(path: String): Boolean = path in entries

    override fun open(path: String): InputStream? {
        if (path !in entries) return null
        val zip = ZipFile(file)
        val entry = zip.getEntry(prefix + path)
        if (entry == null) {
            zip.close()
            return null
        }
        val stream = try {
            zip.getInputStream(entry)
        } catch (e: IOException) {
            zip.close()
            throw e
        }
        return LimitedStream(stream, MAX_ENTRY_BYTES) { zip.close() }
    }

    /** Ends with an IOException past [limit] bytes; closes the archive with the stream. */
    private class LimitedStream(input: InputStream, private val limit: Long, private val onClose: () -> Unit) :
        FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count(n.toLong())
            return n
        }

        private fun count(n: Long) {
            count += n
            if (count > limit) throw IOException("zip entry larger than its limit")
        }

        override fun close() {
            try {
                super.close()
            } finally {
                onClose()
            }
        }
    }

    companion object {
        const val MAX_ENTRY_BYTES = 24L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    }
}
