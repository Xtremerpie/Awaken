package com.awaken.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small rolling error log kept on-device (never uploaded). Shown in History › Diagnostics. */
object AppLog {
    private const val TAG = "AWAKEN"
    private const val MAX_BYTES = 192 * 1024
    private var file: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "awaken.log")
    }

    @Synchronized
    private fun write(level: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(fmt.format(Date())).append(' ').append(level).append(' ').append(msg)
            if (t != null) append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
            append('\n')
        }
        val f = file ?: return
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                val tail = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(tail.substringAfter('\n', tail))
            }
            f.appendText(line)
        } catch (_: Exception) {
            // Logging must never crash the app.
        }
    }

    fun i(msg: String) { Log.i(TAG, msg); write("I", msg, null) }
    fun w(msg: String, t: Throwable? = null) { Log.w(TAG, msg, t); write("W", msg, t) }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t); write("E", msg, t) }

    @Synchronized
    fun read(): String = try { file?.takeIf { it.exists() }?.readText().orEmpty() } catch (_: Exception) { "" }

    @Synchronized
    fun clear() { try { file?.delete() } catch (_: Exception) { } }
}
