package dev.hermeskotlin.wear.assist

import android.Manifest
import android.app.KeyguardManager
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
import dev.hermeskotlin.wear.PROFILE_CHANGED
import dev.hermeskotlin.wear.WatchLink
import dev.hermeskotlin.wear.WatchRecorder
import dev.hermeskotlin.wear.recordAsk
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

    /** Whether the last chat may show: not over the lock screen, unless this session asked it. */
    private val showsLast = MutableStateFlow(true)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        // As on the talk screen, a profile change on the phone takes the recording with it. Not while the phone is
        // still being checked: that check's own first reset would end every first ask.
        work.launch {
            var previous = app.link.profileReset.value
            app.link.profileReset.collect { generation ->
                if (generation == previous) return@collect
                previous = generation
                if (listening.value.recording) {
                    recording?.cancel()
                    listening.value = Listening(error = PROFILE_CHANGED)
                }
            }
        }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@HeraldWearAssistSession)
        setViewTreeSavedStateRegistryOwner(this@HeraldWearAssistSession)
        setViewTreeViewModelStoreOwner(this@HeraldWearAssistSession)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@HeraldWearAssistSession))
        setContent {
            val current by listening.collectAsState()
            val showLast by showsLast.collectAsState()
            AssistPanel(
                link = app.link,
                store = app.store,
                speech = app.speech,
                listening = current,
                showsLast = showLast,
                onOrb = ::orb,
                onNotice = ::notice,
            )
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        showsLast.value = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true
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

    /** The orb: done talking, never mind while the phone is checked, or talk again once the last recording ended. */
    private fun orb() {
        when {
            listening.value.recording -> recorder.finish()
            listening.value.waiting -> stopRecording()
            else -> listen()
        }
    }

    /** A notice: the microphone's is asked for; any other is only dismissed. */
    private fun notice() {
        if (listening.value.error == NEEDS_MICROPHONE) askForMicrophone() else listening.value = Listening()
    }

    @Suppress("MissingPermission") // Checked first.
    private fun listen() {
        if (recording?.isActive == true) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listening.value = Listening(error = NEEDS_MICROPHONE)
            return
        }
        // Nothing of the last reply may play while the microphone is open.
        app.link.quiet()
        // The microphone opens only once the phone answers, so the panel doesn't say it hears before it does.
        listening.value = Listening(waiting = true)
        // A recording just cancelled may still be letting go of the microphone; its end must not reset this one.
        val previous = recording
        recording = work.launch {
            previous?.join()
            // As the talk screen does: the phone's profile first, and the ask only goes to the chat it was made in.
            if (!app.link.synchronizeProfile()) {
                listening.value = Listening(error = WatchLink.NO_HERALD)
                return@launch
            }
            recordAsk(listening, app.link.profileReset.value, { app.link.profileReset.value }, recorder::record) { audio ->
                showsLast.value = true
                app.link.ask(null, audio, WatchRecorder.MIME_TYPE)
            }
        }
    }

    /** Leaving the screen takes the microphone with it; a recording never outlives the session. */
    private fun stopRecording() {
        recording?.cancel()
        recording = null
        if (listening.value.recording || listening.value.waiting) listening.value = Listening()
    }

    /** Android's prompt can't show over the assistant's window, so the session steps aside; it comes back once allowed. */
    private fun askForMicrophone() {
        listening.value = Listening()
        // In the assistant's own task, as Android intends for it.
        startAssistantActivity(Intent(context, MicrophonePermissionActivity::class.java))
        hide()
    }

    private companion object {
        const val NEEDS_MICROPHONE = "Tap to allow the microphone."
    }
}
