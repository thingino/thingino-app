package com.thingino.app.provision

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * Something the user chose to remember under a name: a Wi-Fi network by its
 * SSID, a root password by a label of their own choosing.
 */
data class SavedSecret(val name: String, val secret: String)

/**
 * Secrets the user has chosen to remember, so provisioning a second camera
 * does not mean retyping the Wi-Fi passphrase or the root password. One
 * instance per [Kind]; both kinds share the store.
 *
 * Backed by [EncryptedSharedPreferences], not plain ones. These are the user's
 * home Wi-Fi credentials and camera root passwords at rest; the app sandbox
 * already keeps them from other apps, but AES-GCM under a Keystore-held master
 * key also keeps them out of adb backups, and off the disk in readable form if
 * the device is compromised while off.
 *
 * Falls back to plain preferences only if the Keystore is unavailable, which
 * happens on some damaged or heavily modified devices. Losing the feature
 * entirely would be worse than storing them the way most apps do.
 */
class SecretStore(context: Context, private val kind: Kind) {

    /**
     * Where each kind lives. The preference file and the network keys are the
     * on-disk format since v0.1.0, when networks were the only kind: keep them
     * or existing users lose their saved networks.
     */
    enum class Kind(val listKey: String, val preferredKey: String, val nameField: String) {
        NETWORKS("networks", "preferred", "ssid"),
        ROOT_PASSWORDS("root_passwords", "preferred_root", "label"),
    }

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

    fun list(): List<SavedSecret> {
        val raw = prefs.getString(kind.listKey, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString(kind.nameField)
                if (name.isEmpty()) null else SavedSecret(name, o.optString(SECRET_FIELD))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Keyed by name, so re-saving replaces rather than duplicating. */
    fun save(entry: SavedSecret) {
        val updated = list().filterNot { it.name == entry.name } + entry
        write(updated.sortedBy { it.name.lowercase() })
    }

    fun forget(name: String) {
        write(list().filterNot { it.name == name })
        // A forgotten entry cannot stay preferred, or the picker would try to
        // auto-select something that no longer exists.
        if (preferred() == name) setPreferred(null)
    }

    /** Name auto-selected when the provisioning screen opens, if still saved. */
    fun preferred(): String? =
        prefs.getString(kind.preferredKey, null)?.takeIf { name -> list().any { it.name == name } }

    fun setPreferred(name: String?) {
        prefs.edit().apply {
            if (name == null) remove(kind.preferredKey) else putString(kind.preferredKey, name)
        }.apply()
    }

    fun find(name: String): SavedSecret? = list().firstOrNull { it.name == name }

    private fun write(entries: List<SavedSecret>) {
        val arr = JSONArray()
        entries.forEach {
            arr.put(JSONObject().put(kind.nameField, it.name).put(SECRET_FIELD, it.secret))
        }
        prefs.edit().putString(kind.listKey, arr.toString()).apply()
    }

    companion object {
        private const val ENCRYPTED_PREFS = "tprov_networks"
        private const val FALLBACK_PREFS = "tprov_networks_plain"
        private const val SECRET_FIELD = "password"
    }
}
