package com.thingino.app.dfu

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Manages Android USB Host API interactions for Ingenic devices.
 *
 * Handles device discovery, permission requests, and provides the raw
 * file descriptor needed by libusb_wrap_sys_device() on the native side.
 */
class UsbHelper(private val context: Context) {

    companion object {
        private const val TAG = "UsbHelper"
        const val ACTION_USB_PERMISSION = "com.thingino.dfu.USB_PERMISSION"

        // U-Boot DFU gadget ("USB download gadget")
        const val PID_DFU_GADGET = 0x4D44

        // Ingenic USB VID/PID pairs
        private val INGENIC_DEVICES = listOf(
            Pair(0xA108, 0xC309),  // T30/T31/T40 series bootrom (alt VID)
            Pair(0x601A, 0x4770),  // T20/T21 bootrom
            Pair(0x601A, 0xC309),  // T30/T31/T40 series bootrom
            Pair(0x601A, 0x601A),  // Alternative bootrom
            Pair(0xA108, 0x8887),  // Firmware stage (alt VID)
            Pair(0x601A, 0x8887),  // Firmware stage
            Pair(0xA108, 0x601E),  // Alternative firmware stage
            Pair(0x601A, 0x601E),  // Alternative firmware stage
            Pair(0xA108, PID_DFU_GADGET),  // U-Boot DFU gadget (alt VID)
            Pair(0x601A, PID_DFU_GADGET),  // U-Boot DFU gadget
        )

        fun isIngenicDevice(device: UsbDevice): Boolean {
            return INGENIC_DEVICES.any { (vid, pid) ->
                device.vendorId == vid && device.productId == pid
            }
        }

        /**
         * True if the device is a running U-Boot DFU gadget: the standard DFU
         * gadget PID (a108/601a:4d44), or any device exposing a DFU interface
         * (class 0xFE / subclass 0x01), which catches a gadget that shares the
         * bootrom PID (0xC309). Detection is by descriptor, not PID alone.
         */
        fun isDfuGadget(device: UsbDevice): Boolean {
            if (device.productId == PID_DFU_GADGET) return true
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                if (itf.interfaceClass == 0xFE && itf.interfaceSubclass == 0x01) return true
            }
            return false
        }
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var currentDevice: UsbDevice? = null

    interface DeviceListener {
        fun onDeviceAttached(device: UsbDevice)
        fun onDeviceDetached()
        fun onPermissionGranted(device: UsbDevice)
        fun onPermissionDenied(device: UsbDevice)
    }

    private var listener: DeviceListener? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        if (granted) {
                            Log.i(TAG, "USB permission granted for ${device.deviceName}")
                            listener?.onPermissionGranted(device)
                        } else {
                            Log.w(TAG, "USB permission denied for ${device.deviceName}")
                            listener?.onPermissionDenied(device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && isIngenicDevice(device)) {
                        Log.i(TAG, "Ingenic device attached: VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)}")
                        listener?.onDeviceAttached(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && device == currentDevice) {
                        Log.i(TAG, "Ingenic device detached")
                        closeDevice()
                        listener?.onDeviceDetached()
                    }
                }
            }
        }
    }

    fun setListener(l: DeviceListener) {
        listener = l
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // ACTION_USB_PERMISSION is our own broadcast, so it must never be
        // exported. ContextCompat applies the flag on 33+ and is a no-op below.
        ContextCompat.registerReceiver(
            context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

    /**
     * Scan for already-connected Ingenic devices.
     * Returns the first one found, or null.
     */
    fun findDevice(): UsbDevice? {
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            if (isIngenicDevice(device)) {
                Log.i(TAG, "Found Ingenic device: ${device.deviceName} " +
                        "VID=0x${device.vendorId.toString(16)} " +
                        "PID=0x${device.productId.toString(16)}")
                return device
            }
        }
        return null
    }

    /**
     * Request USB permission for a device.
     */
    fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            listener?.onPermissionGranted(device)
            return
        }

        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Open a USB device and return the native file descriptor.
     * The fd can be passed directly to libusb_wrap_sys_device().
     * Returns -1 on failure.
     */
    fun openDevice(device: UsbDevice): Int {
        closeDevice()

        if (!usbManager.hasPermission(device)) {
            Log.e(TAG, "No permission for device ${device.deviceName}")
            return -1
        }

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "Failed to open device ${device.deviceName}")
            return -1
        }

        connection = conn
        currentDevice = device

        val fd = conn.fileDescriptor
        Log.i(TAG, "Opened device ${device.deviceName}, fd=$fd")
        return fd
    }

    /**
     * Close the current USB device connection.
     */
    fun closeDevice() {
        connection?.close()
        connection = null
        currentDevice = null
    }

    /**
     * Get the currently connected device, or null.
     */
    fun getDevice(): UsbDevice? = currentDevice

    /**
     * Get the file descriptor of the current connection, or -1.
     */
    fun getFileDescriptor(): Int = connection?.fileDescriptor ?: -1

    /**
     * Check if we have an open connection.
     */
    fun isConnected(): Boolean = connection != null

    /**
     * Get a descriptive string for a device's VID:PID.
     */
    fun getDeviceDescription(device: UsbDevice): String {
        val vid = device.vendorId
        val pid = device.productId
        val stage = when (pid) {
            0x4770, 0xC309, 0x601A -> "bootrom"
            0x8887, 0x601E -> "firmware"
            PID_DFU_GADGET -> "U-Boot DFU"
            else -> "unknown"
        }
        return "Ingenic ${vid.toString(16).uppercase()}:${pid.toString(16).uppercase()} ($stage)"
    }
}
