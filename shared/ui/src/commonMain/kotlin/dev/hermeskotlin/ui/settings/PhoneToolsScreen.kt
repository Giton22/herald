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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.AlarmClock
import com.composables.icons.lucide.BatteryMedium
import com.composables.icons.lucide.CalendarDays
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.ClipboardCopy
import com.composables.icons.lucide.Contact
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.MapPinned
import com.composables.icons.lucide.PhoneIncoming
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Wifi
import com.composables.icons.lucide.Wrench
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolUse
import dev.hermeskotlin.core.phone.PhoneToolsStatus
import dev.hermeskotlin.core.phone.PhoneToolsStatus.State
import dev.hermeskotlin.core.phone.WatchedPlace
import androidx.compose.ui.text.style.TextOverflow
import dev.hermeskotlin.core.phone.enabledPhoneTools
import dev.hermeskotlin.core.push.KnownPhone
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.halo
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.sessions.SubpageHeader
import kotlin.time.Clock
import org.koin.compose.viewmodel.koinViewModel

/** Settings → Phone tools, over the settings page. */
@Composable
fun PhoneToolsScreen(onBack: () -> Unit, viewModel: PhoneToolsViewModel = koinViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val phones by viewModel.phones.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val access = rememberPhoneToolAccess()
    PlatformBackHandler(enabled = true, onBack = onBack)
    // Other phones change the list (a new one reports, one is used): fresh each time the page opens.
    LaunchedEffect(Unit) { viewModel.refreshPhones() }
    // A new access is made each time the user comes back (maybe from granting location): arm the places again.
    LaunchedEffect(access) { viewModel.refreshPlaces() }
    PhoneToolsView(
        settings = settings,
        status = status,
        recent = recent,
        onBack = onBack,
        onAll = viewModel::setAll,
        onTool = { tool, on ->
            viewModel.setTool(tool, on)
            // Turning on a tool that can't work yet asks for what it needs straight away.
            if (on && access.missing(tool) != null) access.request(tool)
        },
        onSetUp = viewModel::setUp,
        phones = phones,
        onMovePhone = viewModel::movePhone,
        onRemovePhone = viewModel::removePhone,
        onUpdatePlugin = viewModel::updatePlugin,
        missing = access::missing,
        onAllow = access::request,
        optional = access::optional,
        onAllowOptional = access::requestOptional,
        places = places,
        onRemovePlace = viewModel::removePlace,
        onRings = { on ->
            viewModel.setRings(on)
            if (on && access.ringsMissing() != null) access.requestRings()
        },
        ringsMissing = access::ringsMissing,
        onAllowRings = access::requestRings,
        onRingsTalk = viewModel::setRingsTalk,
    )
}

/** Under the switch: what answering a call does. */
internal const val RINGS_TALK_DETAIL =
    "Answering puts Hermes on the line: it asks in its own words, you answer by voice, and you can talk on. Off, " +
        "Answer opens the chat instead."

/** Under the switch: what rings, and that the agent never calls anyone. */
internal const val RINGS_DETAIL =
    "An approval or a question from a chat you're not looking at rings like a call. Decline it for the usual " +
        "notification. It only ever rings you: the agent never calls anyone from this phone."

