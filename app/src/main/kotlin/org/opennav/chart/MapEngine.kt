/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.chart

import android.content.Context
import org.maplibre.android.MapLibre
import org.opennav.diag.CrashLog

/**
 * Starts MapLibre once, and remembers if it refused.
 *
 * `MapLibre.getInstance` loads the native library, sets up the file source and asks the
 * system about connectivity. Any of those can throw on a device or a build we have not
 * seen, and letting it propagate out of `Application.onCreate` kills the process before a
 * single pixel is drawn -- which is the least informative failure an app can have.
 *
 * So it is caught. The map cannot work afterwards, and [failure] says so, but the app
 * still starts, still shows the reason, and can still share it.
 */
object MapEngine {

    @Volatile
    private var initialised = false

    /** Why the map engine is unusable, or null when it started normally. */
    @Volatile
    var failure: Throwable? = null
        private set

    val isReady: Boolean get() = initialised && failure == null

    @Synchronized
    fun start(context: Context) {
        if (initialised) return
        initialised = true
        runCatching { MapLibre.getInstance(context.applicationContext) }
            .onFailure {
                failure = it
                CrashLog.record(context, "MapLibre n'a pas pu démarrer", it)
            }
    }
}
