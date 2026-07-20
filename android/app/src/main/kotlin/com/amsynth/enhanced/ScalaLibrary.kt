package com.amsynth.enhanced

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * The bundled Huygens-Fokker Scala scale archive (~5400 .scl scales, freely
 * redistributable). Two assets:
 *   scala/index.tsv   — precomputed at build time: "<entry>\t<display>" per line
 *                        (so the searchable list is instant and can't fail).
 *   scala/scales.zip  — the scale files; an individual scale is read on demand
 *                        when selected.
 */
object ScalaLibrary {
    data class Scale(val entry: String, val display: String)

    @Volatile
    private var index: List<Scale>? = null
    private var zip: File? = null

    /** The full scale list, read from the bundled precomputed index. Fast + robust. */
    fun load(context: Context): List<Scale> {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            val list = runCatching {
                context.assets.open("scala/index.tsv").bufferedReader().useLines { seq ->
                    seq.mapNotNull { line ->
                        val t = line.indexOf('\t')
                        if (t > 0) Scale(line.substring(0, t), line.substring(t + 1)) else null
                    }.toList()
                }
            }.getOrDefault(emptyList())
            index = list
            return list
        }
    }

    private fun cacheZip(context: Context): File {
        zip?.let { if (it.exists() && it.length() > 0L) return it }
        val f = File(context.cacheDir, "scales.zip")
        if (!f.exists() || f.length() == 0L) {
            context.assets.open("scala/scales.zip").use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
        }
        zip = f
        return f
    }

    /** The raw .scl text for an entry, or null. */
    fun read(context: Context, entry: String): String? = runCatching {
        ZipFile(cacheZip(context)).use { zf ->
            zf.getEntry(entry)?.let { zf.getInputStream(it).bufferedReader().readText() }
        }
    }.getOrNull()
}
