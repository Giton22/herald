package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.LogIn
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.voice.PlanUsage
import dev.hermeskotlin.core.voice.SUBSCRIPTION_VOICES
import dev.hermeskotlin.core.voice.SubscriptionSignIn
import dev.hermeskotlin.core.voice.SubscriptionVoiceOptions
import dev.hermeskotlin.core.voice.VoiceUsage
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Status
import dev.hermeskotlin.designsystem.components.StatusDot
import dev.hermeskotlin.designsystem.components.Surface
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.components.plainTextClipEntry
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.components.timeUntil
import dev.hermeskotlin.ui.sessions.SubpageHeader
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Settings › Voice › Live voice: the ChatGPT plan's live voice through the gateway's Talk Desktop plugin. */
@Composable
fun LiveVoiceScreen(settings: AppSettings, viewModel: SettingsViewModel, onUpdate: ((AppSettings) -> AppSettings) -> Unit, onBack: () -> Unit) {
    PlatformBackHandler(enabled = true, onBack = onBack)
    LaunchedEffect(Unit) { viewModel.checkLiveVoice() }
    val plugin by viewModel.liveVoice.collectAsStateWithLifecycle()
    LiveVoiceView(
        settings,
        plugin,
        onUpdate = onUpdate,
        onCheck = viewModel::checkLiveVoice,
        onSignIn = viewModel::signInLiveVoice,
        onCancelSignIn = viewModel::cancelLiveVoiceSignIn,
        onBack = onBack,
    )
}

/** The page itself, stateless so previews can draw it. */
@Composable
internal fun LiveVoiceView(
    settings: AppSettings,
    plugin: LiveVoicePlugin,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onCheck: () -> Unit,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader("Live voice", onBack = onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text(
                    "A voice chat is a live call on the ChatGPT plan the gateway's Talk Desktop plugin is signed in to, " +
                        "instead of per minute on an OpenAI key. The plan's own limits apply.",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                SwitchRowSection(
                    title = "Live voice on ChatGPT",
                    detail = "Off, a voice chat is the profile's own: GPT-Live or listen, send and read aloud.",
                    checked = settings.subscriptionVoice,
                    onCheckedChange = { on -> onUpdate { it.copy(subscriptionVoice = on) } },
                )
                Section("ChatGPT") { Account(plugin, onCheck, onSignIn, onCancelSignIn) }
                Section("Voice") {
                    Column(Modifier.selectableGroup()) {
                        Choice("The gateway's choice", "The voice its Talk Desktop plugin is set to.", settings.subscriptionVoiceName.isBlank()) {
                            onUpdate { it.copy(subscriptionVoiceName = "") }
                        }
                        SUBSCRIPTION_VOICES.forEach { voice ->
                            Divider()
                            Choice(voice.label, voice.description, settings.subscriptionVoiceName == voice.id) {
                                onUpdate { it.copy(subscriptionVoiceName = voice.id) }
                            }
                        }
                    }
                }
                Section("Persona") {
                    val bots = (plugin as? LiveVoicePlugin.Ready)?.bots.orEmpty()
                    // A bot picked before that the gateway no longer lists still shows, so it can be moved off.
                    val listed = (bots + settings.subscriptionPersona)
                        .filter { it.isNotBlank() && it != SubscriptionVoiceOptions.PERSONA_MAIN }
                        .distinct()
                    Column(Modifier.selectableGroup()) {
                        Choice("This chat's profile", "The voice speaks as the bot you're chatting with.", settings.subscriptionPersona == SubscriptionVoiceOptions.PERSONA_CHAT) {
                            onUpdate { it.copy(subscriptionPersona = SubscriptionVoiceOptions.PERSONA_CHAT) }
                        }
                        Divider()
                        Choice("The gateway's main bot", "Whatever chat you're in.", settings.subscriptionPersona == SubscriptionVoiceOptions.PERSONA_MAIN) {
                            onUpdate { it.copy(subscriptionPersona = SubscriptionVoiceOptions.PERSONA_MAIN) }
                        }
                        listed.forEach { bot ->
                            Divider()
                            Choice(bot, null, settings.subscriptionPersona == bot) { onUpdate { it.copy(subscriptionPersona = bot) } }
                        }
                    }
                }
                Section("Work") {
                    SwitchRow(
                        title = "Hand work to Hermes",
                        detail = "What the voice can't answer itself runs in the open chat, with the agent's tools and model, and the voice " +
                            "tells you how it went. Off, the voice only talks.",
                        checked = settings.subscriptionChatWork,
                        onCheckedChange = { on -> onUpdate { it.copy(subscriptionChatWork = on) } },
                    )
                }
                (plugin as? LiveVoicePlugin.Ready)?.let { ready ->
                    if (ready.plan != null || ready.minutes != null) Section("Usage") { Usage(ready.plan, ready.minutes) }
                }
            }
        }
    }
}

/** The main switch, on its own card above the sections. */
@Composable
private fun SwitchRowSection(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxWidth()) {
        SwitchRow(title, detail, checked, onCheckedChange)
    }
}

