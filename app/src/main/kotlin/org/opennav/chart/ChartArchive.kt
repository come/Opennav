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

    private const val TAG = "ChartArchive"
    private const val STAGING_SUFFIX = ".part"
    private const val COPY_BUFFER_BYTES = 1 shl 16

    data class Located(val file: File, val directory: File)

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
     * Finds the archive to display, or null if the user has not installed one yet.
     *
     * An explicit choice ([preferredPath], set when the user imports a file) wins. Failing
     * that the largest archive does, on the assumption that it is a real survey rather
     * than the synthetic sample.
     */
    fun locate(context: Context, preferredPath: String? = null): Located? {
        val candidates = installed(context)
        if (candidates.isEmpty()) return null
        val chosen = candidates.firstOrNull { it.absolutePath == preferredPath } ?: candidates.first()
        Log.i(TAG, "using ${chosen.absolutePath}")
        return Located(chosen, chosen.parentFile ?: preferredDirectory(context))
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
        val directory = preferredDirectory(context)
        val name = displayName(context, uri)
        val destination = File(directory, name)
        val staging = File(directory, "$name$STAGING_SUFFIX")

        staging.delete()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "impossible de lire le fichier choisi" }
            staging.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
        }
        require(looksLikePmtiles(staging)) {
            "ce fichier n'est pas une archive PMTiles v3"
        }
        destination.delete()
        check(staging.renameTo(destination)) { "impossible de finaliser l'import" }
        Log.i(TAG, "imported ${destination.absolutePath} (${destination.length()} bytes)")
        destination
    }.onFailure { Log.w(TAG, "import failed", it) }

    /** Cheap sanity check so a wrong pick fails at import rather than as a blank map. */
    private fun looksLikePmtiles(file: File): Boolean =
        PmtilesHeader.read(file) != null

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
