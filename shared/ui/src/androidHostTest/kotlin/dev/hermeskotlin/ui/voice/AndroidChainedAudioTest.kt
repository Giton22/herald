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

    @Test
    fun aChainedChatUsesTheSpeakerOrHeadsetAndRestoresItsMode() {
        for (type in listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET)) {
            val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            val selected = device(type)
            shadowOf(manager).setAvailableCommunicationDevices(listOf(speaker, selected))
            manager.mode = AudioManager.MODE_NORMAL
            val close = AndroidSpeechPlayer(context).chainedChatAudio()
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
            assertEquals(if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) speaker else selected, manager.communicationDevice)
            close()
            close()
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
            assertNull(manager.communicationDevice)
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
    fun anExistingCommunicationRouteIsRestored() {
        val headset = device(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        shadowOf(manager).setAvailableCommunicationDevices(listOf(headset))
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.setCommunicationDevice(headset)
        AndroidSpeechPlayer(context).chainedChatAudio()()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(headset, manager.communicationDevice)
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
}
