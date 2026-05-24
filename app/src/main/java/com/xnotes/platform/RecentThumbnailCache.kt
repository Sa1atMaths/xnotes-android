package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * A small on-disk cache of recent-note thumbnails (plus page counts) so the
 * backstage opens instantly across launches instead of re-rendering every note.
 *
 * Each recent URI maps to two files keyed by a hash of the URI: `<key>.png` (the
 * thumbnail) and `<key>.txt` (the page count). Bounded by design: [prune] deletes
 * anything no longer in the recent list (capped at 10), so it never grows without
 * limit. Best-effort — failures are swallowed and just cause a re-render.
 */
class RecentThumbnailCache(private val dir: File) {

    /** The cached thumbnail + page count for [uri], or null when not cached. */
    fun load(uri: String): Pair<Bitmap, Int>? {
        val key = key(uri)
        val png = File(dir, "$key.png")
        if (!png.exists()) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(png.path) }.getOrNull() ?: return null
        val pages = runCatching { File(dir, "$key.txt").readText().trim().toInt() }.getOrDefault(0)
        return bitmap to pages
    }

    fun store(uri: String, bitmap: Bitmap, pageCount: Int) {
        runCatching {
            dir.mkdirs()
            val key = key(uri)
            FileOutputStream(File(dir, "$key.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "$key.txt").writeText(pageCount.toString())
        }
    }

    fun remove(uri: String) {
        val key = key(uri)
        runCatching { File(dir, "$key.png").delete() }
        runCatching { File(dir, "$key.txt").delete() }
    }

    /** Delete cached files for any URI not in [keep]. */
    fun prune(keep: Set<String>) {
        runCatching {
            val keepKeys = keep.mapTo(HashSet()) { key(it) }
            dir.listFiles()?.forEach { f ->
                if (f.name.substringBeforeLast('.') !in keepKeys) f.delete()
            }
        }
    }

    private fun key(uri: String): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
