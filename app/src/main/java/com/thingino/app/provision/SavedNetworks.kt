package com.thingino.app.provision

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject

data class SavedNetwork(val ssid: String, val password: String)

/**
 * Networks the user has chosen to remember, so provisioning a second camera
 * onto the same Wi-Fi does not mean retyping the passphrase.
 *
 * Backed by [EncryptedSharedPreferences], not plain ones. These are the user's
 * home Wi-Fi credentials at rest; the app sandbox already keeps them from other
 * apps, but AES-GCM under a Keystore-held master key also keeps them out of
 * adb backups, and off the disk in readable form if the device is compromised
 * while off.
 *
 * Falls back to plain preferences only if the Keystore is unavailable, which
 * happens on some damaged or heavily modified devices. Losing the feature
 * entirely would be worse than storing them the way most apps do.
 */
class SavedNetworks(context: Context) {

    private val prefs: SharedPreferences = try {
        // security-crypto 1.0.0 API. MasterKey.Builder is 1.1.0-alpha; staying
        // on the stable release for something holding user credentials.
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            ENCRYPTED_PREFS,
            alias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
    }

    fun list(): List<SavedNetwork> {
        val raw = prefs.getString(KEY_NETWORKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val ssid = o.optString("ssid")
                if (ssid.isEmpty()) null else SavedNetwork(ssid, o.optString("password"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Keyed by SSID, so re-saving replaces rather than duplicating. */
    fun save(network: SavedNetwork) {
        val updated = list().filterNot { it.ssid == network.ssid } + network
        write(updated.sortedBy { it.ssid.lowercase() })
    }

    fun forget(ssid: String) = write(list().filterNot { it.ssid == ssid })

    fun find(ssid: String): SavedNetwork? = list().firstOrNull { it.ssid == ssid }

    private fun write(networks: List<SavedNetwork>) {
        val arr = JSONArray()
        networks.forEach {
            arr.put(JSONObject().put("ssid", it.ssid).put("password", it.password))
        }
        prefs.edit().putString(KEY_NETWORKS, arr.toString()).apply()
    }

    companion object {
        private const val ENCRYPTED_PREFS = "tprov_networks"
        private const val FALLBACK_PREFS = "tprov_networks_plain"
        private const val KEY_NETWORKS = "networks"
    }
}
