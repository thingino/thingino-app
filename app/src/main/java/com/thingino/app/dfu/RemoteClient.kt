package com.thingino.app.dfu

import com.thingino.dfu.TdfuBridge

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * TCP client for the dfu-remote daemon binary protocol. Pure-Kotlin transport;
 * variant indices from discovery are resolved to names via
 * TdfuBridge.nativeVariantToString (the C tdfu_variant_t enum, the single source
 * of truth) rather than a local table that drifts as per-variant loaders grow.
 */
class RemoteClient(private val callback: TdfuBridge.NativeCallback?) {

    companion object {
        const val MAGIC = 0x54444655 // "TDFU"
        const val VERSION: Byte = 1
        const val DEFAULT_PORT = 5050
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 300_000 // 5 min for long operations

        // Commands
        const val CMD_DISCOVER: Byte = 0x01
        const val CMD_BOOTSTRAP: Byte = 0x02
        const val CMD_WRITE: Byte = 0x03
        const val CMD_READ: Byte = 0x04
        const val CMD_STATUS: Byte = 0x05
        const val CMD_CANCEL: Byte = 0x06
        const val CMD_DIAG: Byte = 0x07
        const val CMD_REBOOT: Byte = 0x08

        // Response status
        const val RESP_OK: Byte = 0x00
        const val RESP_ERROR: Byte = 0x01
        const val RESP_PROGRESS: Byte = 0x02
        const val RESP_LOG: Byte = 0x03

        // No hardcoded variant table: the daemon sends the tdfu_variant_t enum
        // index, resolved via TdfuBridge.nativeVariantToString (the C enum is the
        // single source of truth) so this can't drift as per-variant loaders grow.
    }

