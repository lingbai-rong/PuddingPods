package moe.chenxy.oppopods.pods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesTest {
    @Test
    fun `exact model name is recognized`() {
        assertTrue(isAdaptiveSupportedByName("OPPO Enco Free4"))
        assertTrue(isSpatialAudioSupportedByName("OPPO Enco X3"))
        assertTrue(isSpatialSoundSwitchSupportedByName("OPPO Enco Air5"))
    }

    @Test
    fun `model suffix is recognized`() {
        assertTrue(isAdaptiveSupportedByName("OPPO Enco Free4（丹拿版）"))
    }

    @Test
    fun `blank or punctuation-only name is not recognized`() {
        assertFalse(isAdaptiveSupportedByName(""))
        assertFalse(isSpatialAudioSupportedByName("   "))
        assertFalse(isSpatialSoundSwitchSupportedByName("---"))
    }

    @Test
    fun `shorter model prefix is not treated as a supported model`() {
        assertFalse(isAdaptiveSupportedByName("OPPO Enco Free"))
    }
}
