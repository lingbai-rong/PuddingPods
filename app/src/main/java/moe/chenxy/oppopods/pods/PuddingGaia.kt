package moe.chenxy.oppopods.pods

/**
 * Minimal GAIA-over-RFCOMM codec used by MOONDROP PUDDING (MD-TWS-056).
 *
 * The MOONDROP app's bundled Qualcomm GAIA client uses the RFCOMM v4 frame layout:
 *   FF <protocolVersion> <flags> <payloadLength> <payload...>
 * for short packets without checksum/length-extension flags.
 */
object PuddingGaia {
    const val SOF: Int = 0xFF
    const val PROTOCOL_V1: Int = 0x01
    const val PROTOCOL_V4: Int = 0x04
    const val FLAG_CHECKSUM: Int = 0x01
    const val FLAG_LENGTH_EXTENSION: Int = 0x02

    /** Size of the GAIA command header (`vendor` + `command word`), which the length omits. */
    const val COMMAND_HEADER_SIZE: Int = 4

    // Standard Bluetooth Serial Port Profile UUID used by PUDDING.
    const val SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"

    // GAIA v1 API-version request used by the official app before switching to v4.
    val QUERY_GAIA_VERSION: ByteArray = byteArrayOf(0x00, 0x0A, 0x03, 0x00)

    // Qualcomm GAIA v3 command words encode feature + packet type + command.
    // The official app logs shown by the user are RESPONSE packets, whose command word
    // has the RESPONSE packet-type bit set.  TX must use COMMAND words instead.
    //
    // ANC_V2 feature 0x20: COMMAND 0x4000, RESPONSE 0x4100.
    val QUERY_NOISE_MODE: ByteArray = byteArrayOf(0x00, 0x1D, 0x40, 0x03)

    // DAC_GAIN feature 0x0F: COMMAND 0x1E00, RESPONSE 0x1F00.
    // LED feature 0x13: COMMAND 0x2600, RESPONSE 0x2700.
    // Sub-command 0x01 = GET, 0x02 = SET.
    val QUERY_GAIN_LEVEL: ByteArray = byteArrayOf(0x00, 0x1D, 0x1E, 0x01)
    val QUERY_LED_STATE: ByteArray = byteArrayOf(0x00, 0x1D, 0x26, 0x01)

    // BATTERY feature 0x0D: COMMAND 0x1A00, RESPONSE 0x1B00.
    // The official PUDDING app polls these as 4-byte + 6-byte payloads: first supported
    // batteries, then GET_BATTERY_LEVELS for LEFT(1) and RIGHT(2). PUDDING also returns
    // charging-case id 3 in the level response.
    val QUERY_SUPPORTED_BATTERIES: ByteArray = byteArrayOf(0x00, 0x1D, 0x1A, 0x00)
    val QUERY_BATTERY_LEVELS: ByteArray = byteArrayOf(
        0x00, 0x1D, 0x1A, 0x01, 0x01, 0x02
    )

    enum class NoiseMode(val wireValue: Int, val uiStatus: Int) {
        OFF(0x00, 1),
        ADAPTIVE(0x01, 4),
        TRANSPARENCY(0x02, 3),
        WIND_REDUCTION(0x03, 5),
        BASIC(0x04, 2);

        companion object {
            fun fromWire(value: Int): NoiseMode? = entries.firstOrNull { it.wireValue == value }
            fun fromUiStatus(status: Int): NoiseMode? = entries.firstOrNull { it.uiStatus == status }
        }
    }

    // PUDDING's DAC gain wire direction is reversed from the UI order:
    // UI low/medium/high = 0/1/2, while the earbud expects wire 2/1/0.
    enum class GainLevel(val uiValue: Int, val wireValue: Int) {
        LOW(0, 0x02),
        MEDIUM(1, 0x01),
        HIGH(2, 0x00);

        companion object {
            fun fromUi(value: Int): GainLevel? = entries.firstOrNull { it.uiValue == value }
            fun fromWire(value: Int): GainLevel? = entries.firstOrNull { it.wireValue == value }
        }
    }

    fun setNoiseMode(mode: NoiseMode): ByteArray = byteArrayOf(
        0x00, 0x1D, 0x40, 0x04, mode.wireValue.toByte()
    )

