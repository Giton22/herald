package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun VerticalScrollbar(state: ScrollState, modifier: Modifier) = Unit

@Composable
actual fun VerticalScrollbar(state: LazyListState, modifier: Modifier) = Unit

@Composable
actual fun HorizontalScrollbar(state: ScrollState, modifier: Modifier) = Unit
