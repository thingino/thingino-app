package com.thingino.app.provision

import com.thingino.app.R
import com.thingino.app.BuildConfig

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.thingino.app.databinding.ActivityProvisionBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.util.TimeZone

@RequiresApi(Build.VERSION_CODES.Q)
class ProvisionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProvisionBinding
    private lateinit var prefs: SharedPreferences

    private lateinit var portalWifi: PortalWifi
    private lateinit var discovery: CameraDiscovery

    private var client: PortalClient? = null
    private var portalHost = PortalClient.PORTAL_HOST
    private var scanned: List<ScannedNetwork> = emptyList()
    private var cameraInfo: CameraInfo? = null

    private lateinit var savedNetworks: SavedNetworks

    /**
     * Passphrase behind a selected saved network. Held rather than shown: the
     * user picked it by name and does not need to see it again, and an
     * unnecessarily populated field is one more thing to shoulder-surf or edit
     * by accident. Typing in the password field overrides it.
     */
    private var selectedSavedPassword: String? = null
    private var passwordVisible = false
    private var rootPasswordVisible = false

    /**
     * Set while the picker is rebuilt. Assigning an adapter resets the Spinner
     * to position 0, which fires onItemSelected and would otherwise look like
     * the user choosing "Enter manually", discarding a selected saved network
     * and its passphrase.
     */
    private var rebuildingPicker = false
    /** Spinner rows after the leading "Enter manually" entry. */
    private var pickerRows: List<PickerRow> = emptyList()

    private sealed class PickerRow {
        data class Saved(val network: SavedNetwork) : PickerRow()
        data class Scanned(val network: ScannedNetwork) : PickerRow()
    }

    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val pubkeyPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { loadPubkey(it) } }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProvisionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        portalHost = prefs.getString(PREF_HOST, PortalClient.PORTAL_HOST)
            ?: PortalClient.PORTAL_HOST

        portalWifi = PortalWifi(this)
        discovery = CameraDiscovery(this)
        savedNetworks = SavedNetworks(this)

        binding.versionText.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
        setupTimezones()
        setupCollapsibleCard()
        applyDebugVisible()
        populateSsids(emptyList())
        applyPassRowVisible()
        selectPreferred()

        binding.findButton.setOnClickListener { findCamera() }
        binding.provisionButton.setOnClickListener { provision() }
        binding.discoverButton.setOnClickListener { discoverOnNetwork() }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }

        binding.ssidSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (rebuildingPicker) return
                onPickerSelected(pos)
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        binding.forgetButton.setOnClickListener { forgetSelected() }
        binding.saveNetworkButton.setOnClickListener { saveCurrentNetwork() }
        binding.passToggle.setOnClickListener {
            passwordVisible = toggleReveal(binding.passInput, binding.passToggle, passwordVisible)
        }
        binding.rootPassToggle.setOnClickListener {
            rootPasswordVisible =
                toggleReveal(binding.rootPassInput, binding.rootPassToggle, rootPasswordVisible)
        }
        binding.pubkeyPickButton.setOnClickListener { pubkeyPicker.launch("*/*") }

        // Save only makes sense once there is something to save.
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: Editable?) {
                if (binding.passInput.text.isNotEmpty()) selectedSavedPassword = null
                applySaveVisible()
            }
        }
        binding.ssidInput.addTextChangedListener(watcher)
        binding.passInput.addTextChangedListener(watcher)

        log("Ready. The camera must be in portal mode, which lasts 10 minutes from boot.")
    }

    override fun onDestroy() {
        portalWifi.release()
        super.onDestroy()
    }

    // ---- flow ---------------------------------------------------------------

    private fun findCamera() = lifecycleScope.launch {
        if (!ensureNearbyWifiPermission()) {
            warn(
                "NEARBY_WIFI_DEVICES was denied. Android 13 and later require it " +
                    "before the system will offer the camera's access point. It is " +
                    "declared neverForLocation, so it grants no location access."
            )
            return@launch
        }

        busy(true, getString(R.string.status_searching))
        try {
            log("Requesting a ${PortalWifi.SSID_PREFIX}* network via the system picker...")
            val network = portalWifi.join()
            log("Joined. Binding requests to the local-only network.")

            val c = PortalClient(network, portalHost)
            client = c

            val info = c.getInfo()
            cameraInfo = info
            setStatus(getString(R.string.status_connected), R.color.success)
            binding.cameraText.text = info.hostname
            binding.buildText.text = getString(R.string.build_fmt, info.imageId, info.buildId)
            binding.cameraText.visibility = View.VISIBLE
            binding.buildText.visibility = View.VISIBLE
            binding.hostnameInput.setText(info.hostname)
            log("Camera: ${info.hostname}  mac=${info.wlanMac}")
            log(if (c.secure) "Transport: HTTPS" else "Transport: HTTP (camera has no TLS portal)")
            applyTransportWarning(c.secure, info.acceptsHashedSecrets)
            log(
                if (info.acceptsHashedSecrets) {
                    "Credentials: derived on this phone, plaintext never sent."
                } else {
                    "Credentials: sent as typed. This firmware cannot take derived forms."
                }
            )
            log("Image:  ${info.imageId}")
            log("Build:  ${info.buildId}")

            binding.provisionButton.isEnabled = true

            log("Asking the camera which networks it can hear...")
            scanned = c.scanNetworks()
            populateSsids(scanned)
            if (scanned.isEmpty()) {
                log(
                    "Scan returned nothing. That is common: api.cgi runs " +
                        "wpa_cli -i wlan0 while some builds use ap0 or wlan1, and " +
                        "scanning during beaconing is unreliable. Type the SSID instead."
                )
            } else {
                log("Camera can hear ${scanned.size} network(s).")
            }
        } catch (e: Exception) {
            fail(e)
        } finally {
            busy(false, null)
        }
    }

    private fun provision() = lifecycleScope.launch {
        val c = client ?: run {
            warn("Not connected to a camera yet.")
            return@launch
        }

        val ssid = binding.ssidInput.text.toString().trim()
        val typed = binding.passInput.text.toString()
        val pass = if (typed.isEmpty()) selectedSavedPassword.orEmpty() else typed
        val hostname = binding.hostnameInput.text.toString().trim()
        val rootPass = binding.rootPassInput.text.toString()

        if (ssid.isEmpty()) {
            warn("Enter the network name the camera should join.")
            return@launch
        }
        if (pass.isEmpty()) {
            warn("Enter the passphrase for $ssid.")
            return@launch
        }
        if (rootPass.isEmpty()) {
            warn("Set a root password. The portal writes it with chpasswd -c sha512.")
            return@launch
        }

        busy(true, getString(R.string.status_saving))
        try {
            val req = ProvisionRequest(
                hostname = hostname,
                wlanSsid = ssid,
                wlanPass = pass,
                rootPass = rootPass,
                timezone = binding.timezoneSpinner.selectedItem?.toString().orEmpty(),
                pubkey = binding.pubkeyInput.text.toString().trim(),
                apMode = binding.apModeSwitch.isChecked,
            )
            c.save(req, hashed = cameraInfo?.acceptsHashedSecrets == true)

            log("Configuration accepted. Camera reboots in 2 seconds.")
            log(
                "Note: that is a write confirmation, not proof the passphrase is " +
                    "correct. If the camera never appears below, it is most likely " +
                    "back in portal mode."
            )
            setStatus(getString(R.string.status_rebooting), R.color.warning)
            binding.provisionButton.isEnabled = false

            portalWifi.release()
            client = null
            log("Released the portal network. Reconnect the phone to your own wifi, then use Find on network.")
        } catch (e: Exception) {
            fail(e)
        } finally {
            busy(false, null)
        }
    }

    private fun discoverOnNetwork() = lifecycleScope.launch {
        busy(true, getString(R.string.status_discovering))
        try {
            log("Browsing ${CameraDiscovery.HTTP_SERVICE} for Thingino cameras...")
            val cams = discovery.browse(CameraDiscovery.HTTP_SERVICE)
            if (cams.isEmpty()) {
                log("Nothing found. mdnsd binds a single interface picked at boot, so give it a moment after reboot, or check the router lease table.")
            } else {
                cams.forEach { log("  ${it.hostname}  http://${it.address}:${it.port}") }
            }

            val streams = discovery.browse(CameraDiscovery.RTSP_SERVICE)
            streams.forEach { log("  ${it.hostname}  rtsp://${it.address}:${it.port}") }
        } catch (e: Exception) {
            fail(e)
        } finally {
            busy(false, null)
        }
    }

    // ---- permissions --------------------------------------------------------

    /**
     * On API 33 and up the wifi request is gated behind NEARBY_WIFI_DEVICES, and
     * requestNetwork() fails silently without it. Below 33 the manifest entry is
     * inert and nothing needs granting, because the system dialog is what picks
     * the network for us.
     */
    private suspend fun ensureNearbyWifiPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

        val permission = Manifest.permission.NEARBY_WIFI_DEVICES
        if (ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }

        val result = CompletableDeferred<Boolean>()
        pendingPermission = result
        permissionLauncher.launch(permission)
        return result.await()
    }

    // ---- ui -----------------------------------------------------------------

    private fun setupTimezones() {
        // The web portal sends Intl's IANA zone with underscores turned into
        // spaces, and /etc/timezone on a live camera holds exactly that
        // ("America/Los Angeles"). Match it rather than sending the raw ID.
        val zones = TimeZone.getAvailableIDs().map { it.replace('_', ' ') }.sorted()
        binding.timezoneSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, zones
        )
        val current = TimeZone.getDefault().id.replace('_', ' ')
        zones.indexOf(current).takeIf { it >= 0 }?.let { binding.timezoneSpinner.setSelection(it) }
    }

    private fun setupCollapsibleCard() {
        binding.deviceHeader.setOnClickListener {
            val open = binding.deviceBody.visibility == View.VISIBLE
            binding.deviceBody.visibility = if (open) View.GONE else View.VISIBLE
            binding.deviceChevron.text = if (open) "▸" else "▾"
        }
    }

    /**
     * One picker over two sources: networks the user saved earlier, and
     * networks this camera can currently hear. Saved ones come first and carry
     * their passphrase; scanned ones only fill in the name. Hidden entirely
     * when there is nothing to offer, since a dropdown whose only entry is
     * "Enter manually" is a wasted row.
     */
    private fun populateSsids(nets: List<ScannedNetwork>) {
        val saved = savedNetworks.list()
        // A saved network that the camera can also hear should appear once.
        val savedSsids = saved.map { it.ssid }.toSet()
        pickerRows = saved.map { PickerRow.Saved(it) } +
            nets.filterNot { it.ssid in savedSsids }.map { PickerRow.Scanned(it) }

        binding.ssidSpinner.visibility = if (pickerRows.isEmpty()) View.GONE else View.VISIBLE
        if (pickerRows.isEmpty()) {
            binding.forgetButton.visibility = View.GONE
            return
        }

        val labels = mutableListOf(getString(R.string.ssid_manual))
        pickerRows.mapTo(labels) { row ->
            when (row) {
                is PickerRow.Saved -> getString(R.string.wifi_saved_fmt, row.network.ssid)
                is PickerRow.Scanned -> getString(
                    R.string.wifi_scanned_fmt,
                    row.network.ssid,
                    row.network.signal,
                    if (row.network.isOpen) "open" else row.network.security,
                )
            }
        }
        // Keep whatever was chosen before: this runs again after the camera
        // scan lands, and a rebuild must not silently undo the user's pick.
        val previous = binding.ssidInput.text.toString().trim()
        rebuildingPicker = true
        binding.ssidSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        val restored = pickerRows.indexOfFirst { ssidOf(it) == previous }
        binding.ssidSpinner.setSelection(if (restored >= 0) restored + 1 else 0)
        binding.ssidSpinner.post { rebuildingPicker = false }
    }

    /**
     * Opens on the network the user marked, so provisioning several cameras
     * onto the same Wi-Fi needs no picking at all.
     */
    private fun selectPreferred() {
        val ssid = savedNetworks.preferred() ?: return
        val at = pickerRows.indexOfFirst { ssidOf(it) == ssid }
        if (at >= 0) binding.ssidSpinner.setSelection(at + 1)
    }

    private fun ssidOf(row: PickerRow): String = when (row) {
        is PickerRow.Saved -> row.network.ssid
        is PickerRow.Scanned -> row.network.ssid
    }

    private fun onPickerSelected(position: Int) {
        val row = pickerRows.getOrNull(position - 1)
        when (row) {
            is PickerRow.Saved -> {
                binding.ssidInput.setText(row.network.ssid)
                binding.passInput.setText("")
                selectedSavedPassword = row.network.password
                binding.forgetButton.visibility = View.VISIBLE
                applyPassRowVisible()
            }
            is PickerRow.Scanned -> {
                binding.ssidInput.setText(row.network.ssid)
                clearSavedPassword()
                binding.forgetButton.visibility = View.GONE
            }
            null -> {
                clearSavedPassword()
                binding.forgetButton.visibility = View.GONE
            }
        }
    }

    private fun clearSavedPassword() {
        selectedSavedPassword = null
        applyPassRowVisible()
    }

    /**
     * With a saved network chosen there is nothing to type and nothing to
     * save, so the whole row goes and a status line takes its place. Forget is
     * the way back: it clears the selection and the field returns, which also
     * covers a passphrase that has gone stale. That matters because `save`
     * reports success either way, so a wrong passphrase fails silently.
     */
    /**
     * The cleartext warning is about the transport, so it only belongs on
     * screen when the transport is actually cleartext. Over TLS it would be
     * simply untrue; over HTTP with derived credentials it is true but
     * narrower, since the derived network key still lets someone join.
     */
    private fun applyTransportWarning(secure: Boolean, hashed: Boolean) {
        if (secure) {
            binding.wifiWarning.visibility = View.GONE
            return
        }
        binding.wifiWarning.visibility = View.VISIBLE
        binding.wifiWarning.setText(
            if (hashed) R.string.wifi_cleartext_derived else R.string.wifi_cleartext_warning
        )
    }

    private fun applyPassRowVisible() {
        val usingSaved = selectedSavedPassword != null
        binding.passRow.visibility = if (usingSaved) View.GONE else View.VISIBLE
        binding.savedPassNote.visibility = if (usingSaved) View.VISIBLE else View.GONE
        applySaveVisible()
    }

    /**
     * Present as soon as the form is in use, but only live when there is
     * genuinely something new to store: a selected saved network, or one
     * re-entered unchanged, leaves it greyed rather than removing it, so the
     * row does not jump around while typing.
     */
    private fun applySaveVisible() {
        val ssid = binding.ssidInput.text.toString().trim()
        val typed = binding.passInput.text.toString()
        val existing = savedNetworks.find(ssid)
        val unchanged = existing != null && existing.password == typed

        // Nothing to save while a stored network is in use.
        binding.saveNetworkButton.visibility =
            if (ssid.isEmpty() || selectedSavedPassword != null) View.GONE else View.VISIBLE
        binding.saveNetworkButton.isEnabled =
            ssid.isNotEmpty() && typed.isNotEmpty() && !unchanged
    }

    private fun saveCurrentNetwork() {
        val ssid = binding.ssidInput.text.toString().trim()
        val typed = binding.passInput.text.toString()
        val pass = if (typed.isEmpty()) selectedSavedPassword.orEmpty() else typed
        if (ssid.isEmpty() || pass.isEmpty()) return
        savedNetworks.save(SavedNetwork(ssid, pass))
        log(getString(R.string.wifi_saved_toast_fmt, ssid))
        populateSsids(scanned)
        binding.forgetButton.visibility = View.VISIBLE
        applySaveVisible()
    }

    /**
     * Changing inputType resets the typeface, so restore it and put the cursor
     * back where it was; otherwise revealing a passphrase jumps to monospace
     * and sends the caret to position zero mid-edit.
     */
    /**
     * Reveals or hides a password field.
     *
     * Visibility is tracked by the caller rather than read back from
     * inputType: variations are a masked field, so VISIBLE_PASSWORD (0x90) AND
     * PASSWORD (0x80) is non-zero and a derived check only ever toggles one
     * way. Changing inputType also resets the typeface and caret, so both are
     * restored.
     */
    private fun toggleReveal(
        field: android.widget.EditText,
        button: android.widget.ImageButton,
        wasVisible: Boolean,
    ): Boolean {
        val face = field.typeface
        val at = field.selectionStart
        val nowVisible = !wasVisible

        field.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            if (nowVisible) {
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        field.typeface = face
        field.setSelection(at.coerceIn(0, field.text.length))

        button.setImageResource(
            if (nowVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        )
        button.contentDescription =
            getString(if (nowVisible) R.string.hide_password else R.string.show_password)
        return nowVisible
    }

    /** Accepts an id_*.pub file; the portal writes it to authorized_keys verbatim. */
    private fun loadPubkey(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.toString(Charsets.UTF_8)
        }.getOrNull()

        if (text.isNullOrBlank()) {
            warn("Could not read that file.")
            return
        }
        if (text.length > MAX_PUBKEY_BYTES) {
            warn("That file is too large to be a public key. Did you pick the private one?")
            return
        }
        val key = text.trim().lines().firstOrNull { it.isNotBlank() }.orEmpty()
        if (!key.startsWith("ssh-") && !key.startsWith("ecdsa-")) {
            warn("That does not look like an SSH public key.")
            return
        }
        binding.pubkeyInput.setText(key)
        log("Loaded public key (${key.substringBefore(' ')}, ${key.length} chars)")
    }

    private fun forgetSelected() {
        val ssid = binding.ssidInput.text.toString().trim()
        if (ssid.isEmpty()) return
        savedNetworks.forget(ssid)
        binding.passInput.setText("")
        clearSavedPassword()
        log("Forgot saved network $ssid")
        populateSsids(scanned)
        binding.ssidSpinner.setSelection(0)
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_provision_settings, null)
        val debugSwitch = view.findViewById<MaterialSwitch>(R.id.dlgDebugSwitch)
        val networkCount = view.findViewById<android.widget.TextView>(R.id.dlgNetworkCount)
        val manageNetworks = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.dlgManageNetworks)
        val hostGroup = view.findViewById<RadioGroup>(R.id.dlgHostRadioGroup)
        val radioPortal = view.findViewById<RadioButton>(R.id.dlgRadioPortal)
        val radioApMode = view.findViewById<RadioButton>(R.id.dlgRadioApMode)

        debugSwitch.isChecked = prefs.getBoolean(PREF_DEBUG, false)

        fun refreshNetworkRow() {
            val n = savedNetworks.list().size
            networkCount.text = if (n == 0) {
                getString(R.string.settings_networks_none)
            } else {
                getString(R.string.settings_networks_count_fmt, n)
            }
        }
        refreshNetworkRow()
        manageNetworks.setOnClickListener { showSavedNetworks { refreshNetworkRow() } }
        (if (portalHost == PortalClient.AP_MODE_HOST) radioApMode else radioPortal).isChecked = true

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                prefs.edit().putBoolean(PREF_DEBUG, debugSwitch.isChecked).apply()
                applyDebugVisible()

                val host = if (hostGroup.checkedRadioButtonId == R.id.dlgRadioApMode) {
                    PortalClient.AP_MODE_HOST
                } else {
                    PortalClient.PORTAL_HOST
                }
                if (host != portalHost) {
                    portalHost = host
                    prefs.edit().putString(PREF_HOST, host).apply()
                    log("Portal address set to $host")
                }
            }
            .show()
    }

    /**
     * Review and remove what the app is holding. Without this the only way to
     * meet a saved network is to stumble on it in the picker, and the only way
     * to remove one is to select it first.
     */
    private fun showSavedNetworks(onChanged: () -> Unit) {
        val networks = savedNetworks.list()
        val preferred = savedNetworks.preferred()
        val labels = networks.map {
            getString(
                if (it.ssid == preferred) R.string.networks_preferred_mark
                else R.string.networks_plain_mark,
                it.ssid,
            )
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_networks_title)
            .setItems(labels) { _, which -> networkActions(networks[which], onChanged) }
            .setPositiveButton(R.string.networks_add) { _, _ -> addNetwork(onChanged) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Two useful things per network, so ask rather than assume one of them. */
    private fun networkActions(target: SavedNetwork, onChanged: () -> Unit) {
        val isPreferred = savedNetworks.preferred() == target.ssid
        MaterialAlertDialogBuilder(this)
            .setTitle(target.ssid)
            .setNeutralButton(
                if (isPreferred) R.string.networks_prefer_clear else R.string.networks_prefer_set
            ) { _, _ ->
                savedNetworks.setPreferred(if (isPreferred) null else target.ssid)
                log(
                    if (isPreferred) "${target.ssid} will no longer be selected automatically"
                    else "${target.ssid} will be selected automatically"
                )
                onChanged()
            }
            .setPositiveButton(R.string.btn_forget) { _, _ ->
                savedNetworks.forget(target.ssid)
                log("Forgot saved network ${target.ssid}")
                populateSsids(scanned)
                onChanged()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun addNetwork(onChanged: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_add_network, null)
        val ssidField = view.findViewById<android.widget.EditText>(R.id.addSsid)
        val passField = view.findViewById<android.widget.EditText>(R.id.addPass)
        val preferBox = view.findViewById<android.widget.CheckBox>(R.id.addPreferred)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.networks_add_title)
            .setView(view)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val ssid = ssidField.text.toString().trim()
                val pass = passField.text.toString()
                if (ssid.isEmpty() || pass.isEmpty()) {
                    warn("A network needs both a name and a passphrase.")
                    return@setPositiveButton
                }
                savedNetworks.save(SavedNetwork(ssid, pass))
                if (preferBox.isChecked) savedNetworks.setPreferred(ssid)
                log("Saved $ssid")
                populateSsids(scanned)
                onChanged()
            }
            .show()
    }

    private fun applyDebugVisible() {
        binding.logCard.visibility =
            if (prefs.getBoolean(PREF_DEBUG, false)) View.VISIBLE else View.GONE
    }

    private fun busy(busy: Boolean, status: String?) {
        binding.progressBar.isIndeterminate = busy
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.progressText.visibility = if (busy && status != null) View.VISIBLE else View.GONE
        binding.progressText.text = status.orEmpty()
        if (status != null) setStatus(status, R.color.on_surface)

        binding.findButton.isEnabled = !busy
        binding.discoverButton.isEnabled = !busy
        binding.provisionButton.isEnabled = !busy && client != null
    }

    private fun setStatus(text: String, colorRes: Int) {
        binding.statusText.text = text
        binding.statusText.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun fail(e: Exception) {
        val message = e.message ?: e.javaClass.simpleName
        log("Error: $message")
        // Also on the status line, because the log is hidden by default and an
        // error that only reaches the log is an error nobody sees.
        setStatus(message, R.color.error)
    }

    /** A validation complaint. Same reasoning as [fail]: must survive a hidden log. */
    private fun warn(message: String) {
        log(message)
        setStatus(message, R.color.warning)
    }

    private fun log(line: String) {
        binding.logText.append(if (binding.logText.length() == 0) line else "\n$line")
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val PREFS_NAME = "tprov_prefs"
        private const val PREF_DEBUG = "show_debug_log"
        private const val PREF_HOST = "portal_host"
        private const val MAX_PUBKEY_BYTES = 16 * 1024
    }
}