    fun setGainLevel(level: GainLevel): ByteArray = byteArrayOf(
        0x00, 0x1D, 0x1E, 0x02, level.wireValue.toByte()
    )

    fun setLedEnabled(enabled: Boolean): ByteArray = byteArrayOf(
        0x00, 0x1D, 0x26, 0x02, if (enabled) 0x01 else 0x00
    )

    /**
     * Wraps a payload (`00 1D <cmd> <sub> [params...]`) in the RFCOMM v4 frame.
     *
     * The length field must count only the parameters that follow the command header, exactly
     * like the earbud does on the way back; the official app sends `FF 04 00 02 00 1D 1A 01 01 02`
     * for a two-parameter battery query, not `06`. Declaring the whole payload still works
     * because the earbud ignores a length that overruns the frame, but it breaks the symmetry
     * [StreamDecoder] relies on and makes the wire trace disagree with the reference captures.
     */
    fun frame(protocolVersion: Int, payload: ByteArray): ByteArray {
        require(payload.size >= COMMAND_HEADER_SIZE) { "PUDDING GAIA payload lacks a command header" }
        val parameterLength = payload.size - COMMAND_HEADER_SIZE
        require(parameterLength <= 0xFF) { "PUDDING GAIA short frame payload too large" }
        return byteArrayOf(
            SOF.toByte(),
            protocolVersion.toByte(),
            0x00, // no checksum, no length extension
            parameterLength.toByte(),
        ) + payload
    }

    fun v1(payload: ByteArray): ByteArray = frame(PROTOCOL_V1, payload)
    fun v4(payload: ByteArray): ByteArray = frame(PROTOCOL_V4, payload)

    /**
     * Stateful RFCOMM stream decoder. InputStream reads may split or merge GAIA frames.
     * PUDDING traffic observed so far uses short, no-checksum frames; checksum and extended
     * length are supported here as well for robustness.
     *
     * PUDDING's length field counts only the parameters that follow the 4-byte command header
     * (the same convention HyperEars documents), so the parameters a frame carries are always
     * `4 + payloadLength` bytes long. Treating `payloadLength` as the whole parameter block
     * truncates every response by the 4 command bytes: the battery response
     * `00 1D 1B 01 (01 LL)(02 RR)(03 CC)` then decodes as a single `[01, LL]` pair, which is
     * why only the left earbud's level ever reached the UI.
     */
    class StreamDecoder {
        private val pending = ArrayList<Byte>()

        fun reset() = pending.clear()

        fun feed(chunk: ByteArray): List<ByteArray> {
            chunk.forEach { pending.add(it) }
            val result = mutableListOf<ByteArray>()

            while (true) {
                while (pending.isNotEmpty() && (pending[0].toInt() and 0xFF) != SOF) {
                    pending.removeAt(0)
                }
                if (pending.size < 4) break

                val flags = pending[2].toInt() and 0xFF
                val extended = flags and FLAG_LENGTH_EXTENSION != 0
                val headerSize = if (extended) 5 else 4
                if (pending.size < headerSize) break

                val payloadLength = if (extended) {
                    ((pending[3].toInt() and 0xFF) shl 8) or (pending[4].toInt() and 0xFF)
                } else {
                    pending[3].toInt() and 0xFF
                }
                val checksumSize = if (flags and FLAG_CHECKSUM != 0) 1 else 0
                // The declared length covers the command header + parameters, but not the
                // framing header itself, so the command header has to be added back here.
                val frameSize = headerSize + COMMAND_HEADER_SIZE + payloadLength + checksumSize
                if (payloadLength < 0 || frameSize > 65540) {
                    pending.removeAt(0)
                    continue
                }
                if (pending.size < frameSize) break

                val payload = ByteArray(COMMAND_HEADER_SIZE + payloadLength) {
                    pending[headerSize + it]
                }
                result += payload
                // Anything past frameSize is not covered by the declared length: PUDDING
                // occasionally appends a stray byte (0x40 / 0x86) that must not be parsed.
                repeat(frameSize) { pending.removeAt(0) }
            }
            return result
        }
    }
}
