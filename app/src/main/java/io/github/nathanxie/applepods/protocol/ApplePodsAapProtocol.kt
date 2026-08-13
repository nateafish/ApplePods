package io.github.nathanxie.applepods.protocol

/** Small, testable AAP control-command codec used by the HyperOS hooks. */
object ApplePodsAapProtocol {
    const val MODE_OFF = 1
    const val MODE_NOISE_CANCELLATION = 2
    const val MODE_TRANSPARENCY = 3
    const val MODE_ADAPTIVE = 4

    const val ID_LISTENING_MODE: Byte = 0x0D
    const val ID_CONVERSATION_AWARENESS: Byte = 0x28
    const val ID_SLEEP_DETECTION: Byte = 0x35

    val handshake = byteArrayOf(
        0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )
    val setSpecificFeatures = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xD7.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )
    val requestNotifications = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x0F, 0x00,
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    )

    fun listeningMode(mode: Int): ByteArray {
        require(mode in MODE_OFF..MODE_ADAPTIVE)
        return control(ID_LISTENING_MODE, mode.toByte())
    }

    fun booleanControl(identifier: Byte, enabled: Boolean): ByteArray =
        control(identifier, if (enabled) 0x01 else 0x02)

    fun control(identifier: Byte, value: Byte): ByteArray = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x09, 0x00,
        identifier, value, 0x00, 0x00, 0x00,
    )

    data class ControlState(val identifier: Byte, val value: Byte)

    /** Finds control-state frames even when one socket read contains several AAP frames. */
    fun controlStates(data: ByteArray, length: Int = data.size): List<ControlState> {
        val result = mutableListOf<ControlState>()
        var index = 0
        val safeLength = length.coerceIn(0, data.size)
        while (index + 10 < safeLength) {
            if (
                data[index] == 0x04.toByte() && data[index + 1] == 0x00.toByte() &&
                data[index + 2] == 0x04.toByte() && data[index + 3] == 0x00.toByte() &&
                data[index + 4] == 0x09.toByte() && data[index + 5] == 0x00.toByte()
            ) {
                result += ControlState(data[index + 6], data[index + 7])
                index += 11
            } else {
                index++
            }
        }
        return result
    }
}
