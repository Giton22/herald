package dev.hermeskotlin.android.widget

import android.app.Application

/**
 * The widget tests don't need the real app graph: HermesApplication.onCreate starts Koin and a
 * dozen listeners, and its GlobalContext survives across Robolectric test classes. Every widget
 * test class names this empty application instead (the merged manifest is still the app's own).
 */
class WidgetTestApp : Application()
