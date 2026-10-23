package dev.hermeskotlin.wear.assist

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import dev.hermeskotlin.wear.MainActivity

/**
 * What the watch's assist button points at on watches whose long press can only name an activity (OnePlus):
 * it hands the talk screen a listen and steps out of the way, so the button opens Herald listening. The
 * assistant role is the other way in, and the one that shows the session over the watch face.
 */
@SuppressLint("CustomSplashScreen") // Invisible by design: Theme.NoDisplay, gone before anything is drawn.
class AssistLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainActivity.listenIntent(this))
        finish()
    }
}
