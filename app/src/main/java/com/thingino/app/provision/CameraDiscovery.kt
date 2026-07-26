package com.thingino.app.provision

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Collections
import kotlin.coroutines.resume

data class DiscoveredCamera(
    val serviceName: String,
    /** Hostname parsed out of the advertised label, e.g. ing-wyze-campan2-2540. */
    val hostname: String,
    val address: String,
    val port: Int,
    val serviceType: String,
)

/**
 * Finds provisioned cameras on the local network over mDNS.
 *
 * The firmware runs troglobit mdnsd, and overlay/etc/init.d/S50mdnsd regenerates
 * the service files under /etc/mdns.d on every start with a fixed label:
 *
 *     name Thingino Web UI (ing-wyze-campan2-2540)
 *     type _http._tcp
 *     port 80
 *     target ing-wyze-campan2-2540.local
 *
 * Matching on that label prefix enumerates every thingino camera on the segment
 * with no prior knowledge of hostname, board or MAC. That matters because the
 * hostname suffix is the last four digits of the eFuse chip serial while the
 * portal SSID suffix is the last two octets of the wifi MAC, so one cannot be
 * derived from the other.
 *
 * Run this only after the phone is back on the normal network. NsdManager did
 * not accept a Network argument until API 33, so on 29 through 32 it follows the
 * process default and would find nothing while parked on the portal AP.
 */
class CameraDiscovery(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    /** NsdManager rejects a second concurrent resolve with FAILURE_ALREADY_ACTIVE. */
    private val resolveLock = Mutex()

    suspend fun browse(
        serviceType: String = THINGINO_SERVICE,
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS,
    ): List<DiscoveredCamera> {
        val found = Collections.synchronizedList(mutableListOf<NsdServiceInfo>())

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // No name filter: the type is ours, so anything answering on it
                // is a camera. Filtering by name is impossible anyway, since the
                // instance name mdnsd publishes is the user-settable hostname.
                found.add(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.removeAll { it.serviceName == info.serviceName }
            }
        }

        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            delay(timeoutMs)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }

        val unique = found.toList().distinctBy { it.serviceName }
        return unique.mapNotNull { resolve(it, serviceType) }
    }

    private suspend fun resolve(info: NsdServiceInfo, serviceType: String): DiscoveredCamera? =
        resolveLock.withLock {
            suspendCancellableCoroutine { cont ->
                @Suppress("DEPRECATION")
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(failed: NsdServiceInfo, code: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (!cont.isActive) return
                        @Suppress("DEPRECATION")
                        val address = resolved.host?.hostAddress
                        if (address == null) {
                            cont.resume(null)
                            return
                        }
                        cont.resume(
                            DiscoveredCamera(
                                serviceName = resolved.serviceName,
                                hostname = hostnameFrom(resolved.serviceName),
                                address = address,
                                port = resolved.port,
                                serviceType = serviceType,
                            )
                        )
                    }
                }

                @Suppress("DEPRECATION")
                nsd.resolveService(info, listener)
            }
        }

    /** The published instance name is the hostname. */
    private fun hostnameFrom(serviceName: String): String = serviceName

    companion object {
        /**
         * A type of our own. `_http._tcp` is unusable for this: on a normal
         * home network it returns dozens of unrelated devices, and the only
         * thing distinguishing a camera would be its instance name, which is
         * the hostname and therefore whatever the user chose.
         */
        const val THINGINO_SERVICE = "_thingino._tcp"
        const val HTTP_SERVICE = "_http._tcp"
        const val HTTPS_SERVICE = "_https._tcp"
        const val RTSP_SERVICE = "_rtsp._tcp"
        const val RTSPS_SERVICE = "_rtsps._tcp"

        private const val DISCOVERY_TIMEOUT_MS = 6_000L
    }
}
