package com.thingino.app.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors taken from the camera and from openssl, not from this code, so a
 * regression here means the app and the firmware have actually diverged.
 */
class PortalCryptoTest {

    // wpa_passphrase alpha hunter2pass, run on ing-wyze-cam2-8248
    @Test
    fun pskMatchesWpaPassphrase() {
        assertEquals(
            "df52a5861be3778595e04187afce8a8cc57c02cebf3d376d9200a11f0c4efbd3",
            PortalCrypto.wpaPsk("alpha", "hunter2pass"),
        )
    }

    // RFC 4231-style check that the length loop is right, not just the happy path.
    @Test
    fun pskIs64HexChars() {
        val psk = PortalCrypto.wpaPsk("some-network", "a-much-longer-passphrase-here")
        assertEquals(64, psk.length)
        assertTrue(psk.matches(Regex("[0-9a-f]{64}")))
    }

    // busybox mkpasswd -m sha512 -S abcd1234 on the camera == openssl passwd -6
    @Test
    fun cryptMatchesBusyboxAndOpenssl() {
        assertEquals(
            "\$6\$abcd1234\$dz8ghS3K0O52ub00amJPLSLtZi5zfLfMp969po5DKtSh9w5pMrgiBXBD1TPbeXOGuyLFLUpfBMiE1vTWpfqpa/",
            PortalCrypto.sha512Crypt("hunter2pass", "abcd1234"),
        )
    }

    // The firmware only accepts $6$; anything else is rejected by api.cgi.
    @Test
    fun generatedCryptIsSha512AndSalted() {
        val a = PortalCrypto.sha512Crypt("hunter2pass")
        val b = PortalCrypto.sha512Crypt("hunter2pass")
        assertTrue(a.startsWith("\$6\$"))
        assertTrue("salt must be random", a != b)
    }
}
