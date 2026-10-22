package dev.hermeskotlin.wear

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** This app's own recording, on a watch without an input screen: [level] while it hears, [error] when it missed. */
data class Listening(val recording: Boolean = false, val level: Float = 0f, val error: String? = null)

/**
 * The talk screen. A tap on the mic opens the watch's own speech recognizer, so only words go to the phone;
 * a watch without one records and the gateway transcribes. Launched with
 * [EXTRA_LISTEN] (the tile, the complication) or as the watch's assistant (`ACTION_ASSIST`) it starts
 * listening at once.
 */
class MainActivity : ComponentActivity() {

    private val app get() = HeraldWatch.of(this)
    private val recorder = WatchRecorder()
    private val listening = MutableStateFlow(Listening())
    private var recordingJob: Job? = null

    private val recognize = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val said = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!said.isNullOrEmpty()) app.link.ask(said)
    }

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) record() else listening.value = Listening(error = NEEDS_MICROPHONE)
    }

    private val callMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) app.link.startCall() else listening.value = Listening(error = NEEDS_MICROPHONE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HeraldWatchApp(
                link = app.link,
                store = app.store,
                speech = app.speech,
                listening = listening,
                onMic = ::talk,
                onFinishRecording = recorder::finish,
                onDismissListeningError = { listening.value = Listening() },
            )
        }
        // How the phone stands, for the dot at the top edge: asked again now and then while the app is in sight.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    app.link.refreshPhone()
                    delay(PHONE_CHECK_MS)
                }
            }
        }
        // A call keeps the screen on: with it off, the activity stops, and the call with it.
        lifecycleScope.launch {
            app.link.call.collect { call ->
                if (call?.active == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        if (savedInstanceState == null) {
            handle(intent)
            // Hermes rings the watch when it needs an answer, which takes notifications. Asked once; a no stays a no.
            if (Build.VERSION.SDK_INT >= 33 && !app.rings.canRing && !app.store.askedToRing) {
                app.store.askedToRing = true
                notificationsAllowed.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private val notificationsAllowed = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        // Debug builds take a typed question from adb (`--es ask "…"`), for trying a turn on an emulator without a mic.
        val typed = if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_DEBUG_ASK) else null
        when {
            !typed.isNullOrBlank() -> app.link.ask(typed)
            intent.getStringExtra(EXTRA_CALL) == app.callToken -> call()
            AssistEntry.asksToListen(intent.action, intent.getBooleanExtra(EXTRA_LISTEN, false)) -> talk()
        }
    }

    /** A ring answered: a live call in its chat, whatever the talk setting, since that's how the question is asked. */
    private fun call() {
        app.link.dismissCall()
        listening.value = Listening()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            app.link.startCall()
        } else {
            callMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        // Out of sight, a recording or a call would go on hearing the room.
        recordingJob?.cancel()
        if (!isChangingConfigurations) app.link.endCall()
    }

    /** The mic: a live call when the gateway talks live and it's on, else listening for one ask. */
    private fun talk() {
        // A second call-up while the microphone is already open (the assistant button pressed twice) changes nothing.
        if (listening.value.recording) return
        if (!(app.link.liveVoice.value && app.store.live.value)) return listen()
        app.link.dismissCall()
        listening.value = Listening()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            app.link.startCall()
        } else {
            callMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun listen() {
        // Nothing of the last reply may play while the recognizer listens.
        app.link.quiet()
        // The watch's speech recognizer, straight to listening: talking is what the mic is for.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Hermes")
        if (intent.resolveActivity(packageManager) != null) {
            try {
                recognize.launch(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Listed but gone: record instead.
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            record()
        } else {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    @Suppress("MissingPermission") // listen() checks it first.
    private fun record() {
        if (recordingJob?.isActive == true) return
        listening.value = Listening(recording = true)
        recordingJob = lifecycleScope.launch {
            val audio = try {
                recorder.record { level -> listening.value = listening.value.copy(level = level) }
            } catch (e: CancellationException) {
                // Left the screen: nothing to tell.
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

    companion object {
        const val EXTRA_LISTEN = "listen"
        private const val EXTRA_CALL = "call"
        private const val EXTRA_DEBUG_ASK = "ask"
        private const val PHONE_CHECK_MS = 30_000L
        private const val NEEDS_MICROPHONE = "Herald needs the microphone to hear you."

        /** For a PendingIntent, which starts it in its own task (it's singleTask) and to its onNewIntent when open. */
        fun listenIntent(context: Context): Intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_LISTEN, true)

        /** A ring answered: starts the call in the watch's chat, which [WatchLink.answerRing] just set. */
        fun callIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_CALL, HeraldWatch.of(context).callToken)
    }
}
