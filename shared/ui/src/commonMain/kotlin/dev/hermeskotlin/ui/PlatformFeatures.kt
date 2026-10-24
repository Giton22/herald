package dev.hermeskotlin.ui

/** Inline widgets draw in a web view; without one a widget is a file like any other. */
internal expect val inlineWidgetsShow: Boolean

/** The phone tools (SMS, calls, contacts and the like) and their Settings section. */
internal expect val phoneToolsShow: Boolean

/** The App icon picker in Settings. */
internal expect val appIconsShow: Boolean
