package dev.hermeskotlin.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import dev.hermeskotlin.core.gateway.appGatewayOkHttp
import dev.hermeskotlin.core.media.MediaType
import dev.hermeskotlin.core.media.PlaySource
import dev.hermeskotlin.core.media.isNotPublicAddress
import dev.hermeskotlin.core.media.publicOnly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Composable
internal actual fun rememberChatMediaPlayer(): ChatMediaPlayer {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidChatMediaPlayer.get(context) }
}

/** A rebuilt screen (a turn of the phone, a fold) gets a new view on the same player, so the video plays on. */
@OptIn(UnstableApi::class)
@Composable
internal actual fun VideoSurface(player: ChatMediaPlayer, modifier: Modifier) {
    val exo = (player as? AndroidChatMediaPlayer)?.exo?.collectAsState()?.value
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                // Back closes the viewer; the player's own controls do the rest.
                setShowNextButton(false)
                setShowPreviousButton(false)
                controllerAutoShow = true
            }
        },
        update = { view -> view.player = exo },
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
}

/**
 * The app's one [ExoPlayer] for chat audio and video, made on first use and released when nothing plays it.
 * Everything here runs on the main thread, the player's own.
 *
 * Its audio is media: it takes audio focus, so a phone call or another app's sound pauses it, and it pauses
 * when headphones come out. It plays only while Kyllene is in sight (the chat screen pauses it when it goes),
 * with no notification or media session.
 */
