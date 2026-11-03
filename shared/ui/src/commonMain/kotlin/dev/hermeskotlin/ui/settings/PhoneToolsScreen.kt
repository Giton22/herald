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
import com.composables.icons.lucide.LayoutPanelTop
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.MapPinned
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.PhoneCall
import com.composables.icons.lucide.PhoneIncoming
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.Watch
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
import dev.hermeskotlin.core.phone.MINUTES_A_DAY
import dev.hermeskotlin.core.phone.clockTime
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
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.display
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.components.relativeTime
import dev.hermeskotlin.ui.resources.*
import dev.hermeskotlin.ui.sessions.SubpageHeader
import kotlin.time.Clock
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
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
        onPermission = viewModel::setPermission,
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
        onWatchRings = viewModel::setWatchRings,
        onQuietHours = viewModel::setQuietHours,
        onQuietFrom = viewModel::setQuietFrom,
        onQuietUntil = viewModel::setQuietUntil,
    )
}

/** Which window the quiet hours line names: none picked yet, an empty one (it starts where it ends), or a real one. */
internal enum class QuietWindow { Off, Empty, Window }

internal fun quietWindow(settings: AppSettings): QuietWindow = when {
    !settings.quietHours -> QuietWindow.Off
    settings.quietFrom == settings.quietUntil -> QuietWindow.Empty
    else -> QuietWindow.Window
}

/**
 * Under the switch: what quiet hours stop, and what Do Not Disturb does to the calls without them. The calls ring
 * with no contact behind them: Android holds them back unless Kyllene's calls may interrupt or anyone may call, but
 * lets one through as a repeat caller (the same chat's name again within 15 minutes).
 */
