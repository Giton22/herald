package dev.hermeskotlin.android.assist

import android.Manifest
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.service.voice.VoiceInteractionSession
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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
import dev.hermeskotlin.android.MainActivity
import dev.hermeskotlin.android.notify.ChatNotifications
import dev.hermeskotlin.ui.assistant.AssistantPanel
import dev.hermeskotlin.ui.assistant.AssistantPanelModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * The assistant as the user sees it: [AssistantPanel] in the window Android gives the assistant, over the
 * app they were in. Android passes along that app's text and, when the user allows it in the assistant
 * settings, a screenshot; both go to the panel, which sends them with the first question.
 *
 * Compose needs the owners an activity would provide, so the session is its own lifecycle, saved-state
 * and view-model owner, alive from [onCreate] to [onDestroy] and resumed while shown.
 */
class HeraldAssistSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    KoinComponent {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var model: AssistantPanelModel
    private val microphone = mutableStateOf(false)

    /** This call-up's screenshot at full size, to cut circled parts from. */
    @Volatile private var screenshot: Bitmap? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        // Circled parts are cut from the full-size screenshot, not the smaller copy the panel shows.
        model = AssistantPanelModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) { region ->
            screenshot?.let { shot -> withContext(Dispatchers.Default) { runCatching { ScreenReader.crop(shot, region) }.getOrNull() } }
        }
        // Edge to edge, so the panel sits on the navigation bar and rides up with the keyboard.
        window.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION")
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@HeraldAssistSession)
        setViewTreeSavedStateRegistryOwner(this@HeraldAssistSession)
        setViewTreeViewModelStoreOwner(this@HeraldAssistSession)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@HeraldAssistSession))
        setContent {
            AssistantPanel(
                model = model,
                microphoneAllowed = microphone.value,
                onOpenHerald = ::openInHerald,
                onAllowMicrophone = ::askForMicrophone,
                onClose = ::hide,
            )
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Read on every show: it may have just been allowed, which is why the panel came back.
        microphone.value = microphoneAllowed()
        if (args?.getBoolean(HeraldAssistService.EXTRA_RESUME) == true) {
            model.resume()
        } else {
            screenshot = null
            model.begin(
                expectText = showFlags and SHOW_WITH_ASSIST != 0,
                expectScreenshot = showFlags and SHOW_WITH_SCREENSHOT != 0,
            )
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onHide() {
        super.onHide()
        model.voice.cancelDictation()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    // The one-structure form: Android calls it for the app in front on every version Herald runs on.
    @Deprecated("Superseded by onHandleAssist(AssistState) on API 29, which calls this for the app in front")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        if (structure == null) {
            model.onScreenText(null, emptyList())
            return
        }
        // Walking the structure fetches it from the other app, which can take a moment.
        work.launch {
            val items = runCatching { ScreenReader.items(structure) }.getOrDefault(emptyList())
            model.onScreenText(ScreenReader.appName(structure, context.packageManager), items)
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        this.screenshot = screenshot
        if (screenshot == null) {
            model.onScreenshot(null)
            return
        }
        work.launch { model.onScreenshot(runCatching { ScreenReader.jpeg(screenshot) }.getOrNull()) }
    }

    /** Back leaves circling first, then closes the panel as usual. */
    override fun onBackPressed() {
        if (model.circling.value) model.cancelCircling() else super.onBackPressed()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        model.close()
        work.cancel()
        store.clear()
        super.onDestroy()
    }

    private fun microphoneAllowed() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Android's prompt opens below the assistant's window, so the panel steps aside; it comes back once allowed. */
    private fun askForMicrophone() {
        hide()
        context.startActivity(Intent(context, MicrophonePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Herald, on the panel's chat once it has one; also where the microphone is allowed. */
    private fun openInHerald(storedSessionId: String?) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        storedSessionId?.let { intent.putExtra(ChatNotifications.EXTRA_OPEN_SESSION, it) }
        context.startActivity(intent)
        hide()
    }
}
