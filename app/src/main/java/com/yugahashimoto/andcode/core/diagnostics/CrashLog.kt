package com.yugahashimoto.andcode.core.diagnostics

import android.content.Context
import android.os.Build
import com.yugahashimoto.andcode.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash so it can be read back on the next launch.
 *
 * A phone-only user has no logcat, so a crash that happens away from a desk is otherwise
 * invisible. The record is written from the uncaught-exception handler, which runs while the
 * process is already dying - so it stays a single small synchronous file write, and the platform
 * handler still runs afterwards to crash the app the way it normally would.
 */
object CrashLog {
    /** Registers the handler. Call first in Application.onCreate so startup crashes are caught. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last recorded crash, or null when the app has not crashed since the record was cleared. */
    fun read(context: Context): String? =
        runCatching {
            file(context).takeIf(File::exists)?.readText()?.takeIf(String::isNotBlank)
        }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun write(
        context: Context,
        thread: Thread,
        error: Throwable,
    ) {
        val stackTrace =
            StringWriter().also { writer ->
                PrintWriter(writer).use(error::printStackTrace)
            }.toString()
        val report =
            buildString {
                appendLine("AndCode crash")
                appendLine("Time: ${TIMESTAMP.format(Date())}")
                appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Thread: ${thread.name}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Heap: ${heapUsage()}")
                appendLine()
                append(stackTrace.take(MAX_TRACE_CHARS))
            }
        val target = file(context)
        target.parentFile?.mkdirs()
        target.writeText(report)
    }

    private fun heapUsage(): String {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / MEGABYTE
        val max = runtime.maxMemory() / MEGABYTE
        return "$used MB used of $max MB"
    }

    private fun file(context: Context): File = File(File(context.filesDir, "diagnostics"), "last-crash.txt")

    private const val MEGABYTE = 1024L * 1024L

    private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private const val MAX_TRACE_CHARS = 12_000
}