/** The page itself, stateless so previews can draw it. */
@Composable
internal fun PhoneToolsView(
    settings: AppSettings,
    status: PhoneToolsStatus,
    recent: List<PhoneToolUse>,
    onBack: () -> Unit,
    onAll: (Boolean) -> Unit,
    onTool: (PhoneTool, Boolean) -> Unit,
    onSetUp: () -> Unit,
    phones: List<KnownPhone> = emptyList(),
    onMovePhone: (String, Int) -> Unit = { _, _ -> },
    onRemovePhone: (String) -> Unit = {},
    onUpdatePlugin: () -> Unit = {},
    missing: (PhoneTool) -> String? = { null },
    onAllow: (PhoneTool) -> Unit = {},
    optional: (PhoneTool) -> String? = { null },
    onAllowOptional: (PhoneTool) -> Unit = {},
    places: List<WatchedPlace> = emptyList(),
    onRemovePlace: (String) -> Unit = {},
    onRings: (Boolean) -> Unit = {},
    ringsMissing: () -> String? = { null },
    onAllowRings: () -> Unit = {},
    onRingsTalk: (Boolean) -> Unit = {},
    nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
) {
    var removing by remember { mutableStateOf<KnownPhone?>(null) }
    var installAsked by remember { mutableStateOf(false) }
    PluginInstallDialog(
        visible = installAsked,
        feature = "Phone tools",
        what = "It offers the agent the tools you turn on here, and passes their calls to this phone.",
        onDismiss = { installAsked = false },
    ) {
        installAsked = false
        onAll(true)
    }
    RemovePhoneDialog(removing, onDismiss = { removing = null }) {
        onRemovePhone(it.id)
        removing = null
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Theme[colors][background])
            .halo()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            SubpageHeader("Phone tools", onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text(
                    "Let the agent use this phone: read how it is, or do something on it. Every tool is off until you " +
                        "turn it on, and anything that changes the phone asks you first: in Herald while it's open, else " +
                        "in a notification. A tool you " +
                        "turn off is taken away from the agent at once.",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                Section("Phone tools") {
                    SwitchRow(
                        title = "Let the agent use this phone",
                        detail = statusLine(settings, status),
                        checked = settings.phoneTools,
                        // On installs code on the gateway: said first, done only once the user agrees.
                        onCheckedChange = { on -> if (on) installAsked = true else onAll(false) },
                        icon = Lucide.Smartphone,
                    )
                    if (settings.phoneTools && status.state in setOf(State.NotInstalled, State.Failed)) {
                        Divider()
                        ActionRow("Set up on the gateway", Lucide.Wrench, onSetUp)
                    }
                    val update = status.update
                    if (settings.phoneTools && update != null) {
                        Divider()
                        PluginUpdateRow(update, onUpdatePlugin)
                    }
                }
                if (settings.phoneTools) {
                    Section("Tools") {
                        PhoneTool.entries.forEachIndexed { i, tool ->
                            if (i > 0) Divider()
                            val on = tool.id in settings.phoneToolsOn
                            // Only once it's on: a tool that's off needs nothing, and asking early would nag.
                            val needs = if (on) missing(tool) else null
                            SwitchRow(
                                title = tool.title,
                                detail = needs ?: tool.detail,
                                checked = on,
                                onCheckedChange = { onTool(tool, it) },
                                icon = tool.icon,
                            )
                            if (needs != null) {
                                Divider()
                                ActionRow("Allow on this phone", Lucide.ShieldCheck) { onAllow(tool) }
                            }
                            // Offered, never asked: only a tap on this row asks Android for it.
                            val more = if (on && needs == null) optional(tool) else null
                            if (more != null) {
                                Divider()
                                ActionRow(more, Lucide.ShieldCheck) { onAllowOptional(tool) }
                            }
                        }
                    }
                    Section("Calls") {
                        val needs = if (settings.phoneRings) ringsMissing() else null
                        SwitchRow(
                            title = "Ring me for an answer",
                            detail = needs ?: RINGS_DETAIL,
                            checked = settings.phoneRings,
                            onCheckedChange = onRings,
                            icon = Lucide.PhoneIncoming,
                        )
                        if (needs != null) {
                            Divider()
                            ActionRow("Allow on this phone", Lucide.ShieldCheck, onAllowRings)
                        }
                        if (settings.phoneRings) {
                            Divider()
                            SwitchRow(
                                title = "Talk when I answer",
                                detail = RINGS_TALK_DETAIL,
                                checked = settings.phoneRingsTalk,
                                onCheckedChange = onRingsTalk,
                                icon = Lucide.AudioLines,
                            )
                        }
                    }
                    // Only once the gateway knows this phone: the list is the gateway's.
                    if (status.state == State.On && phones.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Section("Phones") {
                                phones.forEachIndexed { i, phone ->
                                    if (i > 0) Divider()
                                    PhoneRow(
                                        phone = phone,
                                        canUp = i > 0,
                                        canDown = i < phones.lastIndex,
                                        nowMillis = nowMillis,
                                        onMove = { by -> onMovePhone(phone.id, by) },
                                        onRemove = { removing = phone },
                                    )
                                }
                            }
                            Text(
                                "A chat you send from a phone uses that phone. Scheduled jobs and chats from Desktop or the " +
                                    "dashboard use the first phone in this list with the tool on. A phone you remove comes " +
                                    "back if Herald on it is still signed in with phone tools on.",
                                style = Theme[typography][bodySmall],
                                color = Theme[colors][textTertiary],
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
                // Listed even with the tool off, so the user sees what would fire if it went back on.
                if (places.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Section("Watched places") {
                            places.forEachIndexed { i, place ->
                                if (i > 0) Divider()
                                PlaceRow(place, onRemove = { onRemovePlace(place.id) })
                            }
                        }
                        Text(
                            if (PhoneTool.Geofences in settings.enabledPhoneTools()) {
                                "Android checks every few minutes, so a message can come a little late. It's sent as you, " +
                                    "marked as coming from the place."
                            } else {
                                "Watched places is off, so none of these fire."
                            },
                            style = Theme[typography][bodySmall],
                            color = Theme[colors][textTertiary],
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
                if (recent.isNotEmpty()) {
                    Section("Used lately") {
                        recent.forEachIndexed { i, use ->
                            if (i > 0) Divider()
                            UseRow(use, nowMillis)
                        }
                    }
                }
            }
        }
    }
}

/** What the switch for all of them does now, or where the gateway's side stands. */
internal fun statusLine(settings: AppSettings, status: PhoneToolsStatus): String {
    if (!settings.phoneTools) return "Off. The agent can't use this phone."
    val count = settings.enabledPhoneTools().size
    return when (status.state) {
        State.Off, State.On -> status.detail ?: when (count) {
            0 -> "On. Turn on below each tool the agent may use."
            1 -> "On. New chats can use 1 tool."
            else -> "On. New chats can use $count tools."
        }
        State.Offline -> "Waiting to connect to the gateway."
        State.Working -> status.detail ?: "Setting up with the gateway…"
        State.NotInstalled -> "Needs the herald-push plugin on the gateway."
        State.Outdated -> status.update?.let {
            "The gateway's herald-push plugin is ${it.installed}; phone tools need ${it.available}. Update it below."
        } ?: "The gateway's herald-push plugin is too old."
        State.Failed -> status.detail ?: "The gateway refused."
    }
}

/** One phone the gateway knows: what it has on and when it was used, with its place in the order. */
@Composable
private fun PhoneRow(
    phone: KnownPhone,
    canUp: Boolean,
    canDown: Boolean,
    nowMillis: Long,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Lucide.Smartphone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                if (phone.me) "${phone.name} · This phone" else phone.name,
                style = Theme[typography][body],
                color = Theme[colors][text],
            )
            Text(phoneDetail(phone, nowMillis), style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        }
        Row {
            IconButton(Lucide.ChevronUp, "Move ${phone.name} up", onClick = { onMove(-1) }, enabled = canUp, iconSize = 18.dp)
            IconButton(Lucide.ChevronDown, "Move ${phone.name} down", onClick = { onMove(1) }, enabled = canDown, iconSize = 18.dp)
            // This phone goes away by turning phone tools off; removing it here would only last until its next report.
            if (!phone.me) IconButton(Lucide.Trash2, "Remove ${phone.name}", onClick = onRemove, iconSize = 18.dp)
        }
    }
}

/** A place the phone watches: what fires it, the message it sends, and a remove button. Removing needs no question. */
@Composable
private fun PlaceRow(place: WatchedPlace, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Lucide.MapPinned)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val event = if (place.arriving) "Arriving at" else "Leaving"
            Text("$event ${place.name} · ${place.radiusMeters} m", style = Theme[typography][body], color = Theme[colors][text])
            val chat = place.chatTitle?.takeIf { it.isNotBlank() }?.let { " to $it" }.orEmpty()
            Text(
                "Sends$chat: ${place.message}",
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(Lucide.Trash2, "Stop watching ${place.name}", onClick = onRemove, iconSize = 18.dp)
    }
}

/** "3 tools on · used 2h ago". */
private fun phoneDetail(phone: KnownPhone, nowMillis: Long): String {
    val tools = when (phone.tools.size) {
        0 -> "No tools on"
        1 -> "1 tool on"
        else -> "${phone.tools.size} tools on"
    }
    val (what, at) = if (phone.used > 0) "used" to phone.used else "seen" to phone.seen
    if (at <= 0) return tools
    val ago = relativeTime(at.toDouble(), nowMillis).let { if (it == "now") "just now" else "$it ago" }
    return "$tools · $what $ago"
}

/** Asks before forgetting [phone] on the gateway. */
@Composable
private fun RemovePhoneDialog(phone: KnownPhone?, onDismiss: () -> Unit, onRemove: (KnownPhone) -> Unit) {
    // Kept while the dialog fades out, so its text doesn't go blank first.
    var shown by remember { mutableStateOf(phone) }
    if (phone != null) shown = phone
    val p = shown ?: return
    Dialog(
        visible = phone != null,
        onDismissRequest = onDismiss,
        title = "Remove ${p.name}?",
        message = "The agent stops using its phone tools, and calls waiting for it are refused. If Herald on that " +
            "phone is still signed in with phone tools on, it comes back the next time it connects.",
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Remove", onClick = { onRemove(p) }, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}

@Composable
private fun UseRow(use: PhoneToolUse, nowMillis: Long) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(use.tool.icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(use.tool.title, style = Theme[typography][body], color = Theme[colors][text])
            Text(
                use.error ?: relativeTime(use.atMillis / 1000.0, nowMillis).let { if (it == "now") "Just now" else "$it ago" },
                style = Theme[typography][bodySmall],
                color = if (use.error != null) Theme[colors][danger] else Theme[colors][textTertiary],
            )
        }
    }
}

/** A row that opens a page: [detail] under the title, a chevron at the end. */
@Composable
internal fun NavRow(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme[typography][body], color = Theme[colors][text])
            Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        }
        UnstyledIcon(Lucide.ChevronRight, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
    }
}

internal val PhoneTool.icon: ImageVector
    get() = when (this) {
        PhoneTool.Battery -> Lucide.BatteryMedium
        PhoneTool.Network -> Lucide.Wifi
        PhoneTool.Sound -> Lucide.Volume2
        PhoneTool.Alarms -> Lucide.AlarmClock
        PhoneTool.ScreenTime -> Lucide.Hourglass
        PhoneTool.Open -> Lucide.ExternalLink
        PhoneTool.Clipboard -> Lucide.ClipboardCopy
        PhoneTool.Location -> Lucide.MapPin
        PhoneTool.Geofences -> Lucide.MapPinned
        PhoneTool.Calendar -> Lucide.CalendarDays
        PhoneTool.Contacts -> Lucide.Contact
        PhoneTool.Health -> Lucide.HeartPulse
    }
