package com.jett.launcher

import android.content.Context

/** Every backdrop photo on the box (downloaded 4K copies first, built-in starter copies otherwise). */
object PhotoIndex {
    data class Photo(val id: String, val phase: String, val path: String, val caption: String) {
        val country get() = caption.substringAfterLast(", ", caption).trim()
    }

    private val PHASES = listOf("dawn", "morning", "midday", "golden", "dusk", "night")
    @Volatile private var builtInCaptions: Map<String, String>? = null

    private fun builtIn(ctx: Context): Map<String, String> = builtInCaptions ?: try {
        val j = org.json.JSONObject(ctx.assets.open("captions.json").bufferedReader().use { it.readText() })
        j.keys().asSequence().associateWith { j.getString(it) }
    } catch (e: Exception) { emptyMap<String, String>() }.also { builtInCaptions = it }

    fun idOf(path: String) = path.substringAfterLast('/').substringBeforeLast('.')

    // Listing ~1,000 files is slow; remembered until the library changes (see [invalidate]).
    private val byPhase = java.util.concurrent.ConcurrentHashMap<String, List<Photo>>()
    fun invalidate() = byPhase.clear()

    fun phase(ctx: Context, phase: String): List<Photo> = byPhase.getOrPut(phase) { list(ctx, phase) }

    private fun list(ctx: Context, phase: String): List<Photo> {
        val caps = builtIn(ctx) + PhotoLibrary.captions(ctx)
        val builtIn = try {
            ctx.assets.list("bg/$phase").orEmpty()
                .filter { it.matches(Regex(".*\\.(jpe?g|png|webp)", RegexOption.IGNORE_CASE)) }
                .map { "asset:bg/$phase/$it" }
        } catch (e: Exception) { emptyList() }
        val downloaded = PhotoLibrary.files(ctx, phase).map { it.path }
        val have = downloaded.map { idOf(it) }.toSet()
        return (downloaded + builtIn.filter { idOf(it) !in have }).map { Photo(idOf(it), phase, it, caps[idOf(it)].orEmpty()) }
    }

    fun all(ctx: Context): List<Photo> = PHASES.flatMap { phase(ctx, it) }

    fun find(ctx: Context, id: String): Photo? = all(ctx).firstOrNull { it.id == id }

    /** Does [p] belong to one of the chosen places ("Iceland", "Kyoto, Japan")? Empty = everywhere. */
    fun inPlaces(p: Photo, places: Set<String>) = places.isEmpty() ||
        places.any { it.equals(p.country, true) || it.equals(p.caption, true) }

    fun open(ctx: Context, path: String): java.io.InputStream =
        if (path.startsWith("asset:")) ctx.assets.open(path.removePrefix("asset:")) else java.io.File(path).inputStream()
}