/** Whether the plugin is there and signed in, and the sign-in when it isn't. */
@Composable
private fun Account(plugin: LiveVoicePlugin, onCheck: () -> Unit, onSignIn: () -> Unit, onCancelSignIn: () -> Unit) {
    val uris = LocalUriHandler.current
    when (plugin) {
        LiveVoicePlugin.Checking -> Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spinner(Modifier.size(18.dp))
            Text("Asking the gateway…", style = Theme[typography][body], color = Theme[colors][text])
        }
        LiveVoicePlugin.Missing -> {
            InfoRow("Not on this gateway", "Live voice needs the Talk Desktop plugin on the gateway. Until then, a voice chat is the profile's own.")
            Divider()
            ActionRow("About Talk Desktop", Lucide.ExternalLink) { runCatching { uris.openUri(TALK_DESKTOP_URL) } }
        }
        is LiveVoicePlugin.Unknown -> {
            InfoRow("Couldn't ask the gateway", plugin.message)
            Divider()
            ActionRow("Check again", Lucide.RefreshCw, onCheck)
        }
        is LiveVoicePlugin.Ready -> when {
            plugin.signedIn -> Row(Modifier.fillMaxWidth().padding(14.dp)) {
                StatusDot(Status.Ok, "Signed in to ChatGPT. The voice runs on this plan.")
            }
            plugin.signIn?.pending == true -> SignIn(plugin.signIn, onCancelSignIn)
            else -> {
                Row(Modifier.fillMaxWidth().padding(14.dp)) {
                    StatusDot(Status.Warning, "Not signed in. A voice chat is the profile's own until the gateway signs in.")
                }
                plugin.signIn?.message?.let {
                    Divider()
                    InfoRow("The last sign-in didn't work", it)
                }
                Divider()
                ActionRow("Sign in with ChatGPT", Lucide.LogIn, onSignIn)
            }
        }
    }
}

/** A sign-in waiting for its code: the page to open, the code to enter there, and a way out. */
@Composable
private fun SignIn(signIn: SubscriptionSignIn, onCancel: () -> Unit) {
    val uris = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val code = signIn.code
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Open the sign-in page, sign in with the ChatGPT account the voice should use, and enter this code.",
            style = Theme[typography][bodySmall],
            color = Theme[colors][textTertiary],
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (code == null) {
                Spinner(Modifier.size(18.dp))
                Text("Getting a code…", style = Theme[typography][body], color = Theme[colors][text])
            } else {
                Text(code, style = Theme[typography][title], color = Theme[colors][accentText], modifier = Modifier.weight(1f, fill = false))
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Theme[colors][surface2])
                        .clickable(role = Role.Button, onClick = { scope.launch { clipboard.setClipEntry(plainTextClipEntry(code)) } }),
                    contentAlignment = Alignment.Center,
                ) {
                    UnstyledIcon(Lucide.Copy, contentDescription = "Copy the code", tint = Theme[colors][text], modifier = Modifier.size(16.dp))
                }
            }
        }
        Text("Waiting for you to approve it. The code runs out in about 15 minutes.", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
    }
    Divider()
    ActionRow("Open the sign-in page", Lucide.ExternalLink) { runCatching { uris.openUri(signIn.url ?: SIGN_IN_URL) } }
    Divider()
    ActionRow("Cancel", Lucide.X, onCancel)
}

/** One option of a list where one is picked, ticked when it is. */
@Composable
private fun Choice(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme[typography][body], color = if (selected) Theme[colors][accentText] else Theme[colors][text])
            if (detail != null) Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        }
        if (selected) UnstyledIcon(Lucide.Check, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
    }
}

/** The plan's limits as bars, and the live voice's time on the gateway. */
@Composable
private fun Usage(plan: PlanUsage?, minutes: VoiceUsage?) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        plan?.plan?.let { Text("Plan: ${it.replaceFirstChar { c -> c.uppercase() }}", style = Theme[typography][body], color = Theme[colors][text]) }
        plan?.windows?.forEach { (label, window) ->
            val used = window.usedPercent ?: return@forEach
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val resets = window.resetsAt?.let { timeUntil(it.toDouble()) }?.takeIf { it.isNotEmpty() }?.let { " · resets $it" }.orEmpty()
                Text("${windowName(label, window.windowMinutes)}: $used% used$resets", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Theme[colors][surface2])) {
                    Box(Modifier.fillMaxWidth(used.coerceIn(0, 100) / 100f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Theme[colors][accent]))
                }
            }
        }
        minutes?.let {
            Text(
                "Live voice on this gateway: ${minutesLabel(it.today.minutes)} today, ${minutesLabel(it.rolling5h.minutes)} in the last 5 hours, " +
                    "${minutesLabel(it.week.minutes)} this week.",
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
            )
        }
    }
}

/** The plugin labels the plan's windows by length ("5h") or in Spanish ("semanal"); this names them. */
internal fun windowName(label: String, minutes: Int?): String = when {
    minutes == 7 * 24 * 60 || label.equals("semanal", ignoreCase = true) || label.equals("weekly", ignoreCase = true) -> "This week"
    minutes != null && minutes % 60 == 0 -> "${minutes / 60}-hour limit"
    else -> label
}

internal fun minutesLabel(minutes: Double): String = when {
    minutes <= 0.0 -> "0 min"
    minutes < 1.0 -> "under a minute"
    else -> "${minutes.roundToInt()} min"
}

/** A short line for the Settings row: the voice picked and whether it's on. */
internal fun liveVoiceDetail(settings: AppSettings): String = when {
    !settings.subscriptionVoice -> "Off. A voice chat is the profile's own."
    settings.subscriptionVoiceName.isBlank() -> "On, in the gateway's voice."
    else -> "On, in ${settings.subscriptionVoiceName.replaceFirstChar { it.uppercase() }}."
}

private const val TALK_DESKTOP_URL = "https://github.com/Synero/hermes-live-voice"
private const val SIGN_IN_URL = "https://auth.openai.com/codex/device"
