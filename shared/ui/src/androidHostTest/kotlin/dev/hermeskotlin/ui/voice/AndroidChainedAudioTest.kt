package dev.hermeskotlin.ui.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.hermeskotlin.core.voice.AndroidSpeechPlayer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidChainedAudioTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

    @Test
    fun aChainedChatOnThePhoneSpeakerUsesCallRoutingAndRestoresItsMode() {
        shadowOf(manager).setOutputDevices(listOf(speaker))
        shadowOf(manager).setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), speaker))
        manager.mode = AudioManager.MODE_NORMAL
        val close = AndroidSpeechPlayer(context).chainedChatAudio()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(speaker, manager.communicationDevice)
        close()
        close()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNull(manager.communicationDevice)
    }

    /** Earbuds without a call profile would move to the phone's speaker, and the rest would drop to call quality. */
    @Test
    fun headphonesAndBluetoothKeepTheirUsualRoute() {
        for (type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET)) {
            shadowOf(manager).setOutputDevices(listOf(speaker, device(type)))
            shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
            manager.mode = AudioManager.MODE_NORMAL
            val close = AndroidSpeechPlayer(context).chainedChatAudio()
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
            assertNull(manager.communicationDevice)
            close()
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        }
    }

    @Test
    fun aStaleCloseCannotRestoreOverANewerChat() {
        val player = AndroidSpeechPlayer(context)
        manager.mode = AudioManager.MODE_NORMAL
        val first = player.chainedChatAudio()
        val second = player.chainedChatAudio()
        first()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        second()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    @Test
    fun theModeBeforeTheChatComesBack() {
        shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker))
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        AndroidSpeechPlayer(context).chainedChatAudio()()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertNull(manager.communicationDevice)
    }

    @Test
    @Config(sdk = [30])
    @Suppress("DEPRECATION")
    fun olderAndroidRestoresItsSpeakerphoneSetting() {
        manager.mode = AudioManager.MODE_NORMAL
        manager.isSpeakerphoneOn = false
        val close = AndroidSpeechPlayer(context).chainedChatAudio()
        assertEquals(true, manager.isSpeakerphoneOn)
        close()
        assertEquals(false, manager.isSpeakerphoneOn)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    @Test
    @Config(sdk = [30])
    @Suppress("DEPRECATION")
    fun olderAndroidLeavesBluetoothEarbudsAlone() {
        shadowOf(manager).setOutputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        manager.mode = AudioManager.MODE_NORMAL
        manager.isSpeakerphoneOn = false
        val close = AndroidSpeechPlayer(context).chainedChatAudio()
        assertEquals(false, manager.isSpeakerphoneOn)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        close()
    }
}
