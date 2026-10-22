package dev.hermeskotlin.wear.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.hermeskotlin.wear.AssistPanel
import dev.hermeskotlin.wear.HeraldWatch
import dev.hermeskotlin.wear.Listening
import dev.hermeskotlin.wear.MainActivity
import dev.hermeskotlin.wear.WatchRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The assistant as the watch shows it: Herald's listening screen in the window Android gives the assistant, over
 * the watch face. It records what's said and sends it the same way the talk screen does (`WatchLink.ask`), so
 * the reply arrives through Herald on the phone and is spoken here.
 *
 * Compose needs the owners an activity would provide, so the session is its own lifecycle, saved-state and
 * view-model owner, alive from [onCreate] to [onDestroy] and resumed while shown.
 */
class HeraldWearAssistSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val app = HeraldWatch.of(context)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = WatchRecorder()
    private val listening = MutableStateFlow(Listening())
    private var recording: Job? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@HeraldWearAssistSession)
        setViewTreeSavedStateRegistryOwner(this@HeraldWearAssistSession)
        setViewTreeViewModelStoreOwner(this@HeraldWearAssistSession)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@HeraldWearAssistSession))
        setContent {
            val current by listening.collectAsState()
            AssistPanel(
                link = app.link,
                store = app.store,
                speech = app.speech,
                listening = current,
                onOrb = ::orb,
                onOpen = ::openHerald,
            )
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // The screen stays on while Herald listens and answers, as on the talk screen.
        setKeepAwake(true)
        // The session window is up: listen straight away, the way the tile does.
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        listen()
    }

    override fun onHide() {
        super.onHide()
        setKeepAwake(false)
        stopRecording()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        stopRecording()
        work.cancel()
        store.clear()
        super.onDestroy()
    }

    /** The orb: done talking, or talk again once the last recording ended. */
    private fun orb() {
        if (listening.value.recording) recorder.finish() else listen()
    }

    private fun listen() {
        if (recording?.isActive == true) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listening.value = Listening(error = "Tap to allow the microphone in Herald.")
            return
        }
        // Nothing of the last reply may play while the microphone is open.
        app.link.quiet()
        listening.value = Listening(recording = true)
        recording = work.launch {
            val audio = try {
                recorder.record { level -> listening.value = listening.value.copy(level = level) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listening.value = Listening(error = e.message ?: "Couldn't use the microphone.")
                return@launch
            } finally {
                if (listening.value.recording) listening.value = Listening()
            }
            if (audio == null) {
                listening.value = Listening(error = "I didn't hear anything.")
            } else {
                app.link.ask(null, audio, WatchRecorder.MIME_TYPE)
            }
        }
    }

    /** Leaving the screen takes the microphone with it; a recording never outlives the session. */
    private fun stopRecording() {
        recording?.cancel()
        recording = null
        if (listening.value.recording) listening.value = Listening()
    }

    /** The place where the microphone is allowed, and where the chat and its reply are. */
    private fun openHerald() {
        // In the assistant's own task, as Android intends for it, so the watch's Recents stays honest.
        startAssistantActivity(Intent(context, MainActivity::class.java))
        hide()
    }
}