@Composable
internal fun quietHoursDetail(settings: AppSettings): String {
    val window = when (quietWindow(settings)) {
        QuietWindow.Empty -> stringResource(Res.string.phone_quiet_empty, clockTime(settings.quietFrom))
        QuietWindow.Window -> stringResource(Res.string.phone_quiet_window, clockTime(settings.quietFrom), clockTime(settings.quietUntil))
        QuietWindow.Off -> stringResource(Res.string.phone_quiet_off)
    }
    return stringResource(Res.string.phone_quiet_detail, window)
}

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
    missing: (PhoneTool) -> StringResource? = { null },
    onAllow: (PhoneTool) -> Unit = {},
    optional: (PhoneTool) -> StringResource? = { null },
    onAllowOptional: (PhoneTool) -> Unit = {},
    places: List<WatchedPlace> = emptyList(),
    onRemovePlace: (String) -> Unit = {},
    onRings: (Boolean) -> Unit = {},
    ringsMissing: () -> StringResource? = { null },
    onAllowRings: () -> Unit = {},
    onRingsTalk: (Boolean) -> Unit = {},
    onWatchRings: (Boolean) -> Unit = {},
    onQuietHours: (Boolean) -> Unit = {},
    onQuietFrom: (Int) -> Unit = {},
    onQuietUntil: (Int) -> Unit = {},
    nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
    onPermission: (PhoneTool, Boolean) -> Unit = { _, _ -> },
) {
    var removing by remember { mutableStateOf<KnownPhone?>(null) }
    // Which end of quiet hours is being picked: true for the start.
    var picking by remember { mutableStateOf<Boolean?>(null) }
    QuietTimeDialog(
        start = picking,
        from = settings.quietFrom,
        until = settings.quietUntil,
        onDismiss = { picking = null },
    ) { start, minute ->
        if (start) onQuietFrom(minute) else onQuietUntil(minute)
        picking = null
    }
    var installAsked by remember { mutableStateOf(false) }
    PluginInstallDialog(
        visible = installAsked,
        feature = stringResource(Res.string.settings_phone_tools),
        what = stringResource(Res.string.phone_install_what),
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
            SubpageHeader(stringResource(Res.string.settings_phone_tools), onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text(
                    "Tools stay off until you enable them. Choose below whether each enabled tool must ask you " +
                        "before it runs. Android permissions still apply. A tool you turn off is taken away from the agent at once.",
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                Section(stringResource(Res.string.settings_phone_tools)) {
                    SwitchRow(
                        title = stringResource(Res.string.phone_let_agent),
                        detail = statusLine(settings, status),
                        checked = settings.phoneTools,
                        // On installs code on the gateway: said first, done only once the user agrees.
                        onCheckedChange = { on -> if (on) installAsked = true else onAll(false) },
                        icon = Lucide.Smartphone,
                    )
                    if (settings.phoneTools && status.state in setOf(State.NotInstalled, State.Failed)) {
                        Divider()
                        ActionRow(stringResource(Res.string.phone_set_up), Lucide.Wrench, onSetUp)
                    }
                    val update = status.update
                    if (settings.phoneTools && update != null) {
                        Divider()
                        PluginUpdateRow(update, onUpdatePlugin)
                    }
                }
                if (settings.phoneTools) {
                    Section(stringResource(Res.string.phone_section_tools)) {
                        // The agent's calls ring: they're under Calls.
                        PhoneTool.entries.filter { it != PhoneTool.CallMe }.forEachIndexed { i, tool ->
                            if (i > 0) Divider()
                            val on = tool.id in settings.phoneToolsOn
                            // Only once it's on: a tool that's off needs nothing, and asking early would nag.
                            val needs = if (on) missing(tool) else null
                            SwitchRow(
                                title = stringResource(tool.titleRes),
                                detail = if (needs != null) stringResource(needs) else when (tool) {
                                    PhoneTool.Sound, PhoneTool.Alarms, PhoneTool.Open, PhoneTool.Clipboard,
                                    PhoneTool.Geofences, PhoneTool.Calendar -> tool.detail
                                    else -> stringResource(tool.detailRes)
                                },
                                checked = on,
                                onCheckedChange = { onTool(tool, it) },
                                icon = tool.icon,
                            )
                            PhoneToolPermissionRow(tool, settings, onPermission)
                            if (needs != null) {
                                Divider()
                                ActionRow(stringResource(Res.string.phone_allow), Lucide.ShieldCheck) { onAllow(tool) }
                            }
                            // Offered, never asked: only a tap on this row asks Android for it.
                            val more = if (on && needs == null) optional(tool) else null
                            if (more != null) {
                                Divider()
                                ActionRow(stringResource(more), Lucide.ShieldCheck) { onAllowOptional(tool) }
                            }
                        }
                    }
                    Section(stringResource(Res.string.phone_section_calls)) {
                        val callsMe = PhoneTool.CallMe.id in settings.phoneToolsOn
                        val callNeeds = if (callsMe) ringsMissing() else null
                        SwitchRow(
                            title = stringResource(PhoneTool.CallMe.titleRes),
                            detail = stringResource(callNeeds ?: PhoneTool.CallMe.detailRes),
                            checked = callsMe,
                            onCheckedChange = { onTool(PhoneTool.CallMe, it) },
                            icon = PhoneTool.CallMe.icon,
                        )
                        PhoneToolPermissionRow(PhoneTool.CallMe, settings, onPermission)
                        if (callNeeds != null) {
                            Divider()
                            ActionRow(stringResource(Res.string.phone_allow), Lucide.ShieldCheck, onAllowRings)
                        }
                        Divider()
                        val needs = if (settings.phoneRings) ringsMissing() else null
                        SwitchRow(
                            title = stringResource(Res.string.phone_rings_title),
                            detail = stringResource(needs ?: Res.string.phone_rings_detail),
                            checked = settings.phoneRings,
                            onCheckedChange = onRings,
                            icon = Lucide.PhoneIncoming,
                        )
                        if (needs != null) {
                            Divider()
                            ActionRow(stringResource(Res.string.phone_allow), Lucide.ShieldCheck, onAllowRings)
                        }
                        if (settings.phoneRings) {
                            Divider()
                            SwitchRow(
                                title = stringResource(Res.string.phone_rings_talk_title),
                                detail = stringResource(Res.string.phone_rings_talk_detail),
                                checked = settings.phoneRingsTalk,
                                onCheckedChange = onRingsTalk,
                                icon = Lucide.AudioLines,
                            )
                        }
                        if (settings.phoneRings || callsMe) {
                            Divider()
                            SwitchRow(
                                title = stringResource(Res.string.phone_watch_rings_title),
                                detail = stringResource(Res.string.phone_watch_rings_detail),
                                checked = settings.watchRings,
                                onCheckedChange = onWatchRings,
                                icon = Lucide.Watch,
                            )
                            Divider()
                            SwitchRow(
                                title = stringResource(Res.string.phone_quiet_title),
                                detail = quietHoursDetail(settings),
                                checked = settings.quietHours,
                                onCheckedChange = onQuietHours,
                                icon = Lucide.Moon,
                            )
                            if (settings.quietHours) {
                                Divider()
                                TimeRow(stringResource(Res.string.phone_quiet_starts), settings.quietFrom) { picking = true }
                                Divider()
                                TimeRow(stringResource(Res.string.phone_quiet_ends), settings.quietUntil) { picking = false }
                            }
                        }
                    }
                    // Only once the gateway knows this phone: the list is the gateway's.
                    if (status.state == State.On && phones.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Section(stringResource(Res.string.phone_section_phones)) {
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
                                stringResource(Res.string.phone_phones_note),
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
                        Section(stringResource(PhoneTool.Geofences.titleRes)) {
                            places.forEachIndexed { i, place ->
                                if (i > 0) Divider()
                                PlaceRow(place, onRemove = { onRemovePlace(place.id) })
                            }
                        }
                        Text(
                            if (PhoneTool.Geofences in settings.enabledPhoneTools()) {
                                stringResource(Res.string.phone_places_note_on)
                            } else {
                                stringResource(Res.string.phone_places_note_off)
                            },
                            style = Theme[typography][bodySmall],
                            color = Theme[colors][textTertiary],
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
                if (recent.isNotEmpty()) {
                    Section(stringResource(Res.string.phone_section_used)) {
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

/**
 * What the switch for all of them does now, or where the gateway's side stands. [PhoneToolsStatus.detail] is the
 * gateway's or the plugin setup's own message, shown as it is.
 */
@Composable
internal fun statusLine(settings: AppSettings, status: PhoneToolsStatus): String {
    if (!settings.phoneTools) return stringResource(Res.string.phone_status_off)
    val count = settings.enabledPhoneTools().size
    return when (status.state) {
        State.Off, State.On -> status.detail ?: if (count == 0) {
            stringResource(Res.string.phone_status_none)
        } else {
            pluralStringResource(Res.plurals.phone_status_count, count, count)
        }
        State.Offline -> stringResource(Res.string.phone_status_offline)
        State.Working -> status.detail ?: stringResource(Res.string.phone_status_working)
        State.NotInstalled -> stringResource(Res.string.phone_status_not_installed)
        State.Outdated -> status.update?.let {
            stringResource(Res.string.phone_status_outdated, it.installed, it.available)
        } ?: stringResource(Res.string.phone_status_too_old)
        State.Failed -> status.detail ?: stringResource(Res.string.phone_status_refused)
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
                if (phone.me) stringResource(Res.string.phone_this_phone, phone.name) else phone.name,
                style = Theme[typography][body],
                color = Theme[colors][text],
            )
            Text(phoneDetail(phone, nowMillis), style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        }
        Row {
            IconButton(Lucide.ChevronUp, stringResource(Res.string.phone_move_up, phone.name), onClick = { onMove(-1) }, enabled = canUp, iconSize = 18.dp)
            IconButton(Lucide.ChevronDown, stringResource(Res.string.phone_move_down, phone.name), onClick = { onMove(1) }, enabled = canDown, iconSize = 18.dp)
            // This phone goes away by turning phone tools off; removing it here would only last until its next report.
            if (!phone.me) IconButton(Lucide.Trash2, stringResource(Res.string.phone_remove, phone.name), onClick = onRemove, iconSize = 18.dp)
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
            val event = if (place.arriving) Res.string.phone_place_arriving else Res.string.phone_place_leaving
            Text(stringResource(event, place.name, place.radiusMeters), style = Theme[typography][body], color = Theme[colors][text])
            val chat = place.chatTitle?.takeIf { it.isNotBlank() }
            Text(
                if (chat != null) {
                    stringResource(Res.string.phone_place_sends_to, chat, place.message)
                } else {
                    stringResource(Res.string.phone_place_sends, place.message)
                },
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(Lucide.Trash2, stringResource(Res.string.phone_place_stop, place.name), onClick = onRemove, iconSize = 18.dp)
    }
}

/** "3 tools on · used 2h ago". */
@Composable
private fun phoneDetail(phone: KnownPhone, nowMillis: Long): String {
    val count = phone.tools.size
    val tools = if (count == 0) {
        stringResource(Res.string.phone_no_tools)
    } else {
        pluralStringResource(Res.plurals.phone_tools_on, count, count)
    }
    val used = phone.used > 0
    val at = if (used) phone.used else phone.seen
    if (at <= 0) return tools
    // The compact age ("2h") stays as it is in every language; "now" is its word for under a minute.
    val age = relativeTime(at.toDouble(), nowMillis)
    return when {
        age == "now" && used -> stringResource(Res.string.phone_used_just_now, tools)
        age == "now" -> stringResource(Res.string.phone_seen_just_now, tools)
        used -> stringResource(Res.string.phone_used_ago, tools, age)
        else -> stringResource(Res.string.phone_seen_ago, tools, age)
    }
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
        title = stringResource(Res.string.phone_remove_title, p.name),
        message = stringResource(Res.string.phone_remove_message),
        actions = {
            Button(stringResource(Res.string.common_cancel), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(stringResource(Res.string.common_remove), onClick = { onRemove(p) }, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}

/** One end of quiet hours: tapping it opens [QuietTimeDialog]. Lined up with the titles of the rows above. */
@Composable
private fun TimeRow(title: String, minuteOfDay: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(start = 60.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Theme[typography][body], color = Theme[colors][text], modifier = Modifier.weight(1f))
        Text(clockTime(minuteOfDay), style = Theme[typography][body], color = Theme[colors][accentText])
    }
}

/**
 * Picks the [start] (true) or end of quiet hours, now [from] and [until]: hours by one, minutes by five, both
 * wrapping.
 */
@Composable
private fun QuietTimeDialog(start: Boolean?, from: Int, until: Int, onDismiss: () -> Unit, onSet: (Boolean, Int) -> Unit) {
    // Kept while the dialog fades out, so its title and time don't change first.
    var shown by remember { mutableStateOf(start) }
    if (start != null) shown = start
    val s = shown ?: return
    // Fresh each time it opens, and left as it was while it closes.
    var minute by remember { mutableStateOf(if (s) from else until) }
    var open by remember { mutableStateOf(false) }
    if (start != null && !open) minute = if (start) from else until
    open = start != null
    Dialog(
        visible = start != null,
        onDismissRequest = onDismiss,
        title = stringResource(if (s) Res.string.phone_quiet_start_title else Res.string.phone_quiet_end_title),
        actions = {
            Button(stringResource(Res.string.common_cancel), onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            // A second tap while it fades out sets nothing.
            Button(stringResource(Res.string.phone_quiet_set), onClick = { if (start != null) onSet(s, minute) }, size = ButtonSize.Small)
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TimeStepper(minute / 60, Res.string.phone_later_hour, Res.string.phone_earlier_hour) { by ->
                minute = (minute + by * 60).mod(MINUTES_A_DAY)
            }
            Text(":", style = Theme[typography][display], color = Theme[colors][textTertiary], modifier = Modifier.padding(horizontal = 8.dp))
            TimeStepper(minute % 60, Res.string.phone_later_minutes, Res.string.phone_earlier_minutes) { by ->
                minute = minute / 60 * 60 + (minute % 60 + by * 5).mod(60)
            }
        }
    }
}

@Composable
private fun TimeStepper(value: Int, later: StringResource, earlier: StringResource, onStep: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(Lucide.ChevronUp, stringResource(later), onClick = { onStep(1) })
        Text(value.toString().padStart(2, '0'), style = Theme[typography][display], color = Theme[colors][text])
        IconButton(Lucide.ChevronDown, stringResource(earlier), onClick = { onStep(-1) })
    }
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
            Text(stringResource(use.tool.titleRes), style = Theme[typography][body], color = Theme[colors][text])
            Text(
                // The tool's own error, as it told the agent.
                use.error ?: relativeTime(use.atMillis / 1000.0, nowMillis).let {
                    if (it == "now") stringResource(Res.string.phone_just_now) else stringResource(Res.string.phone_ago, it)
                },
                style = Theme[typography][bodySmall],
                color = if (use.error != null) Theme[colors][danger] else Theme[colors][textTertiary],
            )
        }
    }
}

/** A row that opens a page: [detail] under the title, a chevron at the end. */
@Composable
internal fun NavRow(title: String, detail: String, icon: ImageVector, onClick: () -> Unit, detailLines: Int = Int.MAX_VALUE) {
    if (!settingShown(title, detail)) return
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme[typography][body], color = Theme[colors][text])
            Text(
                detail,
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
                maxLines = detailLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        UnstyledIcon(Lucide.ChevronRight, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
    }
}

/** The tool's name by its switch, in the app's language. [PhoneTool.title] is the English one the agent is told. */
internal val PhoneTool.titleRes: StringResource
    get() = when (this) {
        PhoneTool.Battery -> Res.string.phone_tool_battery
        PhoneTool.Network -> Res.string.phone_tool_network
        PhoneTool.Sound -> Res.string.phone_tool_sound
        PhoneTool.Alarms -> Res.string.phone_tool_alarms
        PhoneTool.ScreenTime -> Res.string.phone_tool_screen_time
        PhoneTool.Open -> Res.string.phone_tool_open
        PhoneTool.Clipboard -> Res.string.phone_tool_clipboard
        PhoneTool.Location -> Res.string.phone_tool_location
        PhoneTool.Geofences -> Res.string.phone_tool_geofences
        PhoneTool.Calendar -> Res.string.phone_tool_calendar
        PhoneTool.Contacts -> Res.string.phone_tool_contacts
        PhoneTool.Health -> Res.string.phone_tool_health
        PhoneTool.ShowUi -> Res.string.phone_tool_show_ui
        PhoneTool.CallMe -> Res.string.phone_tool_call_me
    }

/** What the tool does, under its switch. */
internal val PhoneTool.detailRes: StringResource
    get() = when (this) {
        PhoneTool.Battery -> Res.string.phone_tool_battery_detail
        PhoneTool.Network -> Res.string.phone_tool_network_detail
        PhoneTool.Sound -> Res.string.phone_tool_sound_detail
        PhoneTool.Alarms -> Res.string.phone_tool_alarms_detail
        PhoneTool.ScreenTime -> Res.string.phone_tool_screen_time_detail
        PhoneTool.Open -> Res.string.phone_tool_open_detail
        PhoneTool.Clipboard -> Res.string.phone_tool_clipboard_detail
        PhoneTool.Location -> Res.string.phone_tool_location_detail
        PhoneTool.Geofences -> Res.string.phone_tool_geofences_detail
        PhoneTool.Calendar -> Res.string.phone_tool_calendar_detail
        PhoneTool.Contacts -> Res.string.phone_tool_contacts_detail
        PhoneTool.Health -> Res.string.phone_tool_health_detail
        PhoneTool.ShowUi -> Res.string.phone_tool_show_ui_detail
        PhoneTool.CallMe -> Res.string.phone_tool_call_me_detail
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
        PhoneTool.ShowUi -> Lucide.LayoutPanelTop
        PhoneTool.CallMe -> Lucide.PhoneCall
    }
