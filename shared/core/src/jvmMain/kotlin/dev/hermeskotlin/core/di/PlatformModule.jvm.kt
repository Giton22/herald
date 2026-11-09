package dev.hermeskotlin.core.di

import dev.hermeskotlin.core.auth.LoopbackHttpReceiver
import dev.hermeskotlin.core.auth.LoopbackReceiver
import dev.hermeskotlin.core.cache.KeystoreSealer
import dev.hermeskotlin.core.cache.Sealer
import dev.hermeskotlin.core.cache.offlineDatabase
import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.storage.FileBlobFile
import dev.hermeskotlin.core.storage.desktopKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.storage.appDataDir
import dev.hermeskotlin.core.voice.DeviceDictation
import dev.hermeskotlin.core.voice.FfmpegSpeechDecoder
import dev.hermeskotlin.core.voice.LinuxSpeechPlayer
import dev.hermeskotlin.core.voice.WebRtcSpeaker
import dev.hermeskotlin.core.voice.LinuxVoiceRecorder
import dev.hermeskotlin.core.voice.WebRtcMicrophone
import dev.hermeskotlin.core.voice.LiveCalls
import dev.hermeskotlin.core.voice.LinuxLiveCall
import dev.hermeskotlin.core.voice.WebRtcLivePeer
import dev.hermeskotlin.core.voice.NoDeviceDictation
import dev.hermeskotlin.core.voice.NoSpeechPlayer
import dev.hermeskotlin.core.voice.NoVoiceRecorder
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.core.voice.desktopSpeechDecoder
import dev.hermeskotlin.core.voice.nativeDesktopAudio
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

actual val platformModule: Module = module {
    single<KeyValueStore> { desktopKeyValueStore(File(appDataDir, "settings.json")) }
    // No app link to send the browser back to: its tab says it's done.
    single<LoopbackReceiver> { LoopbackHttpReceiver(returnUri = null) }
    single { WallpaperStore(FileBlobFile(File(appDataDir, "wallpaper.jpg")), get()) }
    // Native recording and playback are Linux backends; live calls remain separate.
    single<VoiceRecorder> {
        if (nativeDesktopAudio()) LinuxVoiceRecorder(::WebRtcMicrophone) else NoVoiceRecorder
    }
    // Decoding needs ffmpeg; voice chat and Read aloud check the same two things (hasSpeechPlayback in shared/ui).
    single<SpeechPlayer> {
        if (nativeDesktopAudio() && desktopSpeechDecoder()) LinuxSpeechPlayer(FfmpegSpeechDecoder(), ::WebRtcSpeaker)
        else NoSpeechPlayer
    }
    single<DeviceDictation> { NoDeviceDictation }
    single { LiveCalls {
        if (nativeDesktopAudio()) {
            LinuxLiveCall { events -> WebRtcLivePeer(events, realAudio = true) }
        } else null
    } }
    single { offlineDatabase(appDataDir) }
    single<Sealer> { KeystoreSealer(get()) }
}
