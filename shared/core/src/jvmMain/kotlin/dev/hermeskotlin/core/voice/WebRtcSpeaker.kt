package dev.hermeskotlin.core.voice

import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioLayer

/** Each playback owns its output module and leaves device selection, mute and volume defaults alone. */
internal class WebRtcSpeaker : SpeechOutput {
    private var module: AudioDeviceModule? = null

    override fun start(fill: (ByteArray, Int, Int) -> Unit, fail: (Throwable) -> Unit) {
        check(module == null)
        try {
            val native = AudioDeviceModule(AudioLayer.kLinuxPulseAudio)
            module = native
            val device = MediaDevices.getDefaultAudioRenderDevice() ?: error("No default speaker")
            native.setPlayoutDevice(device)
            // The device asks at its server's own rate and channel count; WebRTC's "bytes per sample" is per frame
            // of all channels.
            native.setAudioSource { bytes, samples, bytesPerSample, channels, rate ->
                bytes.fill(0)
                if (channels !in 1..2 || bytesPerSample != channels * 2 || rate !in 8_000..192_000 || samples <= 0 ||
                    samples.toLong() * bytesPerSample != bytes.size.toLong()) {
                    fail(IllegalStateException("The speaker returned an unsupported audio format."))
                } else {
                    try { fill(bytes, rate, channels) } catch (caught: Throwable) {
                        bytes.fill(0)
                        fail(caught)
                    }
                }
                samples
            }
            native.initPlayout()
            native.startPlayout()
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw IllegalStateException("The speaker isn't available.", failure)
        }
    }

    override fun close() {
        val native = module ?: return
        module = null
        var failure: Throwable? = null
        try { native.stopPlayout() } catch (caught: Throwable) { failure = caught }
        try { native.dispose() } catch (caught: Throwable) {
            if (failure == null) failure = caught else failure.addSuppressed(caught)
        }
        failure?.let { throw IllegalStateException("The speaker couldn't stop.", it) }
    }
}
