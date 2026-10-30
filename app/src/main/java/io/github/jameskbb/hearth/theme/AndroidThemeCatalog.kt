package io.github.jameskbb.hearth.theme

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * The production [ThemeCatalog]: bundled packs from `assets/themes/<id>/` (ordered by
 * `assets/themes/order.txt`), installed packs from the app-specific `files/themes/` folder on
 * shared storage, as folders or zips. File work runs on Dispatchers.IO.
 *
 * Memory: one decoded theme is cached ([load]); decodes run one at a time, so at most the theme
 * on screen plus the one being decoded exist at once. Gallery previews are 480x270 and live
 * until [releasePreviews]. [release] drops all of it (memory pressure in the background).
 */
class AndroidThemeCatalog(context: Context, scope: CoroutineScope) : ThemeCatalog {
    private val app = context.applicationContext
    private val assets: AssetManager = app.assets

    private val _themes = MutableStateFlow<List<ThemeInfo>>(emptyList())
    override val themes: StateFlow<List<ThemeInfo>> = _themes.asStateFlow()
    private val _failures = MutableStateFlow<List<ThemeFailure>>(emptyList())
    override val failures: StateFlow<List<ThemeFailure>> = _failures.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    override val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val _releases = MutableStateFlow(0)
    override val releases: StateFlow<Int> = _releases.asStateFlow()

    private class Entry(val manifest: ThemeManifest, val source: ThemeSource, val info: ThemeInfo)

    private data class RevisionedSource(val source: ThemeSource, val revision: String)

    @Volatile private var entries: Map<ThemeId, Entry> = emptyMap()

    /** Bundled packs never change while the app runs: scanned once. */
    private var bundled: List<ThemeOrder.Scanned<RevisionedSource>>? = null
    private var order: List<String> = emptyList()

    private val scanLock = Mutex()
    private val decodeLock = Mutex()
    private val firstScan = CompletableDeferred<Unit>()

    @Volatile private var current: LoadedTheme? = null
    private data class PreviewEntry(val info: ThemeInfo, val image: ImageBitmap)

    private val previews = LinkedHashMap<ThemeId, PreviewEntry>()

    init {
        scope.launch { refresh() }
    }

    override suspend fun refresh() {
        scanLock.withLock {
            withContext(Dispatchers.IO) {
                val b = bundled ?: scanBundled().also { bundled = it }
                val arranged = ThemeOrder.arrange(b + scanInstalled(), order)
                val before = entries
                val map = LinkedHashMap<ThemeId, Entry>()
                for (s in arranged.accepted) {
                    val m = s.manifest!!
                    val source = s.source
                    map[m.id] = Entry(m, source.source, ThemeOrder.info(m, s.bundled, source.revision))
                }
                entries = map
                _themes.value = map.values.map { it.info }
                _failures.value = arranged.failures
                // A pack that changed on disk must decode again.
                current?.let { c -> if (map[c.info.id]?.info != c.info) current = null }
                synchronized(previews) {
                    previews.keys.removeAll { id -> map[id] == null || before[id]?.info != map[id]?.info }
                }
            }
            _loaded.value = true
            firstScan.complete(Unit)
        }
    }

    private fun scanBundled(): List<ThemeOrder.Scanned<RevisionedSource>> {
        order = try {
            assets.open("$ASSET_DIR/order.txt").bufferedReader().use { ThemeOrder.parseOrder(it.readText()) }
        } catch (_: IOException) {
            emptyList()
        }
        val dirs = try {
            assets.list(ASSET_DIR).orEmpty().filter { it != "order.txt" }.sorted()
        } catch (_: IOException) {
            emptyList()
        }
        return dirs.mapNotNull { dir ->
            val src = AssetThemeSource(assets, "$ASSET_DIR/$dir", dir)
            // Every folder with a theme.json is a theme; anything else in assets/themes is not.
            if (!src.exists(ThemeSource.MANIFEST)) null else scan(src, bundled = true)
        }
    }

    private fun scanInstalled(): List<ThemeOrder.Scanned<RevisionedSource>> {
        // Creates the folder on first use, so it is there to copy packs into.
        val dir = try {
            app.getExternalFilesDir(INSTALLED_DIR)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val children = dir.listFiles().orEmpty().sortedBy { it.name }
        return children.mapNotNull { f ->
            when {
                f.name.startsWith(".") -> null
                f.isDirectory -> scan(DirectoryThemeSource(f), bundled = false)
                f.isFile && f.name.endsWith(".zip", ignoreCase = true) -> try {
                    scan(ZipThemeSource(f), bundled = false)
                } catch (e: ThemeException) {
                    ThemeOrder.Scanned(f.name, false, null, e.problem, RevisionedSource(NoSource, ""))
                }
                else -> null
            }
        }
    }

    /** Parses and validates one pack from headers only. Never throws. */
    private fun scan(src: ThemeSource, bundled: Boolean): ThemeOrder.Scanned<RevisionedSource> {
        var revision = if (bundled) "bundled" else ""
        val result = try {
            val text = src.readManifest() ?: throw ThemeException(ThemeProblem.MissingManifest)
            val manifest = ThemeManifestCodec.parse(text)
            val problem = ThemeValidation.check(manifest, src::exists) { path -> bounds(src, path) }
            if (!bundled && problem == null) {
                val files = buildList {
                    add(ThemeSource.MANIFEST)
                    manifest.preview?.let(::add)
                    manifest.layers.forEach { add(it.image) }
                }
                revision = src.contentRevision(files)
            }
            manifest to problem
        } catch (e: ThemeException) {
            null to e.problem
        } catch (e: Exception) {
            Log.w(TAG, "Theme ${src.label} could not be read", e)
            null to ThemeProblem.MalformedManifest
        }
        return ThemeOrder.Scanned(src.label, bundled, result.first, result.second, RevisionedSource(src, revision))
    }

    private fun bounds(src: ThemeSource, path: String): ImageBounds? = try {
        src.open(path)?.use { input ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, o)
            if (o.outWidth > 0 && o.outHeight > 0) ImageBounds(o.outWidth, o.outHeight) else null
        }
    } catch (_: IOException) {
        null
    }

