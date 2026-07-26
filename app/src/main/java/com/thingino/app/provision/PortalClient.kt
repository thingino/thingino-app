package com.thingino.app.provision

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class CameraInfo(
    val hostname: String,
    val imageId: String,
    val buildId: String,
    val wlanMac: String,
    /** What this firmware accepts. Absent on cameras predating the field. */
    val features: List<String> = emptyList(),
) {
    val acceptsHashedSecrets: Boolean
        get() = PortalClient.FEATURE_PSK in features &&
            PortalClient.FEATURE_ROOT_HASH in features
}

data class ScannedNetwork(
    val ssid: String,
    val bssid: String,
    val signal: Int,
    val security: String,
) {
    val isOpen: Boolean get() = security.equals("Open", ignoreCase = true)
}

data class ProvisionRequest(
    val hostname: String,
    val wlanSsid: String,
    val wlanPass: String,
    val rootPass: String,
    val timezone: String,
    val pubkey: String = "",
    val apMode: Boolean = false,
)

/**
 * The camera's portal API, which is the whole of package/wifi/files/api.cgi:
 * three actions, no authentication, plain HTTP.
 *
 * Every request goes through [network].openConnection so it is pinned to the
 * local-only wifi network from [PortalWifi]. We address the camera by IP
 * literal rather than by name: the portal runs a wildcard dnsd that answers
 * every lookup with its own address, and skipping DNS entirely keeps that out
 * of the picture.
 */