@OptIn(UnstableApi::class)
internal class AndroidChatMediaPlayer private constructor(
    private val context: Context,
    /** The app's gateway OkHttp client, with its client certificates; null where there's none (previews). */
    private val gatewayHttp: () -> OkHttpClient?,
) : ChatMediaPlayer {

    override val available = true

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _video = MutableStateFlow<PlayableMedia?>(null)
    override val video: StateFlow<PlayableMedia?> = _video.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val slot = PlayerSlot()
    private var loading: Job? = null
    private var ticking: Job? = null
    private var chat: Any? = null

    /** The player while one is built; the video view follows it, as a clip that failed may get a new one. */
    internal val exo = MutableStateFlow<ExoPlayer?>(null)

    private var exoPlayer: ExoPlayer?
        get() = exo.value
        set(value) {
            exo.value = value
        }

    /**
     * The gateway's requests carry the sign-in: they go to the gateway alone, never on to a redirect. They start
     * from the app's gateway client ([appGatewayOkHttp]), so a gateway that asks for a client certificate gets the
     * same one the probe before them presented.
     */
    private val gatewayClient: OkHttpClient
        get() = gatewayClientMade ?: run {
            val base = gatewayHttp()
            val made = (base ?: OkHttpClient()).newBuilder().timeouts().followRedirects(false).followSslRedirects(false).build()
            // Kept only once made from the app's client, so one asked before Koin started isn't kept for good.
            made.also { if (base != null) gatewayClientMade = it }
        }

    private var gatewayClientMade: OkHttpClient? = null

    /**
     * Web addresses: no sign-in and no certificate go along, and only the open internet: a reply's link never
     * reaches into the user's own networks (as for link previews and the screens' players).
     */
    private val http by lazy { OkHttpClient.Builder().timeouts().publicOnly().build() }

    /** Whether the clip held comes from the web, not the gateway, for what its errors say. */
    private var fromWeb = false

    private fun OkHttpClient.Builder.timeouts() = connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        // Still meant to play (loading the next clip, buffering), it keeps its claim; [pause] and [stop] give it up.
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) claimSpeaker() else if (loading?.isActive != true && exoPlayer?.playWhenReady != true) NowPlaying.app.stopped(this@AndroidChatMediaPlayer)
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(phase = PlaybackPhase.Failed, error = describe(error)) }
            stopTicking()
        }
    }

    /** A screen's player that's playing pauses for this one, from the tap on, not once the clip has loaded. */
    private fun claimSpeaker() = NowPlaying.app.started(this, NowPlaying.Group.Chat, ::pause)

    override fun play(media: PlayableMedia, resolve: (suspend () -> PlaySource)?) {
        claimSpeaker()
        if (slot.claim(media.key) == PlayerSlot.Claim.Resume) {
            // Still being worked out: it starts once it's ready.
            if (loading?.isActive == true) return
            val exo = exoPlayer
            if (exo != null && _state.value.phase != PlaybackPhase.Failed) {
                if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
                exo.play()
                return
            }
            // Failed before: a tap tries it again from the start.
        }
        loading?.cancel()
        exoPlayer?.stop()
        _state.value = PlaybackState(key = media.key, phase = PlaybackPhase.Loading, speed = _state.value.speed.takeIf { media.type == MediaType.Audio } ?: 1f)
        loading = scope.launch {
            val source = when {
                media.bytes != null -> PlaySource.Bytes(media.bytes)
                resolve != null -> try {
                    resolve()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PlaySource.Failed(dev.hermeskotlin.core.network.ApiResult.Unavailable(e.message ?: "Couldn't reach the gateway."))
                }
                else -> null
            }
            // Another clip, or none, was asked for while this one was worked out.
            if (!slot.holds(media.key)) return@launch
            val mediaSource = when (source) {
                is PlaySource.Gateway -> progressive(OkHttpDataSource.Factory(gatewayClient).setDefaultRequestProperties(source.headers), source.url)
                is PlaySource.Web -> progressive(OkHttpDataSource.Factory(http), source.url)
                is PlaySource.Bytes -> progressive({ ByteArrayDataSource(source.bytes) }, "data:clip")
                is PlaySource.Failed -> {
                    _state.update { it.copy(phase = PlaybackPhase.Failed, error = playbackError(source.result)) }
                    return@launch
                }
                null -> {
                    _state.update { it.copy(phase = PlaybackPhase.Failed, error = CANT_PLAY) }
                    return@launch
                }
            }
            fromWeb = source is PlaySource.Web
            val player = exoPlayer ?: build().also { exoPlayer = it }
            player.setMediaSource(mediaSource)
            player.setPlaybackSpeed(_state.value.speed)
            player.prepare()
            player.play()
        }
    }

    override fun pause() {
        NowPlaying.app.stopped(this)
        loading?.takeIf { it.isActive }?.let {
            // Still being worked out: it shouldn't start once it's ready.
            it.cancel()
            slot.clear()
            _state.value = PlaybackState(speed = _state.value.speed)
        }
        exoPlayer?.pause()
    }

    override fun seekTo(key: String, positionMs: Long) {
        if (!slot.holds(key)) return
        exoPlayer?.seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs) }
    }

    override fun setSpeed(key: String, speed: Float) {
        if (!slot.holds(key)) return
        exoPlayer?.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    override fun openVideo(media: PlayableMedia, resolve: (suspend () -> PlaySource)?) {
        _video.value = media
        play(media, resolve)
    }

    override fun closeVideo() {
        val shown = _video.value ?: return
        _video.value = null
        if (slot.holds(shown.key)) stop()
    }

    override fun stop() {
        NowPlaying.app.stopped(this)
        loading?.cancel()
        loading = null
        stopTicking()
        slot.clear()
        _video.value = null
        exoPlayer?.let {
            it.removeListener(listener)
            it.release()
        }
        exoPlayer = null
        _state.value = PlaybackState(speed = _state.value.speed)
    }

    override fun bindChat(token: Any) {
        if (chat == token) return
        chat = token
        stop()
    }

    private fun build(): ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also { it.addListener(listener) }

    private fun progressive(factory: DataSource.Factory, uri: String) =
        ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri.toUri()))

    /** What the player is doing, for the clip held. */
    private fun publish() {
        val exo = exoPlayer ?: return
        val key = slot.key ?: return
        if (_state.value.phase == PlaybackPhase.Failed && exo.playerError != null) return
        val phase = when {
            exo.isPlaying -> PlaybackPhase.Playing
            exo.playbackState == Player.STATE_ENDED -> PlaybackPhase.Ended
            exo.playbackState == Player.STATE_BUFFERING && exo.playWhenReady -> PlaybackPhase.Loading
            exo.playbackState == Player.STATE_IDLE -> _state.value.phase
            else -> PlaybackPhase.Paused
        }
        _state.update {
            it.copy(
                key = key,
                phase = phase,
                positionMs = if (phase == PlaybackPhase.Ended) exo.duration.coerceAtLeast(0) else exo.currentPosition,
                durationMs = exo.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 },
                error = null,
            )
        }
        if (exo.isPlaying) startTicking() else stopTicking()
    }

    /** The position moves on its own while playing; the row's time follows it a few times a second. */
    private fun startTicking() {
        if (ticking?.isActive == true) return
        ticking = scope.launch {
            while (isActive) {
                val exo = exoPlayer ?: break
                _state.update { it.copy(positionMs = exo.currentPosition) }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicking() {
        ticking?.cancel()
        ticking = null
    }

    private fun describe(error: PlaybackException): String {
        val status = ((error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode)
        val sender = if (fromWeb) "The site" else "The gateway"
        return when {
            fromWeb && isNotPublicAddress(error) -> "Not played: the link points into your own network."
            status == 401 && !fromWeb -> "Sign in again to play files."
            status != null -> "$sender stopped sending it (HTTP $status)."
            error.errorCode in PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                if (fromWeb) "Couldn't reach the site." else "Couldn't reach the gateway."
            error.errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "This phone can't play this file."
            else -> CANT_PLAY
        }
    }

    companion object {
        private const val TICK_MS = 250L
        private const val CANT_PLAY = "Couldn't play this file."

        // Holds the application's context only, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AndroidChatMediaPlayer? = null

        fun get(context: Context): AndroidChatMediaPlayer =
            instance ?: synchronized(this) {
                instance ?: AndroidChatMediaPlayer(context.applicationContext, ::appGatewayOkHttp).also { instance = it }
            }
    }
}
