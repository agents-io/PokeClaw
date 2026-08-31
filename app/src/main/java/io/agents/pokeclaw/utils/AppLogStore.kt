// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils

import android.content.Context
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight rolling log store for support bundles.
 *
 * Android logcat access is inconsistent on user devices, so keep a small app-owned log
 * timeline inside cache that can be exported through the in-app debug report flow.
 */
object AppLogStore {

    private const val LOG_DIR = "app_logs"
    private const val ACTIVE_LOG = "pokeclaw-app.log"
    private const val PREVIOUS_LOG = "pokeclaw-app.prev.log"
    private const val MAX_LOG_BYTES = 512L * 1024L

    @Volatile
    private var appContext: Context? = null

    private val lock = Any()

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
        resolveLogDir(context)
    }

    @JvmStatic
    fun log(level: String, tag: String, message: String?, throwable: Throwable?) {
        val dir = resolveLogDir() ?: return
        val payload = buildEntry(level, tag, message, throwable).toByteArray(Charsets.UTF_8)

        synchronized(lock) {
            val activeFile = File(dir, ACTIVE_LOG)
            rotateIfNeeded(activeFile, payload.size.toLong())
            runCatching {
                FileOutputStream(activeFile, true).use { output ->
                    output.write(payload)
                    output.flush()
                }
            }
        }
    }

    @JvmStatic
    fun listLogFiles(context: Context): List<File> {
        val dir = resolveLogDir(context) ?: return emptyList()
        return listOf(
            File(dir, PREVIOUS_LOG),
            File(dir, ACTIVE_LOG),
        ).filter { it.exists() && it.isFile && it.length() > 0L }
    }

    private fun buildEntry(level: String, tag: String, message: String?, throwable: Throwable?): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val threadName = Thread.currentThread().name
        val pid = Process.myPid()
        val stackTrace = throwable?.let(::stackTraceString)

        return buildString {
            append(timestamp)
            append(" ")
            append(level)
            append("/")
            append(tag)
            append(" pid=")
            append(pid)
            append(" thread=")
            append(threadName)
            append(" ")
            append(message?.ifBlank { "(blank)" } ?: "(no message)")
            if (!stackTrace.isNullOrBlank()) {
                append("\n")
                append(stackTrace.trimEnd())
            }
            append("\n")
        }
    }

    private fun stackTraceString(throwable: Throwable): String {
        val buffer = StringWriter()
        PrintWriter(buffer).use { writer ->
            throwable.printStackTrace(writer)
        }
        return buffer.toString()
    }

    private fun rotateIfNeeded(activeFile: File, incomingBytes: Long) {
        if (activeFile.exists() && activeFile.length() + incomingBytes <= MAX_LOG_BYTES) {
            return
        }
        if (!activeFile.exists()) {
            return
        }

        val previousFile = File(activeFile.parentFile, PREVIOUS_LOG)
        if (previousFile.exists()) {
            previousFile.delete()
        }
        activeFile.renameTo(previousFile)
    }

    private fun resolveLogDir(context: Context? = appContext): File? {
        val cacheDir = context?.cacheDir ?: return null
        return File(cacheDir, LOG_DIR).apply { mkdirs() }
    }
}
