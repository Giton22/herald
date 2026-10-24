package dev.hermeskotlin.android.notify

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.ui.calls.IncomingCallScreen
import dev.hermeskotlin.ui.calls.agentCallReason
import dev.hermeskotlin.ui.calls.callReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * The full screen a call for an answer rings with ([ChatNotifications.postCall]), over the lock screen and with the
 * screen off. Answer unlocks first, then opens the chat; Decline leaves the usual notification. It closes by itself
 * once the call is gone: answered elsewhere, withdrawn, or rung out.
 */
class IncomingCallActivity : ComponentActivity(), OutOfSight {

    private val notifications: ChatNotifications by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        val requestId = intent.getStringExtra(NotificationActionReceiver.EXTRA_REQUEST_ID)
        val call = requestId?.let { notifications.ringing.value[it] }
        if (requestId == null || call == null) {
            finish()
            return
        }
        setContent {
            IncomingCallScreen(
                caller = call.title?.takeIf { it.isNotBlank() } ?: "Hermes",
                // Over the lock screen: who's calling, never what about.
                why = if (call.agent != null) agentCallReason() else callReason(call.request is InputRequest.Approval),
                onAnswer = { answer(requestId) },
                onDecline = {
                    notifications.decline(requestId)
                    finish()
                },
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                notifications.ringing.first { requestId !in it }
                finish()
            }
        }
    }

    /** A second call while this one shows: the newer one takes the screen, as the one ringing last. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    /** Unlocks first: the chat it opens answers for whoever holds the phone. */
    private fun answer(requestId: String) {
        val open = notifications.answer(requestId) ?: return finish()
        // Hermes on the line: the call screen unlocks and picks up itself.
        if (open.component?.className == PhoneCallActivity::class.java.name) {
            startActivity(open)
            return finish()
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked != true) return open(requestId, open)
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = open(requestId, open)
            },
        )
    }

    private fun open(requestId: String, intent: Intent) {
        notifications.answered(requestId)
        startActivity(intent)
        finish()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }
}