    override suspend fun load(id: ThemeId): LoadedTheme? {
        firstScan.await()
        return decodeLock.withLock {
            var answer: LoadedTheme? = null
            while (true) {
                val entry = entries[id] ?: break
                val cached = current
                if (cached?.info == entry.info) {
                    answer = cached
                    break
                }
                val decoded = withContext(Dispatchers.IO) { decode(entry) }
                // Reload may have replaced this pack while its old bytes were decoding. Never
                // publish or return that stale result; decode the current revision instead.
                if (entries[id]?.info != entry.info) continue
                if (decoded != null) current = decoded
                answer = decoded
                break
            }
            answer
        }
    }

    /** Decodes every layer. Null on failure, which also lists the pack as not loaded. */
    private suspend fun decode(entry: Entry): LoadedTheme? {
        val byPath = HashMap<String, ImageBitmap>()
        val layers = ArrayList<LoadedLayer>(entry.manifest.layers.size)
        for (spec in entry.manifest.layers) {
            coroutineContext.ensureActive()
            val image = byPath[spec.image] ?: run {
                val bitmap = try {
                    entry.source.open(spec.image)?.use { decodeFull(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    // Not the pack's fault; show the fallback without blaming it.
                    Log.w(TAG, "Out of memory decoding ${entry.info.id.name}", e)
                    return null
                } catch (e: Exception) {
                    Log.w(TAG, "Layer ${spec.image} of ${entry.info.id.name} failed", e)
                    null
                } ?: return failDecode(entry, spec.image)
                bitmap.prepareToDraw()
                bitmap.asImageBitmap().also { byPath[spec.image] = it }
            }
            layers += LoadedLayer(image, spec)
        }
        return LoadedTheme(entry.info, entry.manifest.sway, layers)
    }

    private fun decodeFull(input: InputStream): Bitmap? {
        val o = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeStream(input, null, o)
    }

    private fun failDecode(entry: Entry, path: String): LoadedTheme? {
        val id = entry.info.id
        // A reload may have replaced the pack while this revision was decoding. Its failure must
        // not remove the newer accepted entry or add a misleading error for it.
        if (entries[id]?.info != entry.info) return null
        val failure = ThemeFailure(entry.source.label, ThemeProblem.UnreadableImage(path))
        entries = entries - id
        _themes.update { list -> list.filterNot { it.id == id } }
        _failures.update { it + failure }
        return null
    }

    override suspend fun preview(id: ThemeId): ImageBitmap? {
        firstScan.await()
        while (true) {
            val entry = entries[id] ?: return null
            synchronized(previews) { previews[id]?.takeIf { it.info == entry.info }?.image }?.let { return it }
            val image = withContext(Dispatchers.IO) {
                entry.manifest.preview?.let { decodeSmall(entry.source, it) }
                    ?: decodeSmall(entry.source, entry.manifest.layers.first().image)
            } ?: return null
            // As with full artwork, a concurrent reload can replace the source during decode.
            if (entries[id]?.info != entry.info) continue
            synchronized(previews) {
                previews[id] = PreviewEntry(entry.info, image)
                while (previews.size > MAX_PREVIEWS) previews.remove(previews.keys.first())
            }
            return image
        }
    }

    /** Decodes [path] at about [PREVIEW_WIDTH] px wide, by whole-power-of-two subsampling. */
    private fun decodeSmall(src: ThemeSource, path: String): ImageBitmap? = try {
        val b = bounds(src, path) ?: return null
        var sample = 1
        while (b.width / (sample * 2) >= PREVIEW_WIDTH) sample *= 2
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        src.open(path)?.use { BitmapFactory.decodeStream(it, null, o) }?.also { it.prepareToDraw() }?.asImageBitmap()
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        Log.w(TAG, "Preview $path failed", e)
        null
    }

    override fun releasePreviews() {
        synchronized(previews) { previews.clear() }
    }

    override fun release() {
        current = null
        releasePreviews()
        _releases.update { it + 1 }
    }

    /** A placeholder source for a zip that could not even be opened; never read. */
    private object NoSource : ThemeSource {
        override val label = ""
        override fun open(path: String): InputStream? = null
    }

    private companion object {
        const val TAG = "HearthThemes"
        const val ASSET_DIR = "themes"
        const val INSTALLED_DIR = "themes"
        const val PREVIEW_WIDTH = 480
        const val MAX_PREVIEWS = 16
    }
}

/** A bundled pack: a folder under the APK's assets. */
class AssetThemeSource(private val assets: AssetManager, private val dir: String, override val label: String) :
    ThemeSource {
    override fun open(path: String): InputStream? {
        if (!ThemeManifestCodec.isSafePath(path)) return null
        return try {
            assets.open("$dir/$path")
        } catch (_: IOException) {
            null
        }
    }
}
