package com.awaken.app.update

import com.awaken.app.BuildConfig
import com.awaken.core.update.Hashing
import com.awaken.core.update.SecureUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Converts org.json into plain Kotlin maps/lists so the pure-Kotlin core can validate it. */
object JsonConvert {
    fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { toValue(o.get(it)) }
    fun toList(a: JSONArray): List<Any?> = (0 until a.length()).map { toValue(a.get(it)) }
    private fun toValue(v: Any?): Any? = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> toList(v)
        JSONObject.NULL -> null
        else -> v
    }
}

/** HTTPS-only client. Redirects are followed manually and every hop must also pass [SecureUrl]. */
object SecureHttp {
    private const val CONNECT_MS = 15_000
    private const val READ_MS = 20_000
    private const val MAX_REDIRECTS = 4
    private val UA = "AWAKEN/${BuildConfig.VERSION_NAME}"

    class HttpFailure(message: String, val retriable: Boolean) : IOException(message)

    private fun open(start: String): HttpURLConnection {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            if (!SecureUrl.isAllowed(url)) throw HttpFailure("Blocked: only public HTTPS addresses are allowed", false)
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = try { c.responseCode } catch (e: IOException) { c.disconnect(); throw HttpFailure("Could not reach the server (${e.message ?: "network error"})", true) }
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = c.getHeaderField("Location") ?: throw HttpFailure("Redirect without a location", false)
                c.disconnect()
                url = URL(URL(url), loc).toString()
                return@repeat
            }
            if (code !in 200..299) { c.disconnect(); throw HttpFailure("Server answered HTTP $code", code >= 500) }
            return c
        }
        throw HttpFailure("Too many redirects", false)
    }

    fun getText(url: String, maxBytes: Int = 256 * 1024): String {
        val c = open(url)
        try {
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw HttpFailure("Response is unexpectedly large", false)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            c.disconnect()
        }
    }

    /**
     * Streams [url] to [dest] and returns its SHA-256. Aborts if the body exceeds [expectedSize], so a
     * hostile server cannot fill the disk. [onProgress] receives bytes written so far.
     */
    fun download(url: String, dest: File, expectedSize: Long, onProgress: (Long) -> Unit): String {
        val c = open(url)
        val md = MessageDigest.getInstance("SHA-256")
        try {
            dest.parentFile?.mkdirs()
            var total = 0L
            c.inputStream.use { ins ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > expectedSize) throw HttpFailure("Download is larger than the manifest says", false)
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                        if (total - lastReport > 128 * 1024) { lastReport = total; onProgress(total) }
                    }
                }
            }
            onProgress(total)
            return md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            dest.delete()
            if (e is HttpFailure) throw e
            throw HttpFailure("Download interrupted (${e.message ?: "network error"})", true)
        } finally {
            c.disconnect()
        }
    }

    @Suppress("unused")
    fun sha256(bytes: ByteArray) = Hashing.sha256Hex(bytes)
}
