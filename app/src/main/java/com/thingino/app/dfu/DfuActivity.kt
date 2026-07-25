package com.thingino.app.dfu

import com.thingino.app.R
import com.thingino.dfu.TdfuBridge

import android.content.Intent
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DfuActivity : AppCompatActivity(), UsbHelper.DeviceListener, TdfuBridge.NativeCallback {

    companion object {
        private const val TAG = "ThinginoDfu"
        private const val PREFS_NAME = "tdfu_prefs"
        private const val PREF_HOST = "remote_host"
        private const val PREF_PORT = "remote_port"
        private const val PREF_REMOTE = "remote_mode"
        private const val PREF_DEBUG = "debug_logging"
        private const val PREF_VERIFY = "verify_after_write"
        private const val PREF_RELEASES = "show_releases"
        private const val PREF_ADVANCED = "show_advanced"
        private const val PREF_REBOOT = "reboot_after_flash"
    }

    private lateinit var usbHelper: UsbHelper
    private lateinit var prefs: SharedPreferences

    // UI elements
    private lateinit var statusText: TextView
    private lateinit var socText: TextView
    private lateinit var readButton: Button
    private lateinit var writeButton: Button
    private lateinit var diagButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logText: TextView
    private lateinit var deviceSpinner: Spinner

    // Mode selection UI

    // Custom bootloader UI (custom SPL/U-Boot; shared by local + remote modes)
    private lateinit var customBlobInfo: TextView
    private lateinit var selectSplButton: Button
    private lateinit var selectUbootButton: Button
    private lateinit var clearCustomButton: Button
    private lateinit var bootstrapButton: Button
    private lateinit var advancedCard: View

    // Prebuilt-release picker (opt-in via Settings).
    private lateinit var releaseCard: View
    private lateinit var releaseSpinner: Spinner
    private lateinit var releaseDeviceSpinner: Spinner
    private lateinit var releaseAllCheck: CheckBox
    private lateinit var releaseInfo: TextView
    private lateinit var releaseFlashButton: Button
    private var releases: List<Releases.Release> = emptyList()
    private var releaseImages: List<String> = emptyList()
    private var releasesLoaded = false
    // The SoC the image list is currently narrowed to, so we can tell when a
    // device is connected, swapped or unplugged and the list needs rebuilding.
    private var releaseFilterSoc: String = ""

    // State
    private var detectedSoc: String = ""
    private var operationRunning = false
    private var isRemoteMode = false
    // True when the connected local USB device is already a U-Boot DFU gadget.
    private var isDfuGadget = false
    private var remoteClient: RemoteClient? = null
    private var remoteDevices: List<RemoteClient.DeviceEntry> = emptyList()
    private var selectedDeviceIndex: Int = 0
    // Last successful diagnostics readout. The eFuse is only readable on a
    // pristine bootrom; after bootstrap the device leaves bootrom, so we keep
    // the last readout to stay viewable. Cleared only on an explicit reconnect.
    private var lastDiagText: String? = null

    // Optional custom SPL + U-Boot for REMOTE bootstrap (both-or-neither). When
    // both are set, the daemon uses them in place of the bundled DFU U-Boot.
    private var customSplBytes: ByteArray? = null
    private var customSplName: String? = null
    private var customUbootBytes: ByteArray? = null
    private var customUbootName: String? = null

    // File picker result
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            startWriteOperation(uri)
        } else {
            appendLog("File selection cancelled\n")
        }
    }

    // Separate pickers for the two custom-bootloader slots, so we never have to
    // track which slot a single picker is filling.
    private val splPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) loadCustomBlob(uri, isSpl = true) }

    private val ubootPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) loadCustomBlob(uri, isSpl = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dfu)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Display version
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<android.widget.TextView>(R.id.versionText)?.text = "v$versionName"
        } catch (_: Exception) {}

        // Initialize UI
        statusText = findViewById(R.id.statusText)
        socText = findViewById(R.id.socText)
        readButton = findViewById(R.id.readButton)
        writeButton = findViewById(R.id.writeButton)
        diagButton = findViewById(R.id.diagButton)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        logScroll = findViewById(R.id.logScroll)
        logText = findViewById(R.id.logText)
        deviceSpinner = findViewById(R.id.deviceSpinner)

        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (isRemoteMode && remoteDevices.isNotEmpty()) {
                    selectedDeviceIndex = pos
                    val dev = remoteDevices[pos]
                    // A device past bootrom (DFU/firmware) only reports a
                    // placeholder variant; keep the SoC detected at bootrom
                    // rather than overwriting it (matches the daemon/web UX).
                    if (dev.stage == 0 || detectedSoc.isEmpty()) {
                        detectedSoc = dev.variantName
                    }
                    socText.text = "SoC: ${detectedSoc.uppercase()} (${dev.stageName})"
                    refreshDiagButton()
                    refreshBootstrapButton()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Backend mode (Local USB vs remote) is chosen in Settings; restore it.
        isRemoteMode = prefs.getBoolean(PREF_REMOTE, false)

        // Custom bootloader UI (shared by local + remote modes), collapsed by default.
        customBlobInfo = findViewById(R.id.customBlobInfo)
        selectSplButton = findViewById(R.id.selectSplButton)
        selectUbootButton = findViewById(R.id.selectUbootButton)
        clearCustomButton = findViewById(R.id.clearCustomButton)
        bootstrapButton = findViewById(R.id.bootstrapButton)
        updateCustomBlobInfo()

        // Tappable "Advanced" header expands/collapses the custom SPL/U-Boot body.
        advancedCard = findViewById(R.id.remoteBootstrapCard)
        applyAdvancedVisible()
        val customHeader = findViewById<LinearLayout>(R.id.customHeader)
        val customBody = findViewById<LinearLayout>(R.id.customBody)
        val customChevron = findViewById<TextView>(R.id.customChevron)
        customHeader.setOnClickListener {
            val show = customBody.visibility != View.VISIBLE
            customBody.visibility = if (show) View.VISIBLE else View.GONE
            customChevron.text = if (show) "▾" else "▸"
        }

        setupReleasePicker()

        // Apply the saved debug level to native (the Settings dialog persists it).
        TdfuBridge.nativeSetDebug(prefs.getBoolean(PREF_DEBUG, false))

        findViewById<android.widget.ImageButton>(R.id.settingsButton).setOnClickListener {
            showSettingsDialog()
        }

        logText.movementMethod = ScrollingMovementMethod()

        readButton.setOnClickListener { startReadOperation() }
        writeButton.setOnClickListener { pickFileForWrite() }
        diagButton.setOnClickListener { startDiagOperation() }
        selectSplButton.setOnClickListener { splPickerLauncher.launch("application/octet-stream") }
        selectUbootButton.setOnClickListener { ubootPickerLauncher.launch("application/octet-stream") }
        clearCustomButton.setOnClickListener { clearCustomBlobs() }
        bootstrapButton.setOnClickListener { startRemoteBootstrap() }

        // Backend mode + host/port now live in the Settings dialog. The mode is
        // applied at the end of onCreate and whenever Settings is saved.

        // Set up USB. This has to come before setButtonsEnabled: the release-picker
        // gate asks usbHelper whether a device is connected, so a later init means
        // the very first setButtonsEnabled call reads an uninitialized lateinit and
        // the activity dies before it draws.
        usbHelper = UsbHelper(this)
        usbHelper.setListener(this)

        // Disable buttons until device is connected
        setButtonsEnabled(false)

        // Set up native callback
        TdfuBridge.nativeSetCallback(this)

        appendLog("Thingino DFU ready.\n")
        appendLog("Connect a device via USB OTG or remote daemon.\n")

        // Check if launched from USB device attach intent
        handleUsbIntent(intent)

        // Apply the saved backend now that USB + native are initialized.
        if (isRemoteMode) applyMode(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (isRemoteMode) return

        if (intent?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("device", UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("device")
            }
            if (device != null && UsbHelper.isIngenicDevice(device)) {
                appendLog("Device connected via intent: ${usbHelper.getDeviceDescription(device)}\n")
                usbHelper.requestPermission(device)
                return
            }
        }
        scanForDevices()
    }

    override fun onResume() {
        super.onResume()
        usbHelper.register()
        if (!isRemoteMode && !usbHelper.isConnected()) {
            scanForDevices()
        }
    }

    override fun onPause() {
        super.onPause()
        usbHelper.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        TdfuBridge.nativeSetCallback(null)
        usbHelper.closeDevice()
        remoteClient?.disconnect()
    }

    private fun scanForDevices() {
        if (isRemoteMode) return
        val device = usbHelper.findDevice()
        if (device != null) {
            updateStatus("Device found, requesting permission...")
            usbHelper.requestPermission(device)
        } else {
            updateStatus("No device connected")
            socText.text = ""
            setButtonsEnabled(false)
        }
    }

    // ======================================================================
    // Remote Mode
    // ======================================================================

    private fun connectRemote() {
        val host = (prefs.getString(PREF_HOST, "") ?: "").trim()
        if (host.isEmpty()) {
            appendLog("ERROR: Set the daemon host in Settings\n")
            updateStatus("Set the daemon host in Settings")
            return
        }
        val port = prefs.getInt(PREF_PORT, RemoteClient.DEFAULT_PORT)
            .let { if (it > 0) it else RemoteClient.DEFAULT_PORT }

        // Explicit user connect = genuinely new connection: drop any cached diag.
        // (The automatic post-bootstrap re-discover does NOT call this, so the
        // cached readout survives a device leaving bootrom — which is the point.)
        lastDiagText = null

        appendLog("Connecting to $host:$port...\n")

        lifecycleScope.launch(Dispatchers.IO) {
            val client = RemoteClient(this@DfuActivity)
            val connected = client.connect(host, port)

            if (!connected) {
                withContext(Dispatchers.Main) {
                    appendLog("Connection failed\n")
                    updateStatus("Connection failed")
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                appendLog("Connected to daemon\n")
            }

            val devices = client.discover()

            withContext(Dispatchers.Main) {
                remoteClient = client
                remoteDevices = devices
                selectedDeviceIndex = 0

                if (devices.isEmpty()) {
                    updateStatus("Connected — no devices found")
                    socText.text = ""
                    deviceSpinner.visibility = View.GONE
                    appendLog("No Ingenic devices found on daemon\n")
                } else {
                    val dev = devices[0]
                    detectedSoc = dev.variantName
                    updateStatus("Connected — ${devices.size} device(s)")
                    socText.text = "SoC: ${dev.variantName.uppercase()} (${dev.stageName})"
                    setButtonsEnabled(true)
                    appendLog("Found ${devices.size} device(s):\n")
                    devices.forEachIndexed { i, d ->
                        appendLog("  [$i] $d\n")
                    }

                    if (devices.size > 1) {
                        val names = devices.map { it.toString() }
                        deviceSpinner.adapter = ArrayAdapter(
                            this@DfuActivity,
                            android.R.layout.simple_spinner_dropdown_item,
                            names
                        )
                        deviceSpinner.visibility = View.VISIBLE
                    } else {
                        deviceSpinner.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun disconnectRemote() {
        remoteClient?.disconnect()
        remoteClient = null
        remoteDevices = emptyList()
        setButtonsEnabled(false)
        deviceSpinner.visibility = View.GONE
        if (isRemoteMode) {
            updateStatus("Disconnected")
            socText.text = ""
        }
    }

    /** Apply the chosen backend: local -> USB scan; remote -> connect to the saved
     *  daemon. Called on startup and when Settings is saved with a changed backend. */
    private fun applyMode(remote: Boolean) {
        isRemoteMode = remote
        bootstrapButton.visibility = if (remote) View.VISIBLE else View.GONE
        deviceSpinner.visibility = View.GONE
        setButtonsEnabled(false)
        detectedSoc = ""
        socText.text = ""
        if (remote) {
            usbHelper.closeDevice()
            val host = (prefs.getString(PREF_HOST, "") ?: "").trim()
            if (host.isEmpty()) {
                updateStatus("Set the daemon host in Settings")
            } else {
                connectRemote()
            }
        } else {
            disconnectRemote()
            updateStatus("No device connected")
            scanForDevices()
        }
    }

    // ======================================================================
    // UsbHelper.DeviceListener
    // ======================================================================

    override fun onDeviceAttached(device: UsbDevice) {
        if (isRemoteMode) return
        runOnUiThread {
            appendLog("Device attached: ${usbHelper.getDeviceDescription(device)}\n")
            updateStatus("Device attached, requesting permission...")
            usbHelper.requestPermission(device)
        }
    }

    override fun onDeviceDetached() {
        if (isRemoteMode) return
        runOnUiThread {
            appendLog("Device detached\n")
            updateStatus("No device connected")
            socText.text = ""
            detectedSoc = ""
            isDfuGadget = false
            setButtonsEnabled(false)
        }
    }

    override fun onPermissionGranted(device: UsbDevice) {
        if (isRemoteMode) return
        runOnUiThread {
            appendLog("USB permission granted\n")
            updateStatus("Opening device...")

            val fd = usbHelper.openDevice(device)
            if (fd < 0) {
                appendLog("ERROR: Failed to open USB device\n")
                updateStatus("Failed to open device")
                return@runOnUiThread
            }

            appendLog("Device opened (fd=$fd)\n")
            updateStatus("Connected: ${usbHelper.getDeviceDescription(device)}")

            isDfuGadget = UsbHelper.isDfuGadget(device)
            if (isDfuGadget) {
                // A running U-Boot DFU gadget only speaks DFU class requests, so
                // the Ingenic SoC-detection protocol does not apply. The variant
                // is irrelevant for DFU (opaque byte movement to a named alt).
                detectedSoc = ""
                socText.text = getString(R.string.status_dfu_gadget)
                appendLog("U-Boot DFU gadget detected - ready to read/write.\n")
                setButtonsEnabled(true)
            } else {
                detectSoc(fd)
            }
        }
    }

    override fun onPermissionDenied(device: UsbDevice) {
        runOnUiThread {
            appendLog("USB permission denied\n")
            updateStatus("Permission denied")
        }
    }

    // ======================================================================
    // TdfuBridge.NativeCallback (called from native or remote thread)
    // ======================================================================

    override fun onLog(message: String) {
        runOnUiThread {
            appendLog(message)
        }
    }

    override fun onProgress(percent: Int, stage: String, message: String) {
        runOnUiThread {
            progressBar.progress = percent
            progressText.text = "[$stage] $message ($percent%)"
        }
    }

    // ======================================================================
    // SoC Detection (local USB only)
    // ======================================================================

    private fun detectSoc(fd: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val soc = TdfuBridge.nativeDetectSoc(fd)

            withContext(Dispatchers.Main) {
                if (soc.isNotEmpty()) {
                    detectedSoc = soc
                    socText.text = "SoC: ${soc.uppercase()}"
                    setButtonsEnabled(true)
                    appendLog("SoC detected: ${soc.uppercase()}\n")
                } else {
                    socText.text = "SoC: unknown"
                    detectedSoc = "t31x"
                    setButtonsEnabled(true)
                    appendLog("SoC detection failed, defaulting to T31X\n")
                }
            }
        }
    }

    // ======================================================================
    // DFU Bootstrap (local USB: bootrom -> U-Boot DFU gadget)
    // ======================================================================

    /**
     * Load the DFU-capable U-Boot onto a device still in bootrom. The device
     * then re-enumerates as a 4d44 DFU gadget, which Android sees as a brand-new
     * USB device (new fd), so the read/write is retried once it reconnects.
     */
    private fun bootstrapToDfu() {
        if (operationRunning) return
        val useCustom = customSplBytes?.isNotEmpty() == true && customUbootBytes?.isNotEmpty() == true
        // Custom blobs are explicit, so the SoC isn't needed to pick a bundled
        // DFU U-Boot; only require detection for the bundled path.
        if (!useCustom && detectedSoc.isEmpty()) {
            appendLog("ERROR: SoC not detected; cannot select DFU U-Boot\n")
            return
        }
        val device = usbHelper.getDevice()
        if (device == null) {
            appendLog("ERROR: No USB device connected\n")
            return
        }

        operationRunning = true
        setButtonsEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.progress = 0

        appendLog("\n--- DFU BOOTSTRAP ---\n")
        if (useCustom) {
            appendLog("Loading custom U-Boot DFU gadget...\n")
        } else {
            appendLog("Loading U-Boot DFU gadget onto ${detectedSoc.uppercase()}...\n")
        }

        val cacheDir = cacheDir.absolutePath
        val assetManager = assets

        lifecycleScope.launch(Dispatchers.IO) {
            val newFd = withContext(Dispatchers.Main) {
                val d = usbHelper.getDevice()
                if (d != null) {
                    usbHelper.closeDevice()
                    usbHelper.openDevice(d)
                } else -1
            }

            if (newFd < 0) {
                withContext(Dispatchers.Main) {
                    appendLog("ERROR: Failed to reopen device\n")
                    finishOperation()
                }
                return@launch
            }

            // Custom SPL/U-Boot (both-or-neither): stage each ByteArray to a temp
            // file and USB-boot them via nativeBootstrapFiles; otherwise use the
            // bundled assets via nativeBootstrap.
            val spl = customSplBytes
            val uboot = customUbootBytes
            val result = if (spl != null && spl.isNotEmpty() && uboot != null && uboot.isNotEmpty()) {
                val splTmp = File(cacheDir, "custom_spl.bin")
                val ubootTmp = File(cacheDir, "custom_uboot.bin")
                try {
                    splTmp.writeBytes(spl)
                    ubootTmp.writeBytes(uboot)
                    withContext(Dispatchers.Main) {
                        appendLog("Using custom SPL (${spl.size} B) + U-Boot (${uboot.size} B)\n")
                    }
                    TdfuBridge.nativeBootstrapFiles(newFd, splTmp.absolutePath, ubootTmp.absolutePath)
                } finally {
                    splTmp.delete()
                    ubootTmp.delete()
                }
            } else {
                TdfuBridge.nativeBootstrap(newFd, detectedSoc, cacheDir, assetManager)
            }

            withContext(Dispatchers.Main) {
                if (result == 0) {
                    appendLog("DFU U-Boot running; device is re-enumerating as a gadget.\n")
                    appendLog("When it reconnects, press READ or WRITE again.\n")
                    updateStatus("Waiting for DFU gadget...")
                } else {
                    appendLog("DFU bootstrap failed (error $result)\n")
                    updateStatus("Bootstrap failed")
                }
                finishOperation()
            }
        }
    }

    // ======================================================================
    // Read Operation
    // ======================================================================

    private fun startReadOperation() {
        if (operationRunning) return

        if (isRemoteMode) {
            startRemoteReadOperation()
            return
        }

        val fd = usbHelper.getFileDescriptor()
        if (fd < 0) {
            appendLog("ERROR: No USB device connected\n")
            return
        }

        // Device still in bootrom: load the DFU U-Boot first. The device
        // re-enumerates as a gadget, then the read is retried.
        if (!isDfuGadget) {
            bootstrapToDfu()
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val socLabel = if (detectedSoc.isEmpty()) "DFU" else detectedSoc.uppercase()
        val filename = "firmware_${socLabel}_${timestamp}.bin"
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        downloadsDir.mkdirs()
        val outputFile = File(downloadsDir, filename)

        operationRunning = true
        setButtonsEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.progress = 0

        appendLog("\n--- READ OPERATION ---\n")
        appendLog("Output: ${outputFile.absolutePath}\n")

        val cacheDir = cacheDir.absolutePath
        val assetManager = assets

        lifecycleScope.launch(Dispatchers.IO) {
            val newFd = withContext(Dispatchers.Main) {
                // A U-Boot DFU gadget's connection is already open and stable.
                // Reopening it (close+open) disturbs the device's USB controller
                // and can wedge EP0 so DFU control transfers hang forever. Reuse
                // the existing fd; a bootrom device is reopened for a fresh fd
                // after its bootrom->firmware transition.
                if (isDfuGadget) {
                    usbHelper.getFileDescriptor()
                } else {
                    val device = usbHelper.getDevice()
                    if (device != null) {
                        usbHelper.closeDevice()
                        usbHelper.openDevice(device)
                    } else -1
                }
            }

            if (newFd < 0) {
                withContext(Dispatchers.Main) {
                    appendLog("ERROR: Failed to reopen device\n")
                    finishOperation()
                }
                return@launch
            }

            val result = TdfuBridge.nativeReadFirmware(
                newFd, detectedSoc, outputFile.absolutePath, cacheDir, assetManager
            )
            val hash = if (result == 0) runCatching { sha256Hex(outputFile) }.getOrNull() else null

            withContext(Dispatchers.Main) {
                if (result == 0) {
                    appendLog("Read complete: ${outputFile.name}\n")
                    hash?.let { appendLog("SHA256: $it\n") }
                    updateStatus("Read complete!")
                } else {
                    appendLog("Read failed (error $result)\n")
                    updateStatus("Read failed")
                }
                finishOperation()
            }
        }
    }

    private fun startRemoteReadOperation() {
        val client = remoteClient ?: return

        operationRunning = true
        setButtonsEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.progress = 0

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val filename = "firmware_${detectedSoc.uppercase()}_${timestamp}.bin"
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        downloadsDir.mkdirs()
        val outputFile = File(downloadsDir, filename)

        appendLog("\n--- REMOTE READ OPERATION ---\n")
        appendLog("Output: ${outputFile.absolutePath}\n")

        lifecycleScope.launch(Dispatchers.IO) {
            val data = client.readFirmware(selectedDeviceIndex, detectedSoc)
            val hash = if (data != null) runCatching { sha256Hex(data) }.getOrNull() else null

            withContext(Dispatchers.Main) {
                if (data != null) {
                    outputFile.writeBytes(data)
                    appendLog("Read complete: ${outputFile.name} (${data.size / 1024} KB)\n")
                    hash?.let { appendLog("SHA256: $it\n") }
                    updateStatus("Read complete!")
                } else {
                    appendLog("Read failed\n")
                    updateStatus("Read failed")
                }
                finishOperation()
            }
        }
    }

    // ======================================================================
    // Write Operation
    // ======================================================================

    private fun pickFileForWrite() {
        if (operationRunning) return
        // Bootrom device: load the DFU U-Boot first; the user retries the
        // write once it re-enumerates as a gadget.
        if (!isRemoteMode && !isDfuGadget) {
            bootstrapToDfu()
            return
        }
        filePickerLauncher.launch("application/octet-stream")
    }

    private fun startWriteOperation(uri: Uri) {
        if (isRemoteMode) {
            startRemoteWriteOperation(uri)
            return
        }

        val fd = usbHelper.getFileDescriptor()
        if (fd < 0) {
            appendLog("ERROR: No USB device connected\n")
            return
        }

        operationRunning = true
        setButtonsEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.progress = 0

        appendLog("\n--- WRITE OPERATION ---\n")
        appendLog("Source: $uri\n")

        val cacheDir = cacheDir.absolutePath
        val assetManager = assets

        lifecycleScope.launch(Dispatchers.IO) {
            val tempFile = File(cacheDir, "firmware_write_temp.bin")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("ERROR: Failed to read file: ${e.message}\n")
                    finishOperation()
                }
                return@launch
            }

            val fileSize = tempFile.length()
            withContext(Dispatchers.Main) {
                appendLog("Firmware file: ${fileSize / 1024} KB\n")
            }

            val newFd = withContext(Dispatchers.Main) {
                // A U-Boot DFU gadget's connection is already open and stable.
                // Reopening it (close+open) disturbs the device's USB controller
                // and can wedge EP0 so DFU control transfers hang forever. Reuse
                // the existing fd; a bootrom device is reopened for a fresh fd
                // after its bootrom->firmware transition.
                if (isDfuGadget) {
                    usbHelper.getFileDescriptor()
                } else {
                    val device = usbHelper.getDevice()
                    if (device != null) {
                        usbHelper.closeDevice()
                        usbHelper.openDevice(device)
                    } else -1
                }
            }

            if (newFd < 0) {
                withContext(Dispatchers.Main) {
                    appendLog("ERROR: Failed to reopen device\n")
                    finishOperation()
                }
                tempFile.delete()
                return@launch
            }

            var result = TdfuBridge.nativeWriteFirmware(
                newFd, detectedSoc, tempFile.absolutePath, cacheDir, assetManager
            )

            // Verify: read the flash back and compare against the same temp file,
            // on the same reopened fd. Opt-in via Settings (off by default).
            val verify = prefs.getBoolean(PREF_VERIFY, false)
            var verifyFailed = false
            if (result == 0 && verify) {
                withContext(Dispatchers.Main) { appendLog("Verifying flash...\n") }
                val vr = TdfuBridge.nativeVerifyFirmware(newFd, tempFile.absolutePath)
                if (vr != 0) { verifyFailed = true; result = vr }
            }

            if (result == 0 && prefs.getBoolean(PREF_REBOOT, false)) {
                withContext(Dispatchers.Main) { appendLog("Rebooting the device...\n") }
                TdfuBridge.nativeReboot(newFd)
            }

            tempFile.delete()

            withContext(Dispatchers.Main) {
                if (result == 0) {
                    appendLog(if (verify) "Write + verify complete!\n" else "Write complete!\n")
                    updateStatus(if (verify) "Write + verify OK" else "Write complete!")
                } else if (verifyFailed) {
                    appendLog("Verify FAILED - flash does not match\n")
                    updateStatus("Verify failed")
                } else {
                    appendLog("Write failed (error $result)\n")
                    updateStatus("Write failed")
                }
                finishOperation()
            }
        }
    }

    private fun startRemoteWriteOperation(uri: Uri) {
        val client = remoteClient ?: return

        operationRunning = true
        setButtonsEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.progress = 0

        appendLog("\n--- REMOTE WRITE OPERATION ---\n")

        lifecycleScope.launch(Dispatchers.IO) {
            val firmwareData = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("ERROR: Failed to read file: ${e.message}\n")
                    finishOperation()
                }
                return@launch
            }

            if (firmwareData == null) {
                withContext(Dispatchers.Main) {
                    appendLog("ERROR: Could not open file\n")
                    finishOperation()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                appendLog("Firmware file: ${firmwareData.size / 1024} KB\n")
            }

            val verify = prefs.getBoolean(PREF_VERIFY, false)
            val result = client.writeFirmware(selectedDeviceIndex, detectedSoc, firmwareData, verify)

            if (result && prefs.getBoolean(PREF_REBOOT, false)) {
                withContext(Dispatchers.Main) { appendLog("Rebooting the device (remote)...\n") }
                client.reboot(selectedDeviceIndex)
            }

            withContext(Dispatchers.Main) {
                if (result) {
                    appendLog(if (verify) "Write + verify complete!\n" else "Write complete!\n")
                    updateStatus(if (verify) "Write + verify OK" else "Write complete!")
                } else {
                    appendLog("Write failed\n")
                    updateStatus("Write failed")
                }
                finishOperation()
            }
        }
    }

    // ======================================================================
    // Diagnostics (read-only eFuse / secure-boot readout) — remote only
    // ======================================================================

    private fun startDiagOperation() {
        if (operationRunning) return

        // Local USB diag is intentionally not wired: tdfu_diag() needs an
        // enumerable usb_manager_t (impossible on Android's no-discovery fd-wrap
        // path), and the connect-time SoC auto-detect stub already clears the
        // eFuse shadow window. Diagnostics run on the daemon over the wire.
        if (!isRemoteMode) {
            appendLog("Info / Diag is available in Remote (daemon) mode only.\n")
            return
        }

        val client = remoteClient ?: return
        val index = selectedDeviceIndex

        // The eFuse is only readable on a pristine bootrom device. Once it has
        // been bootstrapped it becomes the a108:4d44 DFU gadget (stage != 0) and
        // a fresh read would fail — so don't attempt it; show the cached readout.
        val dev = remoteDevices.getOrNull(index)
        if (dev?.stage != 0) {
            val cached = lastDiagText
            if (cached != null) {
                showDiagDialog(cached, cached = true)
            } else {
                appendLog("No cached info yet — tap Info before bootstrapping.\n")
            }
            return
        }

        operationRunning = true
        setButtonsEnabled(false)
        appendLog("\n--- DIAGNOSTICS ---\n")

        lifecycleScope.launch(Dispatchers.IO) {
            val report = client.diag(index)

            withContext(Dispatchers.Main) {
                if (report != null) {
                    lastDiagText = report
                    appendLog("Diagnostics received (${report.length} bytes)\n")
                    showDiagDialog(report)
                } else {
                    // Read failed (e.g. the device just left bootrom): fall back
                    // to the last successful readout if we have one.
                    val cached = lastDiagText
                    if (cached != null) {
                        appendLog("Diagnostics read failed — showing cached readout\n")
                        showDiagDialog(cached, cached = true)
                    } else {
                        appendLog("Diagnostics failed\n")
                        updateStatus("Diagnostics failed")
                    }
                }
                finishOperation()
            }
        }
    }

    /**
     * Show the multi-line diagnostics report in a scrollable monospace dialog.
     * When [cached] is true the readout came from a previous (bootrom-stage)
     * read and the device can no longer be re-read, so a note is appended.
     */
    private fun showDiagDialog(report: String, cached: Boolean = false) {
        val body = if (cached)
            report + "\n\n(cached — device is no longer in bootrom mode)"
        else
            report
        val pad = (16 * resources.displayMetrics.density).toInt()
        val textView = TextView(this).apply {
            text = body
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        // Nest a horizontal scroller inside the vertical one so the wide eFuse
        // hex-dump lines stay aligned and remain fully scrollable.
        val hScroll = android.widget.HorizontalScrollView(this).apply { addView(textView) }
        val vScroll = ScrollView(this).apply { addView(hScroll) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.diag_title)
            .setView(vScroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ======================================================================
    // Remote Bootstrap + custom SPL/U-Boot (remote/daemon mode only)
    // ======================================================================

    /** Read a picked SPL or U-Boot file into memory for the next remote bootstrap. */
    private fun loadCustomBlob(uri: Uri, isSpl: Boolean) {
        val label = if (isSpl) "SPL" else "U-Boot"
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            val name = queryDisplayName(uri) ?: "file"
            withContext(Dispatchers.Main) {
                if (bytes == null || bytes.isEmpty()) {
                    appendLog("ERROR: Failed to read custom $label\n")
                    return@withContext
                }
                if (isSpl) {
                    customSplBytes = bytes
                    customSplName = name
                } else {
                    customUbootBytes = bytes
                    customUbootName = name
                }
                appendLog("Custom $label selected: $name (${bytes.size} bytes)\n")
                updateCustomBlobInfo()
                if (customSplBytes != null && customUbootBytes != null) {
                    val next = if (isRemoteMode) "Bootstrap" else "Read/Write"
                    appendLog("Custom SPL + U-Boot ready — next $next will use them.\n")
                }
            }
        }
    }

    private fun updateCustomBlobInfo() {
        val spl = customSplName?.let { "SPL: $it (${customSplBytes?.size ?: 0} B)" } ?: "SPL: bundled"
        val uboot = customUbootName?.let { "U-Boot: $it (${customUbootBytes?.size ?: 0} B)" }
            ?: "U-Boot: bundled"
        customBlobInfo.text = "$spl\n$uboot"
    }

    private fun clearCustomBlobs() {
        customSplBytes = null
        customSplName = null
        customUbootBytes = null
        customUbootName = null
        updateCustomBlobInfo()
        appendLog("Custom bootloader cleared — using bundled DFU U-Boot.\n")
    }

    /** Best-effort display name for a content Uri (for the chosen-file label). */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Bootstrap the selected remote (bootrom) device into the U-Boot DFU gadget
     * via the daemon, optionally with a client-supplied SPL + U-Boot. After it
     * succeeds the device re-enumerates bootrom -> DFU; poll discover() until it
     * reappears so Read/Write/Info reflect the new stage (no manual reconnect).
     */
    private fun startRemoteBootstrap() {
        if (operationRunning) return
        val client = remoteClient ?: return
        val dev = remoteDevices.getOrNull(selectedDeviceIndex)
        if (dev == null) {
            appendLog("ERROR: No remote device selected\n")
            return
        }
        if (dev.stage != 0) {
            appendLog("Device is not in bootrom stage — bootstrap not needed.\n")
            return
        }

        val index = selectedDeviceIndex
        val variant = detectedSoc
        val spl = customSplBytes
        val uboot = customUbootBytes
        val useCustom = spl != null && spl.isNotEmpty() && uboot != null && uboot.isNotEmpty()

        // Snapshot devices already past bootrom so we can pick out our own
        // re-enumerated gadget afterwards (correct even with several connected).
        val beforeDfu = remoteDevices.filter { it.stage != 0 }
            .map { "${it.bus}:${it.address}" }.toSet()

        operationRunning = true
        setButtonsEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.progress = 0

        appendLog("\n--- REMOTE BOOTSTRAP ---\n")
        if (useCustom) {
            appendLog("Using custom SPL (${spl!!.size} B) + U-Boot (${uboot!!.size} B)\n")
        }
        updateStatus("Bootstrapping via daemon...")

        lifecycleScope.launch(Dispatchers.IO) {
            val ok = client.bootstrap(
                index, variant,
                if (useCustom) spl else null,
                if (useCustom) uboot else null
            )

            if (!ok) {
                withContext(Dispatchers.Main) {
                    appendLog("Remote bootstrap failed\n")
                    updateStatus("Bootstrap failed")
                    finishOperation()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                appendLog("Remote bootstrap complete; waiting for DFU gadget...\n")
                updateStatus("Waiting for DFU gadget...")
            }

            // bootrom -> U-Boot DFU gadget takes a few seconds. The daemon owns
            // the USB, so just poll discover() until our new gadget appears.
            var found: RemoteClient.DeviceEntry? = null
            var devs: List<RemoteClient.DeviceEntry> = emptyList()
            for (i in 0 until 20) {
                delay(700)
                devs = try { client.discover() } catch (e: Exception) { emptyList() }
                found = devs.firstOrNull {
                    it.stage != 0 && "${it.bus}:${it.address}" !in beforeDfu
                }
                if (found != null) break
            }

            withContext(Dispatchers.Main) {
                if (found != null) {
                    remoteDevices = devs
                    selectedDeviceIndex = devs.indexOf(found)
                    // KEEP the bootrom-detected SoC (a DFU gadget reports only a
                    // placeholder variant).
                    socText.text = "SoC: ${detectedSoc.uppercase()} (DFU)"
                    renderDeviceSpinner()
                    appendLog("Device re-enumerated as DFU gadget — ready to Read/Write.\n")
                    updateStatus("DFU gadget ready")
                } else {
                    appendLog("Timed out waiting for DFU gadget; reconnect if needed.\n")
                    updateStatus("Bootstrap done (no re-enumeration seen)")
                }
                finishOperation()
            }
        }
    }

    /** (Re)build the multi-device spinner from remoteDevices + current selection. */
    private fun renderDeviceSpinner() {
        if (remoteDevices.size > 1) {
            val names = remoteDevices.map { it.toString() }
            deviceSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, names
            )
            deviceSpinner.visibility = View.VISIBLE
            deviceSpinner.setSelection(selectedDeviceIndex.coerceIn(0, remoteDevices.size - 1))
        } else {
            deviceSpinner.visibility = View.GONE
        }
    }

    // ======================================================================
    // UI Helpers
    // ======================================================================

    private fun finishOperation() {
        operationRunning = false
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
        if (isRemoteMode) {
            if (remoteClient?.isConnected() == true) setButtonsEnabled(true)
        } else {
            if (usbHelper.isConnected()) setButtonsEnabled(true)
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        readButton.isEnabled = enabled
        writeButton.isEnabled = enabled
        readButton.alpha = if (enabled) 1.0f else 0.5f
        writeButton.alpha = if (enabled) 1.0f else 0.5f
        refreshDiagButton()
        refreshBootstrapButton()
        refreshReleaseFlashButton()
    }

    // ---------------------------------------------------------------
    //  Prebuilt thingino releases (see Releases.kt - no proxy needed)
    // ---------------------------------------------------------------

    private fun setupReleasePicker() {
        releaseCard = findViewById(R.id.releaseCard)
        releaseSpinner = findViewById(R.id.releaseSpinner)
        releaseDeviceSpinner = findViewById(R.id.releaseDeviceSpinner)
        releaseAllCheck = findViewById(R.id.releaseAllCheck)
        releaseInfo = findViewById(R.id.releaseInfo)
        releaseFlashButton = findViewById(R.id.releaseFlashButton)

        val header = findViewById<LinearLayout>(R.id.releaseHeader)
        val body = findViewById<LinearLayout>(R.id.releaseBody)
        val chevron = findViewById<TextView>(R.id.releaseChevron)
        header.setOnClickListener {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            chevron.text = if (show) "▾" else "▸"
            // Load lazily: someone who never opens the panel never calls the API.
            if (show && !releasesLoaded) loadReleases()
        }

        releaseSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = releaseSelectionChanged()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        releaseDeviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshReleaseFlashButton()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        releaseAllCheck.setOnCheckedChangeListener { _, _ -> releaseSelectionChanged() }
        releaseFlashButton.setOnClickListener { flashSelectedRelease() }

        applyReleasesVisible()
    }

    /** The picker is opt-in, like the web flasher's. */
    private fun applyReleasesVisible() {
        releaseCard.visibility =
            if (prefs.getBoolean(PREF_RELEASES, false)) View.VISIBLE else View.GONE
    }

    /**
     * The custom SPL/U-Boot card is opt-in too. Hiding it must not leave a loaded
     * override armed for the next bootstrap: it would be used with nothing on
     * screen saying so, and no way to clear it. So turning it off drops the blobs.
     */
    private fun applyAdvancedVisible() {
        val show = prefs.getBoolean(PREF_ADVANCED, false)
        advancedCard.visibility = if (show) View.VISIBLE else View.GONE
        if (!show && (customSplBytes != null || customUbootBytes != null)) clearCustomBlobs()
    }

    private fun spinnerOf(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun loadReleases() {
        releaseSpinner.adapter = spinnerOf(listOf("Loading releases..."))
        lifecycleScope.launch {
            try {
                releases = Releases.list()
                releasesLoaded = true
                releaseSpinner.adapter = spinnerOf(listOf("Select a release...") + releases.map { it.label })
                appendLog("Found ${releases.size} thingino firmware releases\n")
            } catch (e: Exception) {
                releaseSpinner.adapter = spinnerOf(listOf("Could not load releases"))
                appendLog("ERROR: Could not list releases: ${e.message}\n")
            }
        }
    }

    private fun selectedRelease(): Releases.Release? {
        val pos = releaseSpinner.selectedItemPosition
        return if (releasesLoaded && pos in 1..releases.size) releases[pos - 1] else null
    }

    private fun selectedImage(): String? {
        val pos = releaseDeviceSpinner.selectedItemPosition
        return if (pos in 1..releaseImages.size) releaseImages[pos - 1] else null
    }

    /** Fill the device list for the selected release, narrowed to the detected SoC. */
    private fun releaseSelectionChanged() {
        releaseFilterSoc = detectedSoc
        val rel = selectedRelease()
        if (rel == null) {
            releaseImages = emptyList()
            releaseDeviceSpinner.adapter = spinnerOf(listOf("Select a release first"))
            releaseInfo.text = ""
            refreshReleaseFlashButton()
            return
        }

        val all = releaseAllCheck.isChecked
        releaseImages = Releases.imagesFor(rel, detectedSoc, all)
        if (releaseImages.isEmpty()) {
            releaseDeviceSpinner.adapter = spinnerOf(listOf("No matching images"))
            releaseInfo.text = if (detectedSoc.isNotEmpty() && !all)
                "No ${detectedSoc.uppercase()} image in this release. Tick “Show all devices” to browse every model."
            else
                "This release has no .bin images."
            refreshReleaseFlashButton()
            return
        }

        releaseDeviceSpinner.adapter = spinnerOf(
            listOf("Select your device...") +
                releaseImages.map { it.removePrefix("thingino-").removeSuffix(".bin").replace('_', ' ') })
        releaseInfo.text = if (detectedSoc.isNotEmpty() && !all)
            "Images for ${detectedSoc.uppercase()} in this release: ${releaseImages.size}"
        else
            "Images in this release (all devices): ${releaseImages.size}" +
                if (detectedSoc.isEmpty()) "  Connect a device to filter by SoC." else ""
        refreshReleaseFlashButton()
    }

    /**
     * Same rule as the web flasher: Flash needs a device to be connected, but NOT
     * DFU mode - the write path bootstraps a bootrom itself, so requiring DFU would
     * disable it for exactly the case it exists to handle.
     */
    private fun refreshReleaseFlashButton() {
        // Called from setButtonsEnabled, so this runs on every connect, disconnect
        // and finished operation: if the detected SoC moved, rebuild the list so it
        // matches the board actually attached. releaseSelectionChanged() re-points
        // releaseFilterSoc first, so it lands back here exactly once.
        if (releasesLoaded && releaseFilterSoc != detectedSoc) {
            releaseSelectionChanged()
            return
        }
        val enabled = deviceConnected() && !operationRunning &&
            selectedRelease() != null && selectedImage() != null
        releaseFlashButton.isEnabled = enabled
        releaseFlashButton.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun deviceConnected(): Boolean =
        if (isRemoteMode) remoteClient?.isConnected() == true else usbHelper.isConnected()

    /**
     * Download the chosen image, check it against the published SHA-256, then hand
     * it to the ordinary write path as a file:// Uri - the very same one the file
     * picker feeds. Local (which auto-bootstraps a bootrom) and remote both work
     * unchanged, with no flash logic duplicated here.
     */
    private fun flashSelectedRelease() {
        val rel = selectedRelease() ?: return
        val name = selectedImage() ?: return
        // The button is gated on this too, but a device can go away between the
        // last refresh and the tap.
        if (!deviceConnected() || operationRunning) {
            appendLog("Connect a device first\n")
            return
        }

        operationRunning = true
        setButtonsEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.progress = 0

        val dest = File(cacheDir, "release_download.bin")
        lifecycleScope.launch {
            try {
                appendLog("\n--- PREBUILT RELEASE ---\n")
                appendLog("Downloading $name\n")
                Releases.download(rel.tag, name, dest) { pct, got, total ->
                    runOnUiThread {
                        if (pct >= 0) progressBar.progress = pct
                        progressText.text = if (total > 0)
                            "Downloading  ${got / 1048576} / ${total / 1048576} MB"
                        else
                            "Downloading  ${got / 1048576} MB"
                    }
                }

                val want = Releases.publishedSha256(rel.tag, name)
                if (want == null) {
                    appendLog("No sha256sum published for this image; skipping verification\n")
                } else {
                    val got = Releases.sha256(dest)
                    if (want != got) {
                        // Refuse to flash a corrupt download.
                        appendLog("ERROR: SHA256 mismatch\n  expected $want\n  got      $got\n")
                        dest.delete()
                        finishOperation()
                        return@launch
                    }
                    appendLog("sha256 verified: $got\n")
                }

                // Hand off. startWriteOperation sets its own operationRunning and
                // owns the progress bar from here.
                operationRunning = false
                startWriteOperation(Uri.fromFile(dest))
            } catch (e: Exception) {
                appendLog("ERROR: Download failed: ${e.message}\n")
                dest.delete()
                finishOperation()
            }
        }
    }

    /**
     * Remote bootstrap is REMOTE-ONLY (the local JNI path auto-bootstraps with
     * bundled firmware on Read/Write and takes no custom blobs). Enable it only
     * when connected to the daemon with the selected device still in bootrom
     * stage — once bootstrapped it becomes a DFU gadget (stage != 0).
     */
    private fun refreshBootstrapButton() {
        val dev = remoteDevices.getOrNull(selectedDeviceIndex)
        val enabled = isRemoteMode && !operationRunning &&
            remoteClient?.isConnected() == true && dev?.stage == 0
        bootstrapButton.isEnabled = enabled
        bootstrapButton.alpha = if (enabled) 1.0f else 0.5f
    }

    /**
     * Diag is read-only and REMOTE-ONLY: it only works on a pristine bootrom
     * device via the daemon's usb_manager. The local USB (JNI) path can't run it
     * — Android's fd-wrap path has no enumerable usb_manager_t, and the eFuse
     * shadow window is already cleared by the connect-time SoC auto-detect stub
     * (see TdfuBridge / diag.h).
     *
     * Enabled when connected (remote) AND either the selected device is still in
     * bootrom stage (a fresh read is possible) OR we have a cached readout to
     * show (so the button stays usable after the device is bootstrapped out of
     * bootrom and the eFuse can no longer be read).
     */
    private fun refreshDiagButton() {
        val dev = remoteDevices.getOrNull(selectedDeviceIndex)
        val connected = isRemoteMode && remoteClient?.isConnected() == true
        val enabled = !operationRunning && connected &&
            (dev?.stage == 0 || lastDiagText != null)
        diagButton.isEnabled = enabled
        diagButton.alpha = if (enabled) 1.0f else 0.5f
    }

    /** Settings window: debug logging toggle. */
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_dfu_settings, null)
        val debugSwitch = view.findViewById<MaterialSwitch>(R.id.dlgDebugSwitch)
        val verifySwitch = view.findViewById<MaterialSwitch>(R.id.dlgVerifySwitch)
        val releasesSwitch = view.findViewById<MaterialSwitch>(R.id.dlgReleasesSwitch)
        val advancedSwitch = view.findViewById<MaterialSwitch>(R.id.dlgAdvancedSwitch)
        val rebootSwitch = view.findViewById<MaterialSwitch>(R.id.dlgRebootSwitch)
        val modeGroup = view.findViewById<RadioGroup>(R.id.dlgModeRadioGroup)
        val radioLocal = view.findViewById<RadioButton>(R.id.dlgRadioLocal)
        val radioRemote = view.findViewById<RadioButton>(R.id.dlgRadioRemote)
        val remoteFields = view.findViewById<LinearLayout>(R.id.dlgRemoteFields)
        val hostField = view.findViewById<EditText>(R.id.dlgHostInput)
        val portField = view.findViewById<EditText>(R.id.dlgPortInput)

        val debugWas = prefs.getBoolean(PREF_DEBUG, false)
        debugSwitch.isChecked = debugWas
        verifySwitch.isChecked = prefs.getBoolean(PREF_VERIFY, false)
        releasesSwitch.isChecked = prefs.getBoolean(PREF_RELEASES, false)
        advancedSwitch.isChecked = prefs.getBoolean(PREF_ADVANCED, false)
        rebootSwitch.isChecked = prefs.getBoolean(PREF_REBOOT, false)

        // Prefill the backend from the current state / saved prefs.
        val modeWas = isRemoteMode
        val hostWas = prefs.getString(PREF_HOST, "") ?: ""
        val portWas = prefs.getInt(PREF_PORT, 0)
        (if (modeWas) radioRemote else radioLocal).isChecked = true
        remoteFields.visibility = if (modeWas) View.VISIBLE else View.GONE
        hostField.setText(hostWas)
        if (portWas > 0) portField.setText(portWas.toString())
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            remoteFields.visibility = if (checkedId == R.id.dlgRadioRemote) View.VISIBLE else View.GONE
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                prefs.edit().putBoolean(PREF_VERIFY, verifySwitch.isChecked).apply()
                prefs.edit().putBoolean(PREF_RELEASES, releasesSwitch.isChecked).apply()
                prefs.edit().putBoolean(PREF_ADVANCED, advancedSwitch.isChecked).apply()
                prefs.edit().putBoolean(PREF_REBOOT, rebootSwitch.isChecked).apply()
                applyReleasesVisible()
                applyAdvancedVisible()
                val newDebug = debugSwitch.isChecked
                if (newDebug != debugWas) {
                    prefs.edit().putBoolean(PREF_DEBUG, newDebug).apply()
                    TdfuBridge.nativeSetDebug(newDebug)
                    appendLog("Debug logging ${if (newDebug) "enabled" else "disabled"}\n")
                }

                // Persist + apply the backend choice (only re-apply if it changed).
                val remote = radioRemote.isChecked
                val host = hostField.text.toString().trim()
                val port = portField.text.toString().toIntOrNull() ?: RemoteClient.DEFAULT_PORT
                prefs.edit()
                    .putBoolean(PREF_REMOTE, remote)
                    .putString(PREF_HOST, host)
                    .putInt(PREF_PORT, port)
                    .apply()
                if (remote != modeWas || (remote && (host != hostWas || port != portWas))) {
                    applyMode(remote)
                }
            }
            .show()
    }

    private fun updateStatus(text: String) {
        statusText.text = text
    }

    private fun appendLog(text: String) {
        logText.append(text)
        logScroll.post {
            logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** Lowercase hex SHA-256 of a file, read in chunks. Call off the UI thread. */
    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Lowercase hex SHA-256 of an in-memory buffer. */
    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