    data class DeviceEntry(
        val bus: Int,
        val address: Int,
        val vendor: Int,
        val product: Int,
        val stage: Int,
        val variant: Int
    ) {
        val stageName: String get() = if (stage == 0) "bootrom" else "firmware"
        val variantName: String get() = TdfuBridge.nativeVariantToString(variant)
        override fun toString(): String =
            "${variantName.uppercase()} $stageName (Bus $bus Addr $address)"
    }

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    fun connect(host: String, port: Int = DEFAULT_PORT, token: String? = null): Boolean {
        return try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.soTimeout = READ_TIMEOUT_MS
            socket = sock
            input = sock.getInputStream()
            output = sock.getOutputStream()

            if (token != null) {
                val tokenBytes = token.toByteArray(Charsets.UTF_8)
                val buf = ByteBuffer.allocate(6 + tokenBytes.size).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(MAGIC)
                buf.put(VERSION)
                buf.put(tokenBytes.size.toByte())
                buf.put(tokenBytes)
                output!!.write(buf.array())
                output!!.flush()

                val (status, _) = readResponse()
                if (status != RESP_OK) {
                    callback?.onLog("Authentication failed\n")
                    disconnect()
                    return false
                }
            }
            true
        } catch (e: Exception) {
            callback?.onLog("Connection failed: ${e.message}\n")
            disconnect()
            false
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    fun discover(): List<DeviceEntry> {
        sendRequest(CMD_DISCOVER)
        val payload = drainResponses() ?: return emptyList()

        val devices = mutableListOf<DeviceEntry>()
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        while (buf.remaining() >= 8) {
            devices.add(DeviceEntry(
                bus = buf.get().toInt() and 0xFF,
                address = buf.get().toInt() and 0xFF,
                vendor = buf.short.toInt() and 0xFFFF,
                product = buf.short.toInt() and 0xFFFF,
                stage = buf.get().toInt() and 0xFF,
                variant = buf.get().toInt() and 0xFF
            ))
        }
        return devices
    }

    /**
     * Bootstrap a device into the U-Boot DFU gadget.
     *
     * Wire format (matches the daemon's handle_bootstrap):
     *   [1:device_index][1:variant_len][variant]
     *   then OPTIONAL [4:spl_len][spl][4:uboot_len][uboot]  (big-endian lengths).
     *
     * When BOTH [spl] and [uboot] are supplied (non-null, non-empty) they are
     * appended so the daemon stages them to temp files and uses them in place of
     * the bundled firmware/dfu/<soc>/ blobs (and skips SoC detection). Both or
     * neither — passing only one is treated as none.
     */
    fun bootstrap(
        deviceIndex: Int,
        variant: String,
        spl: ByteArray? = null,
        uboot: ByteArray? = null
    ): Boolean {
        val variantBytes = variant.toByteArray(Charsets.UTF_8)
        val useCustom = spl != null && spl.isNotEmpty() && uboot != null && uboot.isNotEmpty()

        val payload = if (useCustom) {
            val buf = ByteBuffer.allocate(2 + variantBytes.size + 4 + spl!!.size + 4 + uboot!!.size)
                .order(ByteOrder.BIG_ENDIAN)
            buf.put(deviceIndex.toByte())
            buf.put(variantBytes.size.toByte())
            buf.put(variantBytes)
            buf.putInt(spl.size)
            buf.put(spl)
            buf.putInt(uboot.size)
            buf.put(uboot)
            buf.array()
        } else {
            val p = ByteArray(2 + variantBytes.size)
            p[0] = deviceIndex.toByte()
            p[1] = variantBytes.size.toByte()
            System.arraycopy(variantBytes, 0, p, 2, variantBytes.size)
            p
        }

        sendRequest(CMD_BOOTSTRAP, payload)
        return drainResponses() != null
    }

    fun readFirmware(deviceIndex: Int, variant: String): ByteArray? {
        val variantBytes = variant.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(2 + variantBytes.size)
        payload[0] = deviceIndex.toByte()
        payload[1] = variantBytes.size.toByte()
        System.arraycopy(variantBytes, 0, payload, 2, variantBytes.size)

        sendRequest(CMD_READ, payload)
        val response = drainResponses() ?: return null

        if (response.size < 4) {
            callback?.onLog("ERROR: Response too small for CRC32\n")
            return null
        }

        val fwData = response.copyOfRange(0, response.size - 4)
        val receivedCrc = ByteBuffer.wrap(response, response.size - 4, 4)
            .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

        val crc = CRC32()
        crc.update(fwData)
        if (crc.value != receivedCrc) {
            callback?.onLog("ERROR: CRC32 mismatch (expected ${receivedCrc.toString(16)}, got ${crc.value.toString(16)})\n")
            return null
        }

        return fwData
    }

    fun writeFirmware(deviceIndex: Int, variant: String, firmwareData: ByteArray, verify: Boolean = false): Boolean {
        val variantBytes = variant.toByteArray(Charsets.UTF_8)

        val crc = CRC32()
        crc.update(firmwareData)
        val crcValue = crc.value.toInt()

        // Wire format the daemon parses:
        //   [idx][variant_len][variant][alt_len][alt][fw_len][fw][crc][verify?]
        // alt is empty here (alt_len = 0 => daemon's default alt 0 = flash).
        // The trailing verify byte is optional (older daemons stop after crc).
        val buf = ByteBuffer.allocate(2 + variantBytes.size + 1 + 4 + firmwareData.size + 4 + if (verify) 1 else 0)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(deviceIndex.toByte())
        buf.put(variantBytes.size.toByte())
        buf.put(variantBytes)
        buf.put(0.toByte())  // alt_len = 0
        buf.putInt(firmwareData.size)
        buf.put(firmwareData)
        buf.putInt(crcValue)
        if (verify) buf.put(1.toByte())

        sendRequest(CMD_WRITE, buf.array())
        return drainResponses() != null
    }

    /** Reset the SoC (used after a flash). The daemon runs tdfu_dfu_reboot,
     *  which tolerates the reset disconnect and replies OK. */
    fun reboot(deviceIndex: Int): Boolean {
        sendRequest(CMD_REBOOT, byteArrayOf(deviceIndex.toByte()))
        return drainResponses() != null
    }

    fun cancel(): Boolean {
        sendRequest(CMD_CANCEL)
        return drainResponses() != null
    }

    /**
     * Read-only eFuse / secure-boot diagnostics for a bootrom-stage device.
     * Request payload is a single byte: the device index. The daemon replies
     * with RESP_OK and the formatted diagnostics report as UTF-8 text.
     * Returns the report on success, or null on RESP_ERROR (logged via callback).
     */
    fun diag(deviceIndex: Int): String? {
        val payload = byteArrayOf(deviceIndex.toByte())
        sendRequest(CMD_DIAG, payload)
        val response = drainResponses() ?: return null
        return String(response, Charsets.UTF_8)
    }

    // --- Private protocol helpers ---

    private fun sendRequest(command: Byte, payload: ByteArray = ByteArray(0)) {
        val out = output ?: throw IllegalStateException("Not connected")
        val header = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        header.putInt(MAGIC)
        header.put(VERSION)
        header.put(command)
        header.putInt(payload.size)
        out.write(header.array())
        if (payload.isNotEmpty()) {
            out.write(payload)
        }
        out.flush()
    }

    private fun readResponse(): Pair<Byte, ByteArray> {
        val inp = input ?: throw IllegalStateException("Not connected")
        val headerBuf = readExact(inp, 10)
        val header = ByteBuffer.wrap(headerBuf).order(ByteOrder.BIG_ENDIAN)

        val magic = header.int
        if (magic != MAGIC) {
            throw RuntimeException("Bad magic: 0x${magic.toString(16)}")
        }
        header.get() // version
        val status = header.get()
        val payloadLen = header.int

        val payload = if (payloadLen > 0) readExact(inp, payloadLen) else ByteArray(0)
        return Pair(status, payload)
    }

    private fun drainResponses(): ByteArray? {
        while (true) {
            val (status, payload) = readResponse()
            when (status) {
                RESP_PROGRESS -> {
                    if (payload.size >= 4) {
                        val percent = payload[0].toInt() and 0xFF
                        val msgLen = ((payload[2].toInt() and 0xFF) shl 8) or
                                     (payload[3].toInt() and 0xFF)
                        val msg = if (msgLen > 0 && payload.size >= 4 + msgLen)
                            String(payload, 4, msgLen, Charsets.UTF_8) else ""
                        callback?.onProgress(percent, "remote", msg)
                    }
                }
                RESP_LOG -> {
                    // Raw library log output forwarded by the daemon (same as
                    // local stderr). Surface it and keep reading until the
                    // terminal RESP_OK / RESP_ERROR arrives.
                    if (payload.isNotEmpty())
                        callback?.onLog(String(payload, Charsets.UTF_8))
                }
                RESP_OK -> return payload
                RESP_ERROR -> {
                    val errMsg = if (payload.isNotEmpty())
                        String(payload, Charsets.UTF_8) else "Unknown error"
                    callback?.onLog("ERROR: $errMsg\n")
                    return null
                }
                else -> {
                    callback?.onLog("ERROR: Unexpected response status $status\n")
                    return null
                }
            }
        }
    }

    private fun readExact(stream: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = stream.read(buf, offset, count - offset)
            if (n < 0) throw RuntimeException("Connection closed")
            offset += n
        }
        return buf
    }
}
