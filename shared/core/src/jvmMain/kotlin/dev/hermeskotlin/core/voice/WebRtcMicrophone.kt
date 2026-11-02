package dev.hermeskotlin.core.voice

import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioLayer

/** A fresh PulseAudio device module for each recording; no shared device or volume changes. */
internal class WebRtcMicrophone : Microphone {
    private var module: AudioDeviceModule? = null

    override fun start(onFrame: (PcmFrame) -> Unit, onFailure: (Throwable) -> Unit) {
        check(module == null)
        try {
            val native = AudioDeviceModule(AudioLayer.kLinuxPulseAudio)
            module = native
            val device = MediaDevices.getDefaultAudioCaptureDevice() ?: error("No default microphone")
            native.setRecordingDevice(device)
            // WebRTC's "bytes per sample" is per frame of all channels (2 × channels for 16-bit PCM).
            native.setAudioSink { bytes, samples, bytesPerSample, channels, rate, _, _ ->
                if (channels !in 1..2 || bytesPerSample != channels * 2 || rate !in 8_000..192_000 || samples <= 0 ||
                    bytes.size > 8192 || samples.toLong() * bytesPerSample != bytes.size.toLong()) {
                    onFailure(IllegalStateException("The microphone returned an unsupported audio format."))
                } else {
                    // The native callback may reuse its buffer before the coroutine consumes it.
                    onFrame(PcmFrame(bytes.copyOf(), rate, channels))
                }
            }
            native.initRecording()
            native.startRecording()
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw IllegalStateException("The microphone isn't available.", failure)
        }
    }

    override fun close() {
        val native = module ?: return
        module = null
        var failure: Throwable? = null
        try { native.stopRecording() } catch (caught: Throwable) { failure = caught }
        try { native.dispose() } catch (caught: Throwable) {
            if (failure == null) failure = caught else failure.addSuppressed(caught)
        }
        failure?.let { throw IllegalStateException("The microphone couldn't stop.", it) }
    }
}
