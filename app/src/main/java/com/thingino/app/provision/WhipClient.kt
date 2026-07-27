package com.thingino.app.provision

import android.net.Network
import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * WHIP signalling for the live preview, done from Kotlin rather than from the
 * page's own fetch().
 *
 * The page could talk to rwd directly, but two things make that fragile. The
 * portal certificate is self-signed, and WebView's onReceivedSslError override
 * is only dependable for the main document, not for a subresource or XHR. And
 * a page served from the app's asset origin is cross-origin to the camera, so
 * every exchange would ride on rwd's CORS policy.
 *
 * Doing it here sidesteps both: the request is an ordinary HttpURLConnection
 * bound to the portal network, with the same relaxed trust PortalClient
 * already uses. Media is untouched by this; DTLS-SRTP flows directly between
 * WebView's WebRTC stack and the camera over UDP.
 */
class WhipClient(
    private val network: Network,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val user: String = DEFAULT_USER,
    private val pass: String = DEFAULT_PASS,
) {

    /**
     * The WHIP resource rwd created for the current session, from the Location
     * header of the 201. Held so the session can be deleted afterwards, which is
     * the only thing that frees it: rwd reaps sessions that never completed ICE,
     * but one that connected and was abandoned holds a client slot, and there
     * are four.
     */
    @Volatile
    private var resource: String? = null

    /** Posts the offer, returns the answer SDP. Blocking; call off the main thread. */
    fun exchange(offerSdp: String): String {
        close()
        val conn = open(URL("https://$host:$port/whip"))
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/sdp")
            conn.doOutput = true

            val body = offerSdp.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                // rwd answers 500 when it cannot work out its own address, which
                // is worth surfacing verbatim rather than as a generic failure.
                throw PortalException("The camera refused the preview (HTTP $code). ${text.trim()}")
            }
            resource = conn.getHeaderField("Location")
            text
        } catch (e: IOException) {
            throw PortalException("Could not reach the camera preview. ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Deletes the session, if one is open. Failures are swallowed rather than
     * reported: by this point the screen is going away, and in the common case
     * the camera is rebooting onto its configured network, which frees
     * everything anyway. Worth attempting regardless, because a camera that is
     * not rebooting keeps the slot until rwd restarts.
     */
    fun close() {
        val path = resource ?: return
        resource = null
        try {
            // Shorter than the offer's: teardown runs while the screen is going
            // away, and the camera is one hop off a link we are still holding
            // open for it.
            val conn = open(URL(URL("https://$host:$port/"), path), TEARDOWN_TIMEOUT_MS)
            try {
                conn.requestMethod = "DELETE"
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // Nothing useful to do with it here.
        }
    }

    private fun open(url: URL, timeoutMs: Int = 0): HttpURLConnection {
        val conn = network.openConnection(url) as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = sslContext.socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        conn.connectTimeout = if (timeoutMs > 0) timeoutMs else CONNECT_TIMEOUT_MS
        conn.readTimeout = if (timeoutMs > 0) timeoutMs else READ_TIMEOUT_MS
        conn.useCaches = false
        conn.setRequestProperty("Authorization", basicAuth())
        return conn
    }

    private fun basicAuth(): String {
        val raw = "$user:$pass".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private val sslContext: SSLContext by lazy {
        val trustAny = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, t: String?) = Unit
            override fun checkServerTrusted(c: Array<X509Certificate>?, t: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAny), null) }
    }

    companion object {
        const val DEFAULT_HOST = "172.16.0.1"
        const val DEFAULT_PORT = 8554
        const val DEFAULT_USER = "thingino"
        const val DEFAULT_PASS = "thingino"

        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val TEARDOWN_TIMEOUT_MS = 2_000
    }
}
