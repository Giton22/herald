package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * No player in the desktop app yet: audio and video keep their file card, whose Save opens the file in the
 * computer's own player.
 */
@Composable
internal actual fun rememberChatMediaPlayer(): ChatMediaPlayer = NoMediaPlayer

/** Never shown: with [NoMediaPlayer] no video opens. */
@Composable
internal actual fun VideoSurface(player: ChatMediaPlayer, modifier: Modifier) = Unit
