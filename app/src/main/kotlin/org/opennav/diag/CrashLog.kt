/*
 * Copyright (C) 2026 the Opennav authors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 */
package org.opennav.diag

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash so it can be read on the phone that produced it.
 *
 * An app with no network and no crash reporting service loses every failure that happens
 * away from a cable. "It crashes straight away" is then the entire bug report, and the
 * only remaining tool is guesswork. This writes the stack trace to a file, shows it on
 * the next launch, and offers to share it as plain text through whatever the user already
 * has installed -- no INTERNET permission is involved, the receiving app does the sending.
 *
 * It also records failures that are caught rather than fatal, because the interesting
 * ones are usually those: something the app decided to survive but which left it useless.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val FILE_NAME = "last-crash.txt"

    /**
     * Installs the handler. Call first thing in `Application.onCreate`.
     *
     * Chains to the handler that was there before -- replacing it outright would stop the
     * process dying, leaving a frozen app on screen, which is worse than a crash and much
     * harder to describe.
     */
    fun install(context: Context) {
        val application = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(application, describe("plantage sur ${thread.name}", error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Records something that went wrong without killing the process.
     *
     * Appends: a failure that was survived and then followed by a second, different one is
     * a much better description of what happened than either alone.
     */
    fun record(context: Context, stage: String, error: Throwable) {
        Log.w(TAG, stage, error)
        runCatching {
            val existing = read(context)?.plus("\n\n----------------\n\n").orEmpty()
            write(context.applicationContext, existing + describe(stage, error))
        }
    }

    /** The last recorded failure, or null if this install has never had one. */
    fun read(context: Context): String? = runCatching {
        val file = file(context)
        if (file.isFile && file.length() > 0) file.readText() else null
    }.getOrNull()

    /**
     * A stable identifier for one report, so the app can tell "seen" from "new".
     *
     * Showing the dialog whenever a report exists sounds right and is not: sharing does
     * not delete the file, so the same crash reappeared at every launch, and a report
     * that comes back after it has been dealt with reads as the crash happening again.
     * That cost a round trip on a bug that had already been fixed.
     */
    fun identity(report: String): String = report.hashCode().toString()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * A share sheet carrying the report as text.
     *
     * Deliberately not a file attachment: a `.txt` through a `FileProvider` arrives as an
     * attachment that half the messaging apps then refuse to preview, and the whole point
     * is that the person receiving it can read it without opening anything.
     */
    fun shareIntent(report: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Opennav — rapport de plantage")
        putExtra(Intent.EXTRA_TEXT, report)
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun write(context: Context, text: String) {
        file(context).writeText(text)
    }

    /**
     * The trace plus the handful of facts that change what the trace means.
     *
     * Android version and manufacturer are here because the failures this app can hit --
     * OpenGL ES, scoped storage, permission behaviour -- are all version-dependent, and
     * asking afterwards costs a round trip that a crash report should have saved.
     */
    private fun describe(stage: String, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        return buildString {
            appendLine("Opennav $stage")
            appendLine(stamp)
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            append(trace)
        }
    }
}
