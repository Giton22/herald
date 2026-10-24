package dev.hermeskotlin.android.notify

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.hermeskotlin.core.phone.CallOutcome
import dev.hermeskotlin.ui.calls.InCallScreen
import dev.hermeskotlin.ui.calls.PhoneCallModel
import dev.hermeskotlin.ui.calls.PickedUpCall
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * A call for an answer, picked up ([ChatNotifications.postCall] with talking on): Hermes on the line, asking its
 * question aloud and taking the spoken answer, with no chat on screen. It unlocks first, as every answer does: on a
 * locked phone an approval would otherwise run a command for whoever holds it. Not unlocked, it hangs up and leaves
 * the usual notification; so does a question left unanswered, once the call is out of sight.
 */
class PhoneCallActivity : ComponentActivity() {

    private val notifications: ChatNotifications by inject()
    private val model: PhoneCallModel by viewModel()

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.start() else leave("Kyllene needs the microphone for calls. The question is in the chat.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stays over the lock screen once on, as a call does when the screen locks mid-call.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        // Nor does it lock by itself mid-call: an approval isn't taken from a locked phone.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent { InCallScreen(model) }
        lifecycleScope.launch {
            val ended = model.state.filterNotNull().first { it.ended }
            ended.error?.let { Toast.makeText(this@PhoneCallActivity, it, Toast.LENGTH_LONG).show() }
            // Never on the line: the question waits in the shade, without a sound, as the toast just said why.
            ended.notAsked?.let { call -> call.request?.let { notifications.postRequest(call.storedSessionId, call.caller, it, quiet = true) } }
            finish()
        }
        // A rotation keeps the model: a call on the line, or one being picked up, carries on there.
        if (model.state.value != null) return
        if (model.pickingUp != null) return unlockThenTalk()
        val requestId = intent.getStringExtra(NotificationActionReceiver.EXTRA_REQUEST_ID)
        val ringing = requestId?.let { notifications.ringing.value[it] }
        val chat = ringing?.storedSessionId
        // A call from the agent may have no chat on this gateway (Desktop's, say): it talks in a new one.
        if (requestId == null || ringing == null || (chat == null && ringing.agent == null)) return finish()
        model.pickUp(PickedUpCall(requestId, chat, ringing.title?.takeIf { it.isNotBlank() } ?: "Hermes", ringing.request, ringing.agent))
        // Picked up: it stops ringing at once, whatever comes next.
        notifications.answered(requestId)
        unlockThenTalk()
    }

    /** A second call while on one: it can't be picked up here, so it turns into the usual notification at once. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val requestId = intent.getStringExtra(NotificationActionReceiver.EXTRA_REQUEST_ID) ?: return
        if (requestId != model.pickingUp?.id) notifications.decline(requestId)
    }

    private fun unlockThenTalk() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked != true) return withMicrophone()
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = withMicrophone()
                override fun onDismissCancelled() = leave(null)
                override fun onDismissError() = leave(null)
            },
        )
    }

    private fun withMicrophone() {
        if (isFinishing || isDestroyed) return
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) model.start()
        else microphone.launch(permission)
    }

    /** Not picked up after all: the question waits in the usual notification, and [why] says so. */
    private fun leave(why: String?) {
        // An answer for a screen already gone (rotated): the one in its place picks up.
        if (isFinishing || isDestroyed) return
        model.pickingUp?.let { call ->
            call.request?.let { notifications.postRequest(call.storedSessionId, call.caller, it) }
            call.agent?.end(CallOutcome.Declined)
        }
        why?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Out of sight, the call goes on while Android holds the microphone for it, like any call.
        if (!isChangingConfigurations) model.voice.onBackground()
    }
}
