package com.thingino.app

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.thingino.app.databinding.ActivityHubBinding

/**
 * Home screen.
 *
 * Deliberately not a mode picker. Plugging an Ingenic device into OTG launches
 * the flashing activity directly through its USB_DEVICE_ATTACHED filter and
 * never lands here, so this screen only has to serve the person who opened the
 * app on purpose.
 */
class HubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHubBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.versionText.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
        binding.cameraList.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        binding.cameraStatus.text = getString(R.string.hub_none)
    }
}
