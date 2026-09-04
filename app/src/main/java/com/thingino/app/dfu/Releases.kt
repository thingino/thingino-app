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
 * thingino-firmware tags its releases `<branch>-<date>`. The picker offers two
 * branches, the same two the web flasher's menu offers (the owner's choice,
 * 2026-09-03): `master`, whose builds are the `master-<date>` tags (marked
 * prerelease on GitHub), and `ciao`, the branch the stable line is built from,
 * whose builds are the `firmware-<date>` tags. Every other tag (`ccache`,
 * `update_cache`, other branches) carries nothing a user should flash and is
 * hidden. The web keeps the same rules in `web/src/releases.js`; the two agree.
 *
 * Uses HttpURLConnection and org.json, both in the platform, so it adds no
 * dependency. Every call suspends onto Dispatchers.IO.
 */
object Releases {
    private const val REPO = "themactep/thingino-firmware"
    // 100 is GitHub's page maximum. The master line publishes often, and a page
    // of 30 can be master builds alone, hiding every stable release.
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=100"
    private const val DL = "https://github.com/$REPO/releases/download"

    /** A menu branch: [name] is what the menu shows, [prefix] the tag prefix its builds carry. */
    data class Branch(val name: String, val prefix: String)

    /** The branches offered, in menu order. */
    val BRANCHES = listOf(Branch("master", "master"), Branch("ciao", "firmware"))

    /** Chosen until the user picks another. */
    const val DEFAULT_BRANCH = "master"

    /** `<branch>-<date>`: `firmware-2026-08-14`, `master-2026-08-25`. */
    private val FIRMWARE_TAG = Regex("^([a-z][a-z0-9_.-]*?)-(\\d{4}-\\d{2}-\\d{2})$")

    /**
     * The menu branch a tag belongs to: `master-2026-08-25` -> "master",
     * `firmware-2026-08-14` -> "ciao", anything else -> null.
     */
    fun menuBranchOf(tag: String): String? {
        val prefix = FIRMWARE_TAG.matchEntire(tag)?.groupValues?.get(1) ?: return null
        return BRANCHES.firstOrNull { it.prefix == prefix }?.name
    }

    /** The date half of a tag, what the menu shows once the branch is chosen: `master-2026-08-25` -> `2026-08-25`. */
    fun dateOf(tag: String): String = FIRMWARE_TAG.matchEntire(tag)?.groupValues?.get(2) ?: tag

    data class Release(val tag: String, val prerelease: Boolean, val assets: List<String>) {
        /** The menu branch this build belongs to. */
        val branch: String get() = menuBranchOf(tag) ?: ""

        /** What the picker shows: the date, and "(prerelease)" when GitHub marks the release so, as every master build is. */
        val label: String get() = dateOf(tag) + if (prerelease) " (prerelease)" else ""
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            // github.com 302s to a signed blob host; both are https, so this follows.
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "thingino-dfu")
        }

    /** The menu branches' releases, newest first (the API lists newest first); drafts and every other tag are dropped. */
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
                if (r.optBoolean("draft") || menuBranchOf(tag) == null) continue
                val assets = r.optJSONArray("assets") ?: continue
                val names = ArrayList<String>(assets.length())
                for (j in 0 until assets.length()) names.add(assets.getJSONObject(j).optString("name"))
                out.add(Release(tag, r.optBoolean("prerelease"), names))
            }
            out
        } finally {
            conn.disconnect()
        }
    }

    /** [releases] grouped by menu branch, in [BRANCHES] order, branches without builds omitted. */
    fun byBranch(releases: List<Release>): Map<String, List<Release>> =
        BRANCHES.map { it.name }
            .associateWith { name -> releases.filter { it.branch == name } }
            .filterValues { it.isNotEmpty() }

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
