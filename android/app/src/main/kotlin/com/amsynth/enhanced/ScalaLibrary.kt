package com.amsynth.enhanced

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * The bundled Huygens-Fokker Scala scale archive (assets/scala/scales.zip,
 * ~5400 .scl scales, freely redistributable). The zip is copied to the app
 * cache once so it can be opened with ZipFile; a lightweight index
 * (entry name + description) is built once and cached to a TSV so later
 * launches are instant. Scale contents are read on demand when selected.
 */
object ScalaLibrary {
    data class Scale(val entry: String, val display: String)

    @Volatile
    private var index: List<Scale>? = null
    private var zip: File? = null

    private fun cacheZip(context: Context): File {
        val f = File(context.cacheDir, "scales.zip")
        if (!f.exists() || f.length() == 0L) {
            context.assets.open("scala/scales.zip").use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
        }
        return f
    }

    /** Build (or load the cached) index of every scale. Safe to call off the main thread. */
    fun load(context: Context): List<Scale> {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            val z = cacheZip(context)
            zip = z
            val cacheIdx = File(context.cacheDir, "scala_index_v2.tsv")
            val list: List<Scale> =
                if (cacheIdx.exists() && cacheIdx.length() > 0L) {
                    cacheIdx.readLines().mapNotNull { line ->
                        val p = line.split('\t')
                        if (p.size >= 2) Scale(p[0], p[1]) else null
                    }
                } else {
                    val out = ArrayList<Scale>(5500)
                    ZipFile(z).use { zf ->
                        val e = zf.entries()
                        while (e.hasMoreElements()) {
                            val ze = e.nextElement()
                            if (ze.isDirectory || !ze.name.endsWith(".scl", ignoreCase = true)) continue
                            val base = ze.name.substringAfterLast('/').removeSuffix(".scl")
                            // The .scl description is the first non-comment line.
                            // The archive is Latin-1, so decode names/accents correctly.
                            val desc = zf.getInputStream(ze).bufferedReader(Charsets.ISO_8859_1).useLines { seq ->
                                seq.map { it.trim() }.firstOrNull { !it.startsWith("!") } ?: ""
                            }
                            out.add(Scale(ze.name, if (desc.isNotBlank()) "$desc  ·  $base" else base))
                        }
                    }
                    out.sortBy { it.display.lowercase() }
                    cacheIdx.writeText(out.joinToString("\n") { "${it.entry}\t${it.display}" })
                    out
                }
            index = list
            return list
        }
    }

    /** The raw .scl text for an entry, or null. */
    fun read(context: Context, entry: String): String? {
        val z = zip ?: cacheZip(context).also { zip = it }
        return runCatching {
            ZipFile(z).use { zf ->
                zf.getEntry(entry)?.let { zf.getInputStream(it).bufferedReader().readText() }
            }
        }.getOrNull()
    }
}
