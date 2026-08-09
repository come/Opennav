/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav

import android.app.Application
import org.opennav.chart.MapEngine
import org.opennav.diag.CrashLog

class OpennavApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything else, so that a failure in the very next line is recorded rather
        // than only reaching a logcat nobody at sea is attached to.
        CrashLog.install(this)

        // Must run before any MapView is constructed. No API key and no well-known tile
        // server: every byte this app renders comes off local storage.
        MapEngine.start(this)
    }
}
