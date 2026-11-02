package dev.hermeskotlin.wear

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dev.hermeskotlin.wearlink.WatchRing
import dev.hermeskotlin.wearlink.WearJson
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hermes calling on the watch, full screen: who's calling and why, never the question. Answer unlocks the watch
 * first (an approval taken from a locked watch would run a command for whoever wears it), then the call starts in
 * the talk screen, where the voice asks. Decline leaves the question waiting on the phone.
 */
class RingActivity : ComponentActivity() {

    private val app get() = HeraldWatch.of(this)
    private val ring = MutableStateFlow<WatchRing?>(null)
    private var watching: Job? = null
    private var answering = false

    /** Answer was tapped in the notification: asked once the screen shows, as the unlock needs it to. */
    private var answerWhenShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            val shown by ring.collectAsState()
            shown?.let {
                RingScreen(
                    title = it.title,
                    approval = it.approval,
                    onDecline = {
                        app.link.declineRing(it.requestId)
                        finish()
                    },
                    onAnswer = ::answer,
                    agent = it.agent,
                )
            }
        }
        show(intent, answerNow = savedInstanceState == null)
    }

    // singleTop: another ring, or Answer in the notification of this one.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent, answerNow = true)
    }

    private fun show(intent: Intent, answerNow: Boolean) {
        val next = intent.getStringExtra(EXTRA_RING)?.let { WearJson.decode<WatchRing>(it.encodeToByteArray()) }
        if (next == null || !app.rings.restore(next)) {
            // That one's over; one already on screen stays.
            if (ring.value == null) finish()
            return
        }
        // Unlocking to answer one: it's that one.
        if (answering) return
        hold(next)
        if (answerNow && intent.getBooleanExtra(EXTRA_ANSWER, false)) answerWhenShown = true
    }

    private fun hold(shown: WatchRing) {
        ring.value = shown
        watching?.cancel()
        // Over elsewhere (answered on the phone, rung out): the screen goes with it, or shows another still ringing.
        watching = lifecycleScope.launch {
            val left = app.rings.ringing.first { shown.requestId !in it }
            if (answering) return@launch
            left.values.firstOrNull()?.let(::hold) ?: finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (answerWhenShown) {
            answerWhenShown = false
            answer()
        }
    }

    private fun answer() {
        if (answering || ring.value == null) return
        answering = true
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked != true) return pickUp()
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = pickUp()
                override fun onDismissCancelled() = notAnswered()
                override fun onDismissError() = notAnswered()
            },
        )
    }

    /** Still locked: it rings on, unless it ended meanwhile. */
    private fun notAnswered() {
        answering = false
        if (ring.value?.requestId !in app.rings.ringing.value) finish()
    }

    /** Unlocked: the phone stops ringing, and the talk screen starts the call in the ringing chat. */
    private fun pickUp() {
        if (isFinishing || isDestroyed) return
        val id = ring.value?.requestId ?: return
        lifecycleScope.launch {
            // Refresh before picking the ringing chat: the first profile check can clear the watch's old selection.
            if (!app.link.synchronizeProfile()) {
                notAnswered()
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            if (app.link.answerRing(id) != null) startActivity(MainActivity.answerCallIntent(this@RingActivity))
            finish()
        }
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_RING = "ring"
        private const val EXTRA_ANSWER = "answer"

        /** [ring] whole, so its screen still has it should Herald have been restarted since it began. */
        fun intent(context: Context, ring: WatchRing, answer: Boolean): Intent = Intent(context, RingActivity::class.java)
            .putExtra(EXTRA_RING, WearJson.encode(ring).decodeToString())
            .putExtra(EXTRA_ANSWER, answer)
    }
}
