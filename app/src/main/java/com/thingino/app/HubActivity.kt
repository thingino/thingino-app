package com.thingino.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.thingino.app.databinding.ActivityHubBinding
import com.thingino.app.dfu.DfuActivity
import com.thingino.app.provision.CameraDiscovery
import com.thingino.app.provision.DiscoveredCamera
import com.thingino.app.provision.ProvisionActivity
import kotlinx.coroutines.launch

/**
 * Home screen.
 *
 * Deliberately not a mode picker. Plugging an Ingenic device into OTG launches
 * [DfuActivity] straight through its USB_DEVICE_ATTACHED filter and never lands
 * here, so this screen only serves the person who opened the app on purpose.
 * That person is far more often setting up a camera than flashing one, which is
 * why provisioning is the filled button and flashing is the quiet one.
 */
class HubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHubBinding
    private lateinit var discovery: CameraDiscovery

    private val cameras = mutableListOf<DiscoveredCamera>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        discovery = CameraDiscovery(this)

        binding.versionText.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.cameraList.adapter = adapter
        binding.cameraList.setOnItemClickListener { _, _, position, _ ->
            cameras.getOrNull(position)?.let { openWebUi(it) }
        }

        binding.provisionButton.setOnClickListener { startProvisioning() }
        binding.flashButton.setOnClickListener {
            startActivity(Intent(this, DfuActivity::class.java))
        }
        binding.refreshButton.setOnClickListener { refreshCameras() }
        binding.helpButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WIKI_URL)))
        }

        applyProvisioningAvailability()
    }

    override fun onResume() {
        super.onResume()
        refreshCameras()
    }

    /**
     * Provisioning needs WifiNetworkSpecifier, which arrived in Android 10, and
     * the same release removed the old WifiManager.addNetwork() path. There is
     * no route that also covers 26 through 28, so say so plainly rather than
     * letting the button fail. Flashing still works on those devices, which is
     * why the app as a whole stays at minSdk 26.
     */
    private fun applyProvisioningAvailability() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        binding.provisionSubtitle.text = getString(R.string.hub_provision_unsupported)
    }

    private fun startProvisioning() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hub_provision)
                .setMessage(R.string.hub_provision_unsupported)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        startActivity(Intent(this, ProvisionActivity::class.java))
    }

    private fun refreshCameras() = lifecycleScope.launch {
        binding.cameraStatus.text = getString(R.string.hub_searching)
        val found = runCatching { discovery.browse(CameraDiscovery.THINGINO_SERVICE) }
            .getOrDefault(emptyList())

        cameras.clear()
        cameras.addAll(found.sortedBy { it.hostname })

        adapter.clear()
        adapter.addAll(cameras.map { "${it.hostname}\n${it.address}:${it.port}" })
        adapter.notifyDataSetChanged()

        binding.cameraStatus.text = if (cameras.isEmpty()) {
            getString(R.string.hub_none)
        } else {
            getString(R.string.hub_found_fmt, cameras.size)
        }
    }

    private fun openWebUi(camera: DiscoveredCamera) {
        val url = "http://${camera.address}:${camera.port}/"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    companion object {
        private const val WIKI_URL = "https://github.com/themactep/thingino-firmware/wiki"
    }
}
