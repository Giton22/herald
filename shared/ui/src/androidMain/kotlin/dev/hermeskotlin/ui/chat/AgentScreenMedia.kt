package dev.hermeskotlin.ui.chat

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.a2ui.catalog.A2uiAudioPlayerRenderer
import androidx.compose.material3.a2ui.catalog.A2uiImageRenderer
import androidx.compose.material3.a2ui.catalog.A2uiVideoRenderer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.hermeskotlin.core.media.publicOnly
import androidx.media3.ui.PlayerView
import android.content.Context
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.hermeskotlin.ui.LocalAppVersion
import okhttp3.OkHttpClient
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.surface3
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/**
 * Only pictures and media on the web: a screen mustn't show what's on the phone (`file:`, `content:`) or reach
 * into an app.
 */
internal fun isWebUrl(url: String): Boolean = Uri.parse(url.trim()).scheme?.lowercase() in setOf("http", "https")

/**
 * A picture from the web. One that doesn't load is a quiet box saying so, not an error: a dead link the agent
 * picked shouldn't end the whole screen.
 */
internal val ScreenImages = A2uiImageRenderer { url, description, scale, modifier, _ ->
    if (!isWebUrl(url)) {
        Missing("Picture not shown", modifier)
    } else {
        SubcomposeAsyncImage(
            model = url,
            imageLoader = imageLoader(LocalContext.current, LocalAppVersion.current),
            contentDescription = description,
            contentScale = scale,
            modifier = modifier,
            loading = { Box(Modifier.matchParentSize().background(Theme[colors][surface3])) },
            error = { Missing("Picture didn't load", Modifier.matchParentSize()) },
        )
    }
}

private var client: OkHttpClient? = null
private var loader: ImageLoader? = null

/**
 * What the screens' pictures and media load through. The agent picks the addresses and they load without a tap,
 * so only the open internet, never the user's own networks (as for link previews). It names itself, as OkHttp's own
 * name is turned away by some image hosts (Wikimedia).
 */
private fun webClient(version: String?): OkHttpClient = client ?: run {
    val agent = "Kyllene/${version ?: "dev"} (Android; https://github.com/Giton22/kyllene)"
    OkHttpClient.Builder()
        .publicOnly()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build()) }
        .build()
        .also { client = it }
}

/**
 * The screens' pictures load through this, not Coil's own loader: that finds its web fetcher through a service
 * file a minified build leaves out, and doesn't keep to the open internet.
 */
private fun imageLoader(context: Context, version: String?): ImageLoader = loader ?: run {
    val web = webClient(version)
    ImageLoader.Builder(context.applicationContext)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { web })) }
        .build()
        .also { loader = it }
}

/** A video with the system's controls: play, pause, seek. Nothing plays until the user starts it. */
internal val ScreenVideos = A2uiVideoRenderer { url, modifier, _ ->
    // At least a width of its own: in a list that scrolls sideways, nothing gives it one.
    Player(url, null, audio = false, modifier.fillMaxWidth().widthIn(min = MIN_WIDTH).aspectRatio(16f / 9f).clip(RoundedCornerShape(Theme[radii][radiusMedium])))
}

/** Audio with the system's controls, always shown: there's no picture to tap for them. */
internal val ScreenAudio = A2uiAudioPlayerRenderer { url, description, modifier, _ ->
    Player(url, description, audio = true, modifier.fillMaxWidth().widthIn(min = MIN_WIDTH).height(AUDIO_HEIGHT).clip(RoundedCornerShape(Theme[radii][radiusMedium])))
}

@OptIn(UnstableApi::class)
@Composable
private fun Player(url: String, description: String?, audio: Boolean, modifier: Modifier) {
    if (!isWebUrl(url)) {
        Missing(if (audio) "Audio not played" else "Video not shown", modifier)
        return
    }
    val context = LocalContext.current
    val version = LocalAppVersion.current
    var failed by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        val media = DefaultMediaSourceFactory(OkHttpDataSource.Factory(webClient(version)))
        // Not prepared: nothing downloads until the user presses play (the controls prepare it then).
        ExoPlayer.Builder(context.applicationContext).setMediaSourceFactory(media).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = false
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val errors = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        // Out of sight, it stops: a screen scrolled away or an app in the background doesn't keep playing.
        val pause = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        player.addListener(errors)
        lifecycle.addObserver(pause)
        onDispose {
            lifecycle.removeObserver(pause)
            player.removeListener(errors)
            player.release()
        }
    }
    if (failed) {
        Missing(if (audio) "Audio didn't load" else "Video didn't load", modifier)
        return
    }
    val areas = LocalPlayerAreas.current
    val area = remember { Any() }
    DisposableEffect(areas) { onDispose { areas?.remove(area) } }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                if (audio) {
                    useArtwork = false
                    controllerShowTimeoutMs = 0
                    controllerHideOnTouch = false
                    showController()
                }
            }
        },
        // A new url (the agent changed the screen) is a new player.
        update = { it.player = player },
        modifier = (if (description == null) modifier else modifier.semantics { contentDescription = description })
            .onGloballyPositioned { areas?.set(area, it.boundsInWindow()) },
    )
}

@Composable
private fun Missing(text: String, modifier: Modifier) {
    Box(modifier.background(Theme[colors][surface3]), contentAlignment = Alignment.Center) {
        Text(
            text,
            Modifier.padding(4.dp),
            color = Theme[colors][textTertiary],
            style = Theme[typography][caption],
            maxLines = 1,
        )
    }
}

private val AUDIO_HEIGHT = 96.dp
private val MIN_WIDTH = 240.dp

/**
 * Where a screen's players are, in the window: a screen that takes no taps still lets them be played. Each
 * screen keeps its own.
 */
internal val LocalPlayerAreas = staticCompositionLocalOf<SnapshotStateMap<Any, Rect>?> { null }
