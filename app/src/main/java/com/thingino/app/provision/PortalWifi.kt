package com.thingino.app.provision

import android.os.Build
import androidx.annotation.RequiresApi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PortalException(message: String) : Exception(message)

/**
 * Joins an unprovisioned camera's captive-portal access point.
 *
 * We never scan. A prefix [WifiNetworkSpecifier] hands the whole job to the
 * system dialog, which enumerates every matching AP, asks the user to pick one,
 * and joins it. That avoids ACCESS_FINE_LOCATION, the location master toggle,
 * and the four-scans-per-two-minutes throttle that applies to getScanResults().
 *
 * The resulting network is local-only and carries no upstream, so every request
 * has to be issued through the [Network] handed back by [join]. Anything using
 * the process default routes over mobile data instead and times out.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PortalWifi(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun join(timeoutMs: Int = JOIN_TIMEOUT_MS): Network =
        suspendCancellableCoroutine { cont ->
            release()

            val specifier = WifiNetworkSpecifier.Builder()
                .setSsidPattern(PatternMatcher(SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
                // The portal AP ships key_mgmt=NONE, so there is no passphrase to
                // set here. See package/wifi/files/wpa_supplicant.conf.
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // The builder demands NET_CAPABILITY_INTERNET by default. The
                // camera has no upstream, so leaving it on means the request
                // never matches and the user just sees a dialog that never
                // resolves.
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (cont.isActive) cont.resume(network)
                }

                override fun onUnavailable() {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            PortalException(
                                "No ${SSID_PREFIX}* access point was joined. Check the " +
                                    "camera is powered and still inside its 10 minute " +
                                    "portal window."
                            )
                        )
                    }
                }
            }

            callback = cb
            cm.requestNetwork(request, cb, timeoutMs)
            cont.invokeOnCancellation { release() }
        }

    /**
     * Drops the request so the handset returns to its normal network. Not
     * optional: while the request is held the phone stays bound to an AP with no
     * upstream, and after `action=save` the camera reboots and the AP vanishes.
     */
    fun release() {
        callback?.let { cb -> runCatching { cm.unregisterNetworkCallback(cb) } }
        callback = null
    }

    companion object {
        /** SSID is "THINGINO-" plus the last two octets of the AP MAC. */
        const val SSID_PREFIX = "THINGINO-"

        private const val JOIN_TIMEOUT_MS = 60_000
    }
}
