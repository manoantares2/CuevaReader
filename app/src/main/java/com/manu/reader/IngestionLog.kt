package com.manu.reader

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.time.Instant

class IngestionException(val stage: String, cause: Throwable) : Exception("$stage: ${cause.message}", cause)

// Kept in app-private storage so failures on testers' phones can be read later with `adb shell run-as`.
object IngestionLog {
    private const val TAG = "Ingestion"
    private const val FILE = "ingestion.log"
    private const val MAX_BYTES = 256 * 1024

    fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun error(context: Context, action: String, source: Uri?, error: Throwable) {
        val stage = (error as? IngestionException)?.stage ?: "unknown"
        val root = generateSequence(error) { it.cause }.last()
        val entry = buildString {
            append(Instant.now()).append(" ERROR ").append(action).append('\n')
            append("  stage: ").append(stage).append('\n')
            append("  source: ").append(source).append('\n')
            append("  cause: ").append(root.javaClass.name).append(": ").append(root.message).append('\n')
            root.stackTrace.take(8).forEach { append("    at ").append(it).append('\n') }
        }
        Log.e(TAG, entry, error)
        append(context, entry)
    }

    @Synchronized
    fun warn(context: Context, action: String, source: Uri?, message: String) {
        val entry = "${Instant.now()} WARN $action\n  source: $source\n  $message\n"
        Log.w(TAG, entry)
        append(context, entry)
    }

    private fun append(context: Context, entry: String) {
        runCatching {
            val log = file(context)
            if (log.length() > MAX_BYTES) log.writeText(log.readText().takeLast(MAX_BYTES / 2))
            log.appendText(entry)
        }
    }
}
