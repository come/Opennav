/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.chart

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * Where the bathymetry archive lives on the device.
 *
 * Phase 0 keeps this dumb on purpose: one `.pmtiles` file, found by looking in the two
 * places a developer would plausibly have pushed it. Chart-pack management is Phase 4.
 *
 * Search order:
 *
 *  1. The `charts` directory under `Android/data/org.opennav/files` -- writable over
 *     `adb push` without root and without any storage permission, which is why it is
 *     first. (Written out rather than glob-pasted: Kotlin block comments nest, so a
 *     literal slash-star in a KDoc silently swallows the rest of the file.)
 *  2. The app's private `files/charts` directory, for anything the app placed itself.
 */
object ChartArchive {

    const val DIRECTORY_NAME = "charts"
    const val EXTENSION = ".pmtiles"

    /**
     * A fabricated seabed under the Baie de Quiberon, shipped inside the APK.
     *
     * It exists so the first launch shows a map instead of an empty screen, and so the
     * colour bands, the drying shoal and the violet no-data hole can be checked on a real
     * phone without a SHOM account. It is *not* a chart: the channel, the shoal and the
     * hole are geometric shapes laid over a real coastline, and the app says so in a
     * permanent red band for as long as it is the archive being displayed.
     *
     * The name carries the warning too, because the file outlives the app's UI as soon as
     * someone copies it off the phone.
     */
    const val BUNDLED_ASSET = "demo-quiberon-synthetic.pmtiles"

    /**
     * The real charts shipped inside the APK, unpacked on first launch.
     *
     * Unlike [BUNDLED_ASSET], these are *not* fiction: an OSM base map and the OpenSeaMap
     * buoyage for the whole of Brittany, built by `tools/build_basemap.py` and
     * `tools/build_seamarks.py`. They are what the app opens on -- a real coastline and a
     * real set of marks -- so the first launch shows the actual product rather than an
     * invented seabed. Depths are deliberately absent: bathymetry is a survey the user
     * imports, and a made-up one under this would read as data.
     *
     * Both are vector and small (a base map of a region weighs single-digit megabytes),
     * which is why they can be committed as assets at all where a real bathymetry survey,
     * hundreds of megabytes, never could.
     */
    val BUNDLED_DEFAULTS = listOf("bretagne-base.pmtiles", "bretagne-seamarks.pmtiles")

    private const val TAG = "ChartArchive"
    private const val STAGING_SUFFIX = ".part"
    private const val COPY_BUFFER_BYTES = 1 shl 16

    data class Located(val file: File, val directory: File, val header: PmtilesHeader)

    /**
     * What is installed, split by what it actually contains.
     *
     * The kind is read from the PMTiles header rather than guessed from the filename: a
     * seamark overlay handed to the depth layer would render as a blank map, and a
     * bathymetry archive handed to the seamark layer would render as nothing at all.
     */
    data class Charts(
        val bathymetry: Located?,
        val basemap: Located?,
        val seamarks: Located?,
    ) {
        /**
         * Whichever archive should set the camera, preferring the most specific.
         *
         * Any one of the three is enough to draw a map worth looking at, which is why
         * this exists at all: the base map alone gives a coastline and a GPS position,
         * and that is a usable thing to open on.
         */
        val any: Located? get() = bathymetry ?: basemap ?: seamarks

        val all: List<Located> get() = listOfNotNull(bathymetry, basemap, seamarks)
    }

    /** Every directory the app is willing to read charts from, best first. */
    fun searchPath(context: Context): List<File> = buildList {
        context.getExternalFilesDir(null)?.let { add(File(it, DIRECTORY_NAME)) }
        add(File(context.filesDir, DIRECTORY_NAME))
    }

