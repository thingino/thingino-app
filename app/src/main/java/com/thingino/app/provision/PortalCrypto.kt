package com.thingino.app.provision

import org.apache.commons.codec.digest.Sha2Crypt
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives the forms the camera can consume directly, so neither the Wi-Fi
 * passphrase nor the root password ever leaves the phone in the clear.
 *
 * Both outputs were checked against the camera's own tools before this was
 * written: the PSK against `wpa_passphrase`, and the crypt string against
 * busybox `mkpasswd -m sha512` and `openssl passwd -6`. See PortalCryptoTest.
 */
object PortalCrypto {

    /**
     * WPA-PSK: PBKDF2-HMAC-SHA1(passphrase, ssid, 4096, 32 bytes), hex encoded.
     *
     * Implemented directly rather than through `PBKDF2WithHmacSHA1`, because
     * that takes a char[] and leaves the byte encoding to the provider. WPA
     * defines the passphrase as bytes, so doing the HMAC by hand keeps a
     * non-ASCII passphrase from silently deriving a different key than the
     * camera would.
     */
    fun wpaPsk(ssid: String, passphrase: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val salt = ssid.toByteArray(Charsets.UTF_8)

        val out = ByteArray(PSK_BYTES)
        var offset = 0
        var block = 1
        while (offset < PSK_BYTES) {
            // U1 = PRF(salt || INT(block))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            // U2..Uc, folded in
            for (i in 1 until PSK_ITERATIONS) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val take = minOf(t.size, PSK_BYTES - offset)
            t.copyInto(out, offset, 0, take)
            offset += take
            block++
        }
        return out.joinToString("") { "%02x".format(it) }
    }

    /**
     * SHA-512 crypt, the `$6$` form busybox `chpasswd -e` accepts.
     *
     * Uses Commons Codec rather than a local implementation. SHA-crypt has an
     * awkward byte-permutation step at the end that is easy to get subtly
     * wrong, and a wrong hash means a camera nobody can log into.
     */
    fun sha512Crypt(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val chars = salt.joinToString("") { SALT_ALPHABET[(it.toInt() and 0xff) % SALT_ALPHABET.length].toString() }
        return Sha2Crypt.sha512Crypt(password.toByteArray(Charsets.UTF_8), "\$6\$$chars")
    }

    /** Visible for tests: crypt with a caller-supplied salt. */
    fun sha512Crypt(password: String, salt: String): String =
        Sha2Crypt.sha512Crypt(password.toByteArray(Charsets.UTF_8), "\$6\$$salt")

    private const val PSK_ITERATIONS = 4096
    private const val PSK_BYTES = 32
    private const val SALT_BYTES = 12
    private const val SALT_ALPHABET =
        "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
}
