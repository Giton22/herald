package dev.hermeskotlin.core.update

import android.os.Build

internal actual fun deviceAbis(): List<String> = Build.SUPPORTED_ABIS.toList()
