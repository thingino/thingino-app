package com.thingino.app.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** What the save form carries, and when the app derives the secrets before sending them. */
class PortalFormTest {

    private val req = ProvisionRequest(
        hostname = "cam-1",
        wlanSsid = "alpha",
        wlanPass = "hunter2pass",
        rootPass = "rootpw",
        timezone = "Europe/Lisbon",
        pubkey = "ssh-ed25519 AAAA x",
    )

    private fun fields(body: String): Map<String, String> =
        body.split('&').associate { pair ->
            val (name, value) = pair.split('=', limit = 2)
            name to URLDecoder.decode(value, "UTF-8")
        }

    @Test
    fun aCameraGetsEveryFieldAndTheDerivedSecretsItTakes() {
        val form = fields(PortalClient.formBody(req, hashed = true, wifiOnly = false))
        assertEquals(setOf("hostname", "wlan_ssid", "wlan_psk", "rootpass_hash", "timezone", "rootpkey"), form.keys)
        assertEquals(PortalCrypto.wpaPsk("alpha", "hunter2pass"), form["wlan_psk"])
        assertTrue(form.getValue("rootpass_hash").startsWith("\$6\$"))
        assertEquals("ssh-ed25519 AAAA x", form["rootpkey"])
    }

    @Test
    fun anOlderCameraGetsThePlainSecrets() {
        val form = fields(PortalClient.formBody(req.copy(apMode = true), hashed = false, wifiOnly = false))
        assertEquals("hunter2pass", form["wlan_pass"])
        assertEquals("rootpw", form["rootpass"])
        assertEquals("true", form["wlan_ap"])
    }

    // The dev backpack: the network, as a derived key, and the hostname. Nothing only a
    // camera takes, not even the fields the form still holds.
    @Test
    fun aWifiOnlyDeviceGetsTheNetworkAndTheHostnameAlone() {
        val form = fields(PortalClient.formBody(req.copy(apMode = true), hashed = true, wifiOnly = true))
        assertEquals(
            mapOf(
                "hostname" to "cam-1",
                "wlan_ssid" to "alpha",
                "wlan_psk" to PortalCrypto.wpaPsk("alpha", "hunter2pass"),
            ),
            form,
        )
    }

    @Test
    fun theKeyIsDerivedWhenTheDeviceSaysItTakesOne() {
        fun info(vararg features: String) = CameraInfo("h", "i", "b", "m", features.toList())
        assertTrue(info("wlan_psk", "rootpass_hash").acceptsHashedSecrets)
        assertTrue(info("wlan_psk", "rootpass_hash", "wifi_only").acceptsHashedSecrets)
        assertTrue(
            "a wifi-only device has no root hash to advertise",
            info("wlan_psk", "wifi_only").acceptsHashedSecrets,
        )
        assertFalse(info("wlan_psk").acceptsHashedSecrets)
        assertFalse(info().acceptsHashedSecrets)
        assertTrue(info("wlan_psk", "wifi_only").wifiOnly)
        assertFalse(info("wlan_psk", "rootpass_hash").wifiOnly)
    }
}
