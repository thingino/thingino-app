package com.thingino.app.provision

import com.thingino.app.R
import com.thingino.app.BuildConfig

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    private var pendingPermission: CompletableDeferred<Boolean>? = null

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

        binding.versionText.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
        setupTimezones()
        setupCollapsibleCard()
        applyDebugVisible()
        // Keeps the spinner hidden until a scan actually returns something.
        populateSsids(emptyList())

        binding.findButton.setOnClickListener { findCamera() }
        binding.provisionButton.setOnClickListener { provision() }
        binding.discoverButton.setOnClickListener { discoverOnNetwork() }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }

        binding.ssidSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                scanned.getOrNull(pos - 1)?.let { binding.ssidInput.setText(it.ssid) }
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

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
            setStatus(getString(R.string.status_connected), R.color.success)
            binding.cameraText.text = info.hostname
            binding.buildText.text = getString(R.string.build_fmt, info.imageId, info.buildId)
            binding.cameraText.visibility = View.VISIBLE
            binding.buildText.visibility = View.VISIBLE
            binding.hostnameInput.setText(info.hostname)
            log("Camera: ${info.hostname}  mac=${info.wlanMac}")
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
        val pass = binding.passInput.text.toString()
        val hostname = binding.hostnameInput.text.toString().trim()
        val rootPass = binding.rootPassInput.text.toString()

        if (ssid.isEmpty()) {
            warn("Enter the network name the camera should join.")
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
            c.save(req)

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

    private fun populateSsids(nets: List<ScannedNetwork>) {
        binding.ssidSpinner.visibility = if (nets.isEmpty()) View.GONE else View.VISIBLE
        if (nets.isEmpty()) return

        val labels = mutableListOf(getString(R.string.ssid_manual))
        nets.mapTo(labels) { n ->
            val security = if (n.isOpen) "open" else n.security
            "${n.ssid}  (${n.signal} dBm, $security)"
        }
        binding.ssidSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_provision_settings, null)
        val debugSwitch = view.findViewById<MaterialSwitch>(R.id.dlgDebugSwitch)
        val hostGroup = view.findViewById<RadioGroup>(R.id.dlgHostRadioGroup)
        val radioPortal = view.findViewById<RadioButton>(R.id.dlgRadioPortal)
        val radioApMode = view.findViewById<RadioButton>(R.id.dlgRadioApMode)

        debugSwitch.isChecked = prefs.getBoolean(PREF_DEBUG, false)
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
    }
}
