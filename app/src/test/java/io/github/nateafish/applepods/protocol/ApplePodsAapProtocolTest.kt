package io.github.nateafish.applepods.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplePodsAapProtocolTest {
    @Test
    fun extendedInitMatchesH2FeaturePacket() {
        assertArrayEquals(
            byteArrayOf(
                0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xD7.toByte(), 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            ),
            ApplePodsAapProtocol.setSpecificFeatures,
        )
    }

    @Test
    fun adaptiveModeUsesListeningModeControl() {
        assertArrayEquals(
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, 0x04, 0x00, 0x00, 0x00),
            ApplePodsAapProtocol.listeningMode(ApplePodsAapProtocol.MODE_ADAPTIVE),
        )
    }

    @Test
    fun booleanControlsUseAppleEnabledAndDisabledValues() {
        assertEquals(
            0x01,
            ApplePodsAapProtocol.booleanControl(
                ApplePodsAapProtocol.ID_CONVERSATION_AWARENESS,
                true,
            )[7].toInt(),
        )
        assertEquals(
            0x02,
            ApplePodsAapProtocol.booleanControl(
                ApplePodsAapProtocol.ID_SLEEP_DETECTION,
                false,
            )[7].toInt(),
        )
    }

    @Test
    fun adaptiveAudioNoiseUsesCapodInvertedWireValue() {
        assertArrayEquals(
            byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x2E, 0x14, 0x00, 0x00, 0x00),
            ApplePodsAapProtocol.adaptiveAudioNoise(80),
        )
        assertEquals(80, ApplePodsAapProtocol.decodeAdaptiveAudioNoise(0x14))
        assertEquals(0, ApplePodsAapProtocol.decodeAdaptiveAudioNoise(0x64))
        assertEquals(100, ApplePodsAapProtocol.decodeAdaptiveAudioNoise(0x00))
    }

    @Test
    fun parsesSeveralControlFramesFromOneRead() {
        val first = ApplePodsAapProtocol.booleanControl(
            ApplePodsAapProtocol.ID_CONVERSATION_AWARENESS,
            true,
        )
        val second = ApplePodsAapProtocol.listeningMode(ApplePodsAapProtocol.MODE_ADAPTIVE)
        val data = byteArrayOf(0x55) + first + byteArrayOf(0x66, 0x77) + second

        assertEquals(
            listOf(
                ApplePodsAapProtocol.ControlState(
                    ApplePodsAapProtocol.ID_CONVERSATION_AWARENESS,
                    0x01,
                ),
                ApplePodsAapProtocol.ControlState(
                    ApplePodsAapProtocol.ID_LISTENING_MODE,
                    0x04,
                ),
            ),
            ApplePodsAapProtocol.controlStates(data),
        )
    }
}
