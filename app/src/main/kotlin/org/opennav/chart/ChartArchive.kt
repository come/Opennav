/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.chart

import android.content.Context
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
 *  1. `Android/data/org.opennav/files/charts/*.pmtiles` -- writable over `adb push`
 *     without root and without any storage permission, which is why it is first.
 *  2. The app's private `files/charts` directory, for anything the app placed itself.
 */
object ChartArchive {

    const val DIRECTORY_NAME = "charts"
    private const val TAG = "ChartArchive"

    data class Located(val file: File, val directory: File)

    /** Every directory the app is willing to read charts from, best first. */
    fun searchPath(context: Context): List<File> = buildList {
        context.getExternalFilesDir(null)?.let { add(File(it, DIRECTORY_NAME)) }
        add(File(context.filesDir, DIRECTORY_NAME))
    }

    /**
     * Finds the archive to display, or null if the user has not installed one yet.
     *
     * When several are present the largest wins, on the assumption that it is the real
     * survey rather than the synthetic sample.
     */
    fun locate(context: Context): Located? {
        for (directory in searchPath(context)) {
            val candidates = directory.listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") }
                ?.sortedByDescending { it.length() }
                .orEmpty()
            if (candidates.isNotEmpty()) {
                Log.i(TAG, "using ${candidates.first().absolutePath}")
                return Located(candidates.first(), directory)
            }
        }
        return null
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
