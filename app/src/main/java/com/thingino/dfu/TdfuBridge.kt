package com.thingino.dfu

import android.content.res.AssetManager

/**
 * JNI bridge to libtdfu native code.
 *
 * All native methods operate on a USB file descriptor obtained from
 * Android's UsbDeviceConnection. The fd is passed to libusb_wrap_sys_device()
 * on the native side.
 */
object TdfuBridge {

    init {
        System.loadLibrary("tdfu_jni")
    }

    /** Callback interface for log messages and progress updates from native code. */
    interface NativeCallback {
        fun onLog(message: String)
        fun onProgress(percent: Int, stage: String, message: String)
    }

    /**
     * Set the callback object that receives log and progress messages.
     * Must be called before any operation.
     */
    @JvmStatic
    external fun nativeSetCallback(callback: NativeCallback?)

    /**
     * Detect the SoC connected via USB.
     * @param fd USB device file descriptor from UsbDeviceConnection
     * @return SoC variant name (e.g., "t31x", "t40") or empty string on failure
     */
    @JvmStatic
    external fun nativeDetectSoc(fd: Int): String

    /**
     * Map a tdfu_variant_t enum index (as sent by the dfu-remote daemon's
     * discovery) to its canonical variant name, via the C tdfu_variant_to_string
     * so the Kotlin side keeps no variant table of its own to drift from the enum.
     * @return e.g. "t23n", "t41nq", or "unknown" for an unmapped index
     */
    @JvmStatic
    external fun nativeVariantToString(variant: Int): String

    /**
     * Bootstrap a device: load DDR config, SPL, and U-Boot into SDRAM.
     * @param fd USB device file descriptor
     * @param variant SoC variant name (e.g., "t31x")
     * @param firmwareDir Cache directory for extracted firmware files
     * @param assetManager Android AssetManager for reading bundled firmware
     * @return 0 on success, negative error code on failure
     */
    @JvmStatic
    external fun nativeBootstrap(
        fd: Int,
        variant: String,
        firmwareDir: String,
        assetManager: AssetManager
    ): Int

    /**
     * Bootstrap a device using a caller-supplied SPL + U-Boot read from two file
     * paths (instead of the bundled firmware assets). The caller owns and deletes
     * the temp files; the native side only reads them.
     * @param fd USB device file descriptor
     * @param splPath path to the custom SPL/TPL blob
     * @param ubootPath path to the custom U-Boot blob
     * @return 0 on success, negative error code on failure
     */
    @JvmStatic
    external fun nativeBootstrapFiles(
        fd: Int,
        splPath: String,
        ubootPath: String
    ): Int

    /**
     * Read firmware from device flash to a file.
     * Handles bootstrap automatically if device is in bootrom stage.
     * @param fd USB device file descriptor
     * @param variant SoC variant name
     * @param outputFile Path to save firmware dump
     * @param firmwareDir Cache directory for extracted firmware files
     * @param assetManager Android AssetManager
     * @return 0 on success, negative error code on failure
     */
    @JvmStatic
    external fun nativeReadFirmware(
        fd: Int,
        variant: String,
        outputFile: String,
        firmwareDir: String,
        assetManager: AssetManager
    ): Int

    /**
     * Write firmware from a file to device flash.
     * Handles bootstrap automatically if device is in bootrom stage.
     * @param fd USB device file descriptor
     * @param variant SoC variant name
     * @param inputFile Path to firmware file to write
     * @param firmwareDir Cache directory for extracted firmware files
     * @param assetManager Android AssetManager
     * @return 0 on success, negative error code on failure
     */
    @JvmStatic
    external fun nativeWriteFirmware(
        fd: Int,
        variant: String,
        inputFile: String,
        firmwareDir: String,
        assetManager: AssetManager
    ): Int

    /**
     * Read the just-written flash back and compare against inputFile.
     * Returns 0 on match, non-zero on mismatch or read error.
     */
    external fun nativeVerifyFirmware(fd: Int, inputFile: String): Int

    /** Reboot the SoC via the loader's "reboot" alt (used after a flash). 0 on success. */
    external fun nativeReboot(fd: Int): Int

    /**
     * Enable or disable verbose debug logging in libtdfu.
     */
    @JvmStatic
    external fun nativeSetDebug(enabled: Boolean)

    // NOTE: There is deliberately no nativeDiag() / local-USB diagnostics path.
    // libtdfu's tdfu_diag() takes a usb_manager_t and enumerates devices via
    // libusb_get_device_list(), which cannot work on Android's fd-wrap path
    // (the JNI uses LIBUSB_OPTION_NO_DEVICE_DISCOVERY). Worse, the eFuse shadow
    // window that diag reads is cleared to zero by any stage1 stub — including
    // the SoC auto-detect that already runs at connect time — so a local read
    // would return zeros. Diagnostics are therefore REMOTE-ONLY: the daemon runs
    // tdfu_diag() on a pristine bootrom and ships the formatted text over the
    // wire (RemoteClient.diag / CMD_DIAG). See libtdfu/include/tdfu/diag.h.
}
