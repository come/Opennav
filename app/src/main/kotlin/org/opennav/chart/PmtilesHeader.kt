/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.chart

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * The fixed 127-byte PMTiles v3 header, read directly.
 *
 * MapLibre parses this itself when it opens the archive, but it does not hand any of it
 * back to the application, and the app needs three things from it before the first frame:
 * where to point the camera, which zooms actually contain data, and enough identity to
 * tell the user which chart they are looking at.
 *
 * Spec: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 */
data class PmtilesHeader(
    /** 1 = Mapbox Vector Tile (seamarks), 2 = PNG (Terrain-RGB bathymetry). */
    val tileType: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val centerZoom: Int,
    val centerLon: Double,
    val centerLat: Double,
    val addressedTiles: Long,
    /**
     * True when the archive says it was fabricated by `make_sample_pmtiles.py`.
     *
     * That generator can now target any bounds, which makes it easy to put an invented
     * seabed under a real coastline while waiting for a survey. Useful, and exactly the
     * sort of thing that must never be mistaken for data.
     */
    val synthetic: Boolean,
) {
    companion object {
        private const val TAG = "PmtilesHeader"
        private const val HEADER_LENGTH = 127

        /** PMTiles tile-type values, from the v3 header. */
        const val TILE_TYPE_MVT = 1
        const val TILE_TYPE_PNG = 2
        private val MAGIC = byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'T'.code.toByte(),
            'i'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte())

        /** Returns null for anything that is not a readable PMTiles v3 archive. */
        fun read(file: File): PmtilesHeader? = runCatching {
            val bytes = ByteArray(HEADER_LENGTH)
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < HEADER_LENGTH) return null
                raf.readFully(bytes)
            }
            if (!bytes.copyOfRange(0, 7).contentEquals(MAGIC)) return null
            if (bytes[7].toInt() != 3) return null

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val synthetic = readsAsSynthetic(
                file,
                offset = buffer.getLong(24),
                length = buffer.getLong(32),
                compression = bytes[97].toInt() and 0xFF,
            )
            PmtilesHeader(
                synthetic = synthetic,
                tileType = bytes[99].toInt() and 0xFF,
                addressedTiles = buffer.getLong(72),
                minZoom = bytes[100].toInt() and 0xFF,
                maxZoom = bytes[101].toInt() and 0xFF,
                minLon = buffer.getInt(102).toE7(),
                minLat = buffer.getInt(106).toE7(),
                maxLon = buffer.getInt(110).toE7(),
                maxLat = buffer.getInt(114).toE7(),
                centerZoom = bytes[118].toInt() and 0xFF,
                centerLon = buffer.getInt(119).toE7(),
                centerLat = buffer.getInt(123).toE7(),
            )
        }.onFailure { Log.w(TAG, "could not read ${file.name}", it) }.getOrNull()

        private fun Int.toE7(): Double = this / 10_000_000.0

        private const val COMPRESSION_GZIP = 2

        /** Metadata is a small JSON blob; a chart that lies about this is not our problem. */
        private fun readsAsSynthetic(
            file: File,
            offset: Long,
            length: Long,
            compression: Int,
        ): Boolean = runCatching {
            if (length <= 0 || length > MAX_METADATA_BYTES) return false
            val raw = ByteArray(length.toInt())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                raf.readFully(raw)
            }
            val json = if (compression == COMPRESSION_GZIP) {
                GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            } else {
                raw
            }
            JSONObject(String(json, Charsets.UTF_8)).optBoolean("synthetic", false)
        }.getOrDefault(false)

        private const val MAX_METADATA_BYTES = 4L * 1024 * 1024
    }
}