class PortalClient(
    private val network: Network,
    private val host: String = PORTAL_HOST,
) {

    suspend fun getInfo(): CameraInfo = withContext(Dispatchers.IO) {
        val json = JSONObject(get("action=get_info"))
        json.failIfError()
        CameraInfo(
            hostname = json.optString("hostname"),
            imageId = json.optString("image_id"),
            buildId = json.optString("build_id"),
            wlanMac = json.optString("wlan_mac"),
            features = json.optJSONArray("features")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            }.orEmpty(),
        )
    }

    /**
     * Networks the *camera* can hear, which is the list that actually matters.
     *
     * Treat failure as routine rather than exceptional. api.cgi runs
     * `wpa_cli -i wlan0`, but package/wifi/wifi.mk gives some chipset families
     * an AP netdev of ap0 or wlan1, and asking a cheap SDIO part to scan while
     * it is beaconing is unreliable regardless. Callers must keep a manual SSID
     * entry path available.
     */
    suspend fun scanNetworks(): List<ScannedNetwork> = withContext(Dispatchers.IO) {
        val json = try {
            JSONObject(get("action=scan_networks"))
        } catch (e: Exception) {
            return@withContext emptyList()
        }
        if (json.has("error")) return@withContext emptyList()

        val arr = json.optJSONArray("networks") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ssid = o.optString("ssid")
            if (ssid.isEmpty()) return@mapNotNull null
            ScannedNetwork(
                ssid = ssid,
                bssid = o.optString("bssid"),
                signal = o.optInt("signal"),
                security = o.optString("security"),
            )
        }.distinctBy { it.ssid }.sortedByDescending { it.signal }
    }

    /**
     * Writes the configuration and reboots the camera two seconds later.
     *
     * The success flag means the files were written, not that the credentials
     * work. A wrong passphrase produces exactly the same response, and the
     * camera comes back up in portal mode with nothing to report. Verify by
     * looking for the camera on the target network afterwards, or by checking
     * whether its THINGINO-* AP reappeared.
     */
    suspend fun save(req: ProvisionRequest, hashed: Boolean): Unit = withContext(Dispatchers.IO) {
        val body = buildString {
            append("hostname=").append(enc(req.hostname))
            append("&wlan_ssid=").append(enc(req.wlanSsid))
            // Derive locally when the camera says it can take the derived
            // forms, so neither secret is on the wire in a reusable shape.
            // Only when advertised: older firmware ignores unknown fields and
            // would configure Wi-Fi with an empty passphrase while still
            // reporting success.
            if (hashed) {
                append("&wlan_psk=")
                    .append(enc(PortalCrypto.wpaPsk(req.wlanSsid, req.wlanPass)))
                append("&rootpass_hash=")
                    .append(enc(PortalCrypto.sha512Crypt(req.rootPass)))
            } else {
                append("&wlan_pass=").append(enc(req.wlanPass))
                append("&rootpass=").append(enc(req.rootPass))
            }
            append("&timezone=").append(enc(req.timezone))
            append("&rootpkey=").append(enc(req.pubkey))
            if (req.apMode) append("&wlan_ap=true")
        }

        val text = post("action=save", body)
        val json = JSONObject(text)
        if (!json.optBoolean("success", false)) {
            throw PortalException(json.optString("error", "Camera rejected the configuration."))
        }
    }

    private fun get(query: String): String = request("GET", query, null)

    private fun post(query: String, body: String): String = request("POST", query, body)

    /**
     * Prefers HTTPS and falls back to plain HTTP.
     *
     * The portal serves both: 80 because captive-portal detection is HTTP by
     * definition, 443 for clients like this one that address it directly.
     * Older firmware serves only 80, so a failure on 443 is expected rather
     * than exceptional, and the chosen scheme is remembered for the session.
     */
    private fun request(method: String, query: String, body: String?): String {
        schemes().forEach { scheme ->
            try {
                val text = attempt(scheme, method, query, body)
                secure = scheme == "https"
                chosen = scheme
                return text
            } catch (e: IOException) {
                // Only worth falling back on transport failures. An HTTP status
                // from the camera means it answered, so trying the other port
                // would just repeat the same rejection.
                if (scheme == "http") {
                    throw PortalException("Could not reach the camera at $host: ${e.message}")
                }
            }
        }
        throw PortalException("Could not reach the camera at $host")
    }

    private fun schemes(): List<String> =
        chosen?.let { listOf(it) } ?: listOf("https", "http")

    private fun attempt(scheme: String, method: String, query: String, body: String?): String {
        // action is read from QUERY_STRING even on POST, where the body carries
        // only the form fields. See the parse_query call at the foot of api.cgi.
        val url = URL("$scheme://$host$API_PATH?$query")
        val conn = network.openConnection(url) as HttpURLConnection
        if (conn is HttpsURLConnection) relaxTls(conn)
        return try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.useCaches = false
            conn.instanceFollowRedirects = false

            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw PortalException("Camera returned HTTP $code for $query")
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The portal's certificate is self-signed and issued for <hostname>.local
     * while we connect by IP, so neither the chain nor the name can validate.
     * Accepting it anyway still defeats passive capture on the open access
     * point, which is the threat here; it does not defend against an active
     * attacker running a rogue THINGINO access point.
     *
     * Scoped to this connection. Never set as the JVM default, or it would
     * also disable validation for the release downloads in the flashing flow.
     */
    private fun relaxTls(conn: HttpsURLConnection) {
        conn.sslSocketFactory = portalSslContext.socketFactory
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
    }

    private fun JSONObject.failIfError() {
        if (has("error")) throw PortalException(optString("error"))
    }

    // api.cgi decodes with `sed 's/+/ /g; s/%XX/\xXX/g'` piped through
    // `printf '%b'`, so standard form encoding round-trips. The one rough edge
    // is that printf %b also expands backslash escapes, so a passphrase
    // containing a literal backslash sequence can arrive altered.
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private var chosen: String? = null

    /** True once a request has completed over TLS. */
    var secure: Boolean = false
        private set

    private val portalSslContext: SSLContext by lazy {
        val trustAny = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, t: String?) = Unit
            override fun checkServerTrusted(c: Array<X509Certificate>?, t: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAny), null) }
    }

    companion object {
        const val FEATURE_PSK = "wlan_psk"
        const val FEATURE_ROOT_HASH = "rootpass_hash"

        /** Portal mode. The camera's own AP mode uses 100.64.1.1 instead. */
        const val PORTAL_HOST = "172.16.0.1"
        const val AP_MODE_HOST = "100.64.1.1"

        private const val API_PATH = "/x/api.cgi"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 20_000
    }
}
