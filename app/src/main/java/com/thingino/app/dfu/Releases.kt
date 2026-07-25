package com.thingino.app.dfu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Prebuilt thingino firmware releases, fetched straight from GitHub.
 *
 * The web flasher cannot do this: GitHub serves release assets from a blob host
 * that sends no Access-Control-Allow-Origin, so a browser refuses to read the
 * bytes, and the web build has to route them through a Cloudflare Worker that
 * adds the header. CORS is a browser rule, not an HTTP one - a native client is
 * under no such restriction, so this talks to GitHub directly and needs no proxy.
 *
 * Uses HttpURLConnection and org.json, both in the platform, so it adds no
 * dependency. Every call suspends onto Dispatchers.IO.
 */
object Releases {
    private const val REPO = "themactep/thingino-firmware"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"
    private const val DL = "https://github.com/$REPO/releases/download"

    data class Release(val tag: String, val assets: List<String>) {
        /** "firmware-2026-06-22" -> "2026-06-22", which is what the picker shows. */
        val label: String get() = tag.removePrefix("firmware-")
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            // github.com 302s to a signed blob host; both are https, so this follows.
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "thingino-dfu")
        }

    /** The official firmware-<date> releases, newest first. */
    suspend fun list(): List<Release> = withContext(Dispatchers.IO) {
        val conn = open(API)
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            val out = mutableListOf<Release>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val tag = r.optString("tag_name")
                if (r.optBoolean("draft") || !tag.startsWith("firmware-")) continue
                val assets = r.optJSONArray("assets") ?: continue
                val names = ArrayList<String>(assets.length())
                for (j in 0 until assets.length()) names.add(assets.getJSONObject(j).optString("name"))
                out.add(Release(tag, names))
            }
            out
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The images in a release. The SoC is part of the asset name
     * (thingino-<vendor>_<model>_<soc>_<sensor>_<wifi>.bin), so a detected SoC
     * narrows ~160 images down to the handful that fit this board. [soc] is the
     * lowercase variant name, e.g. "t31x".
     */
    fun imagesFor(release: Release, soc: String, all: Boolean): List<String> {
        val bins = release.assets.filter { it.endsWith(".bin") }
        if (all || soc.isEmpty()) return bins
        val re = Regex("_${Regex.escape(soc.lowercase())}[._]") // _t31x_ or _t31x.
        return bins.filter { re.containsMatchIn(it.lowercase()) }
    }

    /** Stream an asset to [dest]. onProgress reports (percent, got, total); percent is -1 if unknown. */
    suspend fun download(tag: String, name: String, dest: File, onProgress: (Int, Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val conn = open("$DL/$tag/$name")
            try {
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(65536)
                        var got = 0L
                        var lastPct = -2
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val pct = if (total > 0) (got * 100 / total).toInt() else -1
                            // Only report on change: a 16 MB image is ~256 reads, and
                            // the UI has nothing to do with a repeated identical value.
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct, got, total)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

    /**
     * The SHA-256 published alongside an image, or null if there is none (in which
     * case there is nothing to check against, and the caller should say so rather
     * than pretend the image was verified).
     */
    suspend fun publishedSha256(tag: String, name: String): String? = withContext(Dispatchers.IO) {
        val conn = open("$DL/$tag/$name.sha256sum")
        try {
            if (conn.responseCode !in 200..299) return@withContext null
            // thingino's .sha256sum opens with '#' comment lines, then the usual
            // "<hash>  <file>" line. Skip the comments, take the first 64-hex token.
            conn.inputStream.bufferedReader().readText().lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { Regex("^[0-9a-fA-F]{64}").find(it)?.value?.lowercase() }
                .firstOrNull()
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** SHA-256 of a file, lowercase hex. */
    suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}