    /** Every `.pmtiles` the app can see, largest first. */
    fun installed(context: Context): List<File> = searchPath(context)
        .flatMap { directory ->
            directory.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }.orEmpty().toList()
        }
        .sortedByDescending { it.length() }

    /**
     * Finds what to display: at most one bathymetry archive and at most one seamark
     * overlay, so the two can be installed independently and shown together.
     *
     * Within a kind, an explicit choice ([preferredPath], set when the user imports a
     * file) wins. Failing that, a real survey beats a fabricated one and the larger beats
     * the smaller -- in that order. Size alone used to decide, which was fine while the
     * only synthetic archive was one a developer had gone out of their way to install;
     * with a demo shipped in the APK it is not, because a single imported harbour can
     * easily weigh less than a demo spanning a whole bay.
     */
    fun locate(context: Context, preferredPath: String? = null): Charts {
        val readable = installed(context).mapNotNull { file ->
            PmtilesHeader.read(file)?.let { Located(file, file.parentFile ?: file, it) }
        }

        fun pick(matches: (PmtilesHeader) -> Boolean): Located? {
            val ofKind = readable.filter { matches(it.header) }
            return ofKind.firstOrNull { it.file.absolutePath == preferredPath }
                ?: ofKind.minWithOrNull(
                    compareBy<Located> { it.header.synthetic }
                        .thenByDescending { it.file.length() },
                )
        }

        // Buoyage and base map are both MVT, so the tile type cannot separate them; the
        // layer names in the archive's own metadata can. An archive that declares
        // neither is ignored rather than guessed at, because handing a base map to the
        // seamark layer draws nothing at all and looks exactly like a missing file.
        val charts = Charts(
            bathymetry = pick { it.tileType == PmtilesHeader.TILE_TYPE_PNG },
            basemap = pick { it.tileType == PmtilesHeader.TILE_TYPE_MVT && it.isBasemap },
            seamarks = pick { it.tileType == PmtilesHeader.TILE_TYPE_MVT && it.isSeamarks },
        )
        Log.i(
            TAG,
            "bathy=${charts.bathymetry?.file?.name} base=${charts.basemap?.file?.name} " +
                "seamarks=${charts.seamarks?.file?.name}",
        )
        return charts
    }

    /**
     * Copies a `.pmtiles` chosen through the system file picker into the charts directory.
     *
     * The picker hands back a content URI that is only readable while the app holds the
     * grant, so the archive has to be copied rather than referenced: MapLibre range-reads
     * it from a plain path, for months, long after the grant is gone.
     *
     * Written to a temporary name and renamed only once the copy completes, so an import
     * interrupted halfway cannot leave a truncated archive looking like a valid chart.
     *
     * Runs on a background thread by the caller's arrangement -- a real survey is hundreds
     * of megabytes.
     */
    fun importFrom(context: Context, uri: Uri): Result<File> = runCatching {
        val name = displayName(context, uri)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "impossible de lire le fichier choisi" }
            install(context, input, name)
        }
    }.onFailure { Log.w(TAG, "import failed", it) }

    /**
     * Unpacks the bundled demo chart on first launch, so the app opens on a map.
     *
     * Copied out of the APK rather than read in place. MapLibre does understand
     * `asset://`, so `pmtiles://asset://…` is tempting and would save the disk copy --
     * but it is a second unverified URI scheme stacked under an unverified one, and the
     * whole point of the demo is to be the thing that proves the *first* one works. A
     * copy costs a few megabytes once and puts the demo on exactly the code path an
     * imported chart takes.
     *
     * Returns null when the demo is already installed, when the asset is missing from
     * the build, or when the copy fails: none of those is worth interrupting a launch
     * for, and the empty state still explains how to import a real chart.
     *
     * Caller's job to keep this off the main thread, and to remember that it ran -- a
     * demo the user deleted must stay deleted rather than reappearing at every launch.
     */
    fun seedBundled(context: Context): File? = runCatching {
        val existing = File(preferredDirectory(context), BUNDLED_ASSET)
        if (existing.isFile && PmtilesHeader.read(existing) != null) {
            Log.i(TAG, "demo already installed")
            return null
        }
        context.assets.open(BUNDLED_ASSET).use { input ->
            install(context, input, BUNDLED_ASSET)
        }
    }.onFailure { Log.w(TAG, "could not unpack the bundled demo", it) }.getOrNull()

    /**
     * Unpacks the bundled Brittany charts on first launch, so the app opens on a real map.
     *
     * The counterpart to [seedBundled] for [BUNDLED_DEFAULTS]: same copy-out-of-the-APK
     * path an imported chart takes, one file at a time so a build shipping only some of
     * them still installs what it has. Each is skipped when already present, so this is
     * cheap to call again and a chart the user deleted is not resurrected -- the caller
     * still records that the seeding ran, exactly as for the demo.
     *
     * Returns the files it actually installed this time, which the caller uses only to
     * decide whether re-locating is worthwhile; an empty list means everything was already
     * there, or the assets are missing from this build, and neither interrupts a launch.
     *
     * Caller's job to keep this off the main thread.
     */
    fun seedDefaults(context: Context): List<File> = BUNDLED_DEFAULTS.mapNotNull { name ->
        runCatching {
            val existing = File(preferredDirectory(context), name)
            if (existing.isFile && PmtilesHeader.read(existing) != null) {
                Log.i(TAG, "$name already installed")
                return@runCatching null
            }
            context.assets.open(name).use { input -> install(context, input, name) }
        }.onFailure { Log.w(TAG, "could not unpack bundled $name", it) }.getOrNull()
    }

    /** Whether this build carries the default charts, so first launch has something to seed. */
    fun hasDefaults(context: Context): Boolean = BUNDLED_DEFAULTS.any { name ->
        runCatching { context.assets.open(name).close() }.isSuccess
    }

    /**
     * Deletes the unpacked demo. Returns true if a file was actually removed.
     *
     * Wanting it gone is a normal thing to want -- once a real buoyage layer is installed,
     * an invented seabed under it is worse than no seabed at all, because the colours read
     * as information. The caller records that it was unpacked once, so this does not come
     * back at the next launch.
     */
    fun removeBundled(context: Context): Boolean {
        val file = File(preferredDirectory(context), BUNDLED_ASSET)
        val removed = file.isFile && file.delete()
        Log.i(TAG, "demo removal requested: removed=$removed")
        return removed
    }

    /** Whether this build actually carries the demo, so the UI can stop offering it. */
    fun hasBundled(context: Context): Boolean =
        runCatching { context.assets.open(BUNDLED_ASSET).close() }.isSuccess

    /**
     * Copies a stream into the charts directory under [name], atomically enough.
     *
     * Written to a temporary name and renamed only once the copy completes and the header
     * parses, so a copy interrupted halfway -- or a file that was never a chart -- cannot
     * leave a truncated archive sitting there looking valid.
     */
    private fun install(context: Context, source: InputStream, name: String): File {
        val directory = preferredDirectory(context)
        val destination = File(directory, name)
        val staging = File(directory, "$name$STAGING_SUFFIX")

        staging.delete()
        try {
            staging.outputStream().use { output -> source.copyTo(output, COPY_BUFFER_BYTES) }
            require(PmtilesHeader.read(staging) != null) {
                "ce fichier n'est pas une archive PMTiles v3"
            }
            destination.delete()
            check(staging.renameTo(destination)) { "impossible de finaliser l'import" }
        } finally {
            staging.delete()
        }
        Log.i(TAG, "installed ${destination.absolutePath} (${destination.length()} bytes)")
        return destination
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        val raw = fromProvider ?: uri.lastPathSegment ?: "carte"
        // Providers can return anything, including path separators.
        val safe = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safe.endsWith(EXTENSION)) safe else "$safe$EXTENSION"
    }

    /** Makes sure the preferred directory exists, and returns it for the empty-state UI. */
    fun preferredDirectory(context: Context): File =
        searchPath(context).first().also { it.mkdirs() }

    /**
     * The source URI MapLibre needs.
     *
     * `pmtiles://` hands the request to MapLibre's built-in PMTiles file source, which
     * range-reads the archive in place; the nested `file://` tells it the archive is a
     * local file rather than something to fetch over HTTP. Nothing here touches the
     * network, which is what lets the manifest strip the INTERNET permission.
     */
    fun sourceUri(file: File): String = "pmtiles://file://${file.absolutePath}"
}
