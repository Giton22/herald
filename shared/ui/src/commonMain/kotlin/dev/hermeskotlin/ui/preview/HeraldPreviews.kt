package dev.hermeskotlin.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.CalendarClock
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Plus
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.sessions.InsightsUiState
import dev.hermeskotlin.ui.sessions.SubpageHeader
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.ui.chat.PendingComment
import dev.hermeskotlin.ui.connect.AccessToken
import dev.hermeskotlin.ui.connect.ConnectUiState
import dev.hermeskotlin.ui.connect.ConnectView
import dev.hermeskotlin.ui.connect.ConnectionChecklist
import dev.hermeskotlin.ui.signin.SignInMethods
import dev.hermeskotlin.ui.signin.SignInUiState
import dev.hermeskotlin.ui.signin.SignInView
import dev.hermeskotlin.core.gateway.GatewayStatus
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.ProbeResult
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.hermeskotlin.designsystem.components.Toast
import dev.hermeskotlin.core.capabilities.McpServer
import dev.hermeskotlin.core.capabilities.Skill
import dev.hermeskotlin.core.capabilities.Toolset
import dev.hermeskotlin.ui.chat.CheckpointsSheetView
import dev.hermeskotlin.ui.chat.ProcessesSheetView
import dev.hermeskotlin.ui.chat.UsageSheetView
import dev.hermeskotlin.ui.sessions.CapabilitiesActions
import dev.hermeskotlin.ui.sessions.CapabilitiesView
import dev.hermeskotlin.ui.sessions.ScheduledActions
import dev.hermeskotlin.ui.sessions.ScheduledView
import dev.hermeskotlin.ui.sessions.CapabilityTab
import dev.hermeskotlin.ui.sessions.InsightsView
import dev.hermeskotlin.ui.sessions.JobEditorPage
import dev.hermeskotlin.designsystem.components.SidebarLayout
import dev.hermeskotlin.designsystem.components.rememberSidebarState
import dev.hermeskotlin.core.settings.ThemeMode
import dev.hermeskotlin.ui.sessions.SessionsSidebarSample
import dev.hermeskotlin.ui.sessions.BotsSidebarSample
import dev.hermeskotlin.core.rooms.Room
import dev.hermeskotlin.core.rooms.RoomPendingAction
import dev.hermeskotlin.ui.bots.BotFaces
import dev.hermeskotlin.ui.bots.LocalBotFaces
import dev.hermeskotlin.ui.rooms.RoomActions
import dev.hermeskotlin.ui.rooms.RoomView
import dev.hermeskotlin.ui.plugins.PluginsUiState
import dev.hermeskotlin.ui.plugins.PluginsView
import dev.hermeskotlin.ui.sessions.ProjectDraft
import dev.hermeskotlin.core.projects.FolderListing
import dev.hermeskotlin.ui.sessions.ProjectActionsSheet
import dev.hermeskotlin.ui.sessions.NewProjectDialog
import dev.hermeskotlin.ui.settings.ConnectionCheckSheet
import dev.hermeskotlin.ui.settings.GatewayInfo
import dev.hermeskotlin.ui.settings.SettingsView
import androidx.compose.ui.tooling.preview.Preview
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.models.PickerScope
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.DEFAULT_ACCENT
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalAccentPalette
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.ui.LocalAppSettings
import dev.hermeskotlin.ui.chat.ChatView
import dev.hermeskotlin.ui.chat.ModelPickerState
import dev.hermeskotlin.ui.chat.ModelSheet
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.VoiceChatState

/** The screens the README shows, drawn from [ChatSamples]. */
enum class PreviewScene(val label: String) {
    Reply("A finished reply"),
    Working("A turn at work"),
    Approval("An approval"),
    LongApproval("An approval for a long command"),
    BareApproval("An approval with no tool or purpose"),
    VaultUnlock("A locked password manager"),
    VaultCode("A sign-in code"),
    VaultSaveLogin("A login to save"),
    Undelivered("Messages that lost their reply"),
    Subagents("Subagents"),
    Voice("Voice chat"),
    NewChat("A new chat"),
    Sidebar("The sessions sidebar"),
    Bots("The bots and rooms"),
    Room("A room of bots"),
    Settings("Settings"),
    Insights("Insights"),
    Capabilities("Capabilities"),
    Plugins("Plugins with a page"),
    Scheduled("Scheduled jobs"),
    ScheduledJob("A scheduled job and its runs"),
    JobEditor("A new scheduled job"),
    Usage("Usage and context"),
    Processes("Background processes"),
    Comments("Comments on a reply"),
    MidTask("Typing while a task runs"),
    ConnectionCheck("The connection check"),
    LongChat("A chat to scroll back through"),
    Notices("Notices from the gateway"),
    Archived("Archived, with Undo"),
    Checkpoints("Checkpoints, one opened on its changes"),
    ProjectOptions("A project's options"),
    NewProject("A new project"),
    Models("Your starred and recent models in the model picker"),
    ModelsAll("Every model, one row per model, in the model picker"),
    ModelSearch("A search in the model picker"),
    Offline("A chat that can't reach the gateway"),
    EmptyPage("A page with nothing on it yet"),
    LoadError("A page that couldn't load"),
    Loading("A page loading"),
    Connect("Connecting to a gateway that answered"),
    ConnectFailed("A gateway that didn't answer"),
    SignIn("Signing in"),
}

/**
 * One scene full-screen in the app theme: Android Studio previews and the debug screenshot gallery both use it.
 * [accent] is an [AccentPalette] name.
 */
@Composable
fun HeraldPreview(scene: PreviewScene, dark: Boolean = true, accent: String = DEFAULT_ACCENT) {
    val palette = AccentPalette.named(accent)
    hermesTheme(palette)(if (dark) ColorScheme.Dark else ColorScheme.Light) {
        CompositionLocalProvider(LocalAccentPalette provides palette, LocalAppSettings provides AppSettings(showPet = false)) {
            when (scene) {
                PreviewScene.Reply -> SampleChat(ChatSamples.reply)
                PreviewScene.Notices -> SampleChat(ChatSamples.notices)
                PreviewScene.Working -> SampleChat(ChatSamples.working)
                PreviewScene.Approval -> SampleChat(ChatSamples.approval)
                PreviewScene.LongApproval -> SampleChat(ChatSamples.longApproval)
                PreviewScene.BareApproval -> SampleChat(ChatSamples.bareApproval)
                PreviewScene.VaultUnlock -> SampleChat(ChatSamples.vaultUnlock)
                PreviewScene.VaultCode -> SampleChat(ChatSamples.vaultCode)
                PreviewScene.VaultSaveLogin -> SampleChat(ChatSamples.vaultSaveLogin)
                PreviewScene.Undelivered -> SampleChat(ChatSamples.undelivered)
                PreviewScene.Subagents -> SampleChat(ChatSamples.subagents)
                PreviewScene.Voice -> SampleChat(ChatSamples.voice, voiceChat = ChatSamples.listening)
                PreviewScene.NewChat -> SampleChat(ChatSamples.empty, placeholder = "What are we building?")
                PreviewScene.Sidebar -> OpenSidebar {
                    SessionsSidebarSample(
                        ChatSamples.sessions(),
                        selectedId = "s1",
                        userLabel = ChatSamples.USER,
                        statuses = ChatSamples.sessionStatuses,
                        drafts = ChatSamples.sessionDrafts,
                        projects = ChatSamples.projects,
                    )
                }
                PreviewScene.Bots -> OpenSidebar {
                    BotsSidebarSample(
                        BotSamples.roster,
                        userLabel = ChatSamples.USER,
                        nowSeconds = BotSamples.now,
                        needsYou = BotSamples.needsYou,
                        rooms = BotSamples.rooms,
                    )
                }
                PreviewScene.Room -> {
                    val faces = remember { BotFaces(BotSamples.roster.all) }
                    CompositionLocalProvider(LocalBotFaces provides faces) {
                        RoomView(BotSamples.openRoom, remember { PreviewRoomActions() }, faces, onOpenSidebar = {}, onBack = {})
                    }
                }
                PreviewScene.Archived -> Box(Modifier.fillMaxSize()) {
                    OpenSidebar {
                        SessionsSidebarSample(
                            ChatSamples.sessions().filterNot { it.id == "s3" },
                            selectedId = "s1",
                            userLabel = ChatSamples.USER,
                            statuses = ChatSamples.sessionStatuses,
                            drafts = ChatSamples.sessionDrafts,
                        )
                    }
                    Toast(
                        "Archived “${ChatSamples.sessions().first { it.id == "s3" }.displayTitle}”",
                        icon = Lucide.Archive,
                        modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(14.dp).fillMaxWidth(),
                        actionLabel = "Undo",
                    )
                }
                PreviewScene.ProjectOptions, PreviewScene.NewProject -> Box(Modifier.fillMaxSize()) {
                    val site = ChatSamples.projects.first { it.id == "p-site" }
                    OpenSidebar {
                        SessionsSidebarSample(
                            ChatSamples.sessions(),
                            selectedId = "s1",
                            userLabel = ChatSamples.USER,
                            statuses = ChatSamples.sessionStatuses,
                            drafts = ChatSamples.sessionDrafts,
                            projects = ChatSamples.projects,
                            selectedProject = site,
                            canMakeProjects = true,
                        )
                    }
                    if (scene == PreviewScene.ProjectOptions) {
                        ProjectActionsSheet(site, onDismiss = {}, onRename = {}, onDelete = {}, onSave = {})
                    } else {
                        NewProjectDialog(
                            ProjectDraft(name = "Herald", folder = "/home/you/projects/herald"),
                            busy = false,
                            error = null,
                            onDismiss = {},
                            onCreate = { _, _ -> },
                            // Browse folders opens on sample folders, for the picker's screenshots.
                            listFolders = { dir, _ ->
                                FolderListing(
                                    if (dir.endsWith("/projects/")) listOf("herald", "notes-app", "site") else listOf("androidApp", "docs", "shared"),
                                )
                            },
                        )
                    }
                }
                PreviewScene.Comments -> SampleChat(ChatSamples.reply, comments = remember { ChatSamples.comments() })
                PreviewScene.LongChat -> SampleChat(ChatSamples.longChat)
                PreviewScene.MidTask ->SampleChat(ChatSamples.working, composerText = "Also check the photos share")
                PreviewScene.Offline -> SampleChat(ChatSamples.reply, offline = true)
                PreviewScene.EmptyPage -> OpenSidebar {
                    SidebarPage {
                        Column {
                            SubpageHeader("Scheduled", onBack = {})
                            EmptyState(
                                Lucide.CalendarClock,
                                "No scheduled jobs",
                                "Have the agent do something on a schedule, like a morning briefing. Each run opens as a chat here.",
                            ) { Button("New job", onClick = {}, leadingIcon = Lucide.Plus) }
                        }
                    }
                }
                PreviewScene.LoadError -> OpenSidebar {
                    SidebarPage {
                        Column {
                            SubpageHeader("Capabilities", onBack = {})
                            EmptyState(Lucide.CloudOff, "Couldn't load", "The gateway didn't answer in time.", error = true) {
                                Button("Try again", onClick = {}, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                            }
                        }
                    }
                }
                PreviewScene.Connect -> SampleConnect(
                    ProbeResult.Reachable(
                        GatewayUrl.parse("https://hermes.example.ts.net"),
                        GatewayStatus(
                            version = "0.9.0",
                            gatewayRunning = true,
                            authRequired = true,
                            authProviders = listOf("basic"),
                            authFlows = listOf("native_pkce"),
                            profiles = listOf("default", "work"),
                        ),
                    ),
                )
                PreviewScene.ConnectFailed -> SampleConnect(
                    ProbeResult.Unreachable(GatewayUrl.parse("192.168.1.20:9119"), "Connection refused"),
                    address = "192.168.1.20:9119",
                )
                PreviewScene.SignIn -> SignInView(
                    host = "hermes.example.ts.net",
                    notice = null,
                    state = SignInUiState(methods = SignInMethods(password = true, browser = true)),
                    username = remember { TextFieldState("alex") },
                    password = remember { TextFieldState("correct-horse") },
                    onSignIn = {},
                    onSignInWithBrowser = {},
                    onCancelBrowser = {},
                    onChangeGateway = {},
                )
                PreviewScene.Loading -> OpenSidebar {
                    SidebarPage { InsightsView(InsightsUiState(), onBack = {}, onSelectPeriod = {}, onRetry = {}) }
                }
                PreviewScene.Insights -> OpenSidebar {
                    SidebarPage { InsightsView(PageSamples.insights(), onBack = {}, onSelectPeriod = {}, onRetry = {}, place = "homelab") }
                }
                PreviewScene.Capabilities -> OpenSidebar {
                    SidebarPage { CapabilitiesView(PageSamples.capabilities, remember { TextFieldState() }, PreviewCapabilitiesActions, onBack = {}) }
                }
                PreviewScene.Plugins -> OpenSidebar {
                    SidebarPage { PluginsView(PluginsUiState(plugins = PageSamples.plugins), onBack = {}, onOpenPlugin = {}, onRetry = {}) }
                }
                PreviewScene.Scheduled, PreviewScene.ScheduledJob -> OpenSidebar {
                    SidebarPage {
                        ScheduledView(
                            PageSamples.scheduled(open = scene == PreviewScene.ScheduledJob),
                            PreviewScheduledActions,
                            selectedId = null,
                            onBack = {},
                            onOpenRun = {},
                        )
                    }
                }
                PreviewScene.JobEditor -> OpenSidebar {
                    SidebarPage {
                        JobEditorPage(
                            PageSamples.jobEditor,
                            prompt = remember { TextFieldState(PageSamples.JOB_PROMPT) },
                            schedule = remember { TextFieldState(PageSamples.JOB_SCHEDULE) },
                            name = remember { TextFieldState(PageSamples.JOB_NAME) },
                            deliveryTargets = PageSamples.deliveryTargets,
                            onSetDeliver = {},
                            onSave = {},
                            onClose = {},
                        )
                    }
                }
                PreviewScene.Usage -> Box(Modifier.fillMaxSize()) {
                    SampleChat(ChatSamples.reply)
                    UsageSheetView(visible = true, state = PageSamples.usage, live = PageSamples.liveUsage, onDismiss = {})
                }
                PreviewScene.Checkpoints -> Box(Modifier.fillMaxSize()) {
                    SampleChat(ChatSamples.reply)
                    CheckpointsSheetView(
                        visible = true,
                        state = PageSamples.checkpoints,
                        onDiff = {},
                        onRestore = {},
                        running = false,
                        onDismiss = {},
                        initiallyExpanded = "9f2c1ab47e0d55aa",
                    )
                }
                PreviewScene.Models, PreviewScene.ModelsAll, PreviewScene.ModelSearch -> Box(Modifier.fillMaxSize()) {
                    SampleChat(ChatSamples.reply)
                    val settings = AppSettings(showPet = false, starredModels = PageSamples.starredModels, recentModels = PageSamples.recentModels)
                    CompositionLocalProvider(LocalAppSettings provides settings) {
                        ModelSheet(
                            visible = true,
                            onDismiss = {},
                            state = ChatState(),
                            picker = ModelPickerState(catalog = PageSamples.modelCatalog),
                            onRefresh = {},
                            onSelectModel = {},
                            onSelectEffort = {},
                            onFast = {},
                            onSettings = {},
                            initialQuery = if (scene == PreviewScene.ModelSearch) "open" else "",
                            initialScope = PickerScope.All.takeIf { scene == PreviewScene.ModelsAll },
                        )
                    }
                }
                PreviewScene.Processes -> Box(Modifier.fillMaxSize()) {
                    SampleChat(ChatSamples.reply)
                    ProcessesSheetView(visible = true, state = PageSamples.processes, onKill = {}, onDismiss = {}, initiallyExpanded = "proc_1")
                }
                PreviewScene.Settings -> SettingsView(
                    settings = AppSettings(theme = if (dark) ThemeMode.Dark else ThemeMode.Light, accent = accent),
                    info = GatewayInfo(userLabel = ChatSamples.USER, version = "0.9.0"),
                    gatewayUrl = "https://hermes.example.ts.net",
                    gatewayName = "homelab",
                    onUpdate = {},
                    onBack = {},
                    onSignOut = {},
                    onOpenGateways = {},
                )
                PreviewScene.ConnectionCheck -> Box(Modifier.fillMaxSize()) {
                    SettingsView(
                        settings = AppSettings(theme = if (dark) ThemeMode.Dark else ThemeMode.Light),
                        info = GatewayInfo(userLabel = ChatSamples.USER, version = "0.9.0"),
                        gatewayUrl = "https://hermes.example.ts.net",
                        gatewayName = "homelab",
                        onUpdate = {},
                        onBack = {},
                        onSignOut = {},
                        onOpenGateways = {},
                    )
                    ConnectionCheckSheet(visible = true, results = PageSamples.connectionCheck, running = false, onDismiss = {}, onCheckAgain = {})
                }
            }
        }
    }
}

/** The sidebar opened as on a phone: the drawer over the chat. A static preview shows it closed. */
@Composable
private fun OpenSidebar(content: @Composable () -> Unit) {
    val sidebar = rememberSidebarState()
    LaunchedEffect(sidebar) { sidebar.open() }
    SidebarLayout(state = sidebar, sidebar = content) { SampleChat(ChatSamples.reply) }
}

/** One of the sidebar's pages, inset as the sidebar insets them. */
@Composable
private fun SidebarPage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))) {
        content()
    }
}

private object PreviewCapabilitiesActions : CapabilitiesActions {
    override fun selectTab(tab: CapabilityTab) = Unit
    override fun refresh() = Unit
    override fun setSkillEnabled(skill: Skill, enabled: Boolean) = Unit
    override fun setToolsetEnabled(toolset: Toolset, enabled: Boolean) = Unit
    override fun setServerEnabled(server: McpServer, enabled: Boolean) = Unit
    override fun testServer(server: McpServer) = Unit
    override fun dismissMessage() = Unit
}

private object PreviewScheduledActions : ScheduledActions {
    override fun refresh() = Unit
    override fun newJob() = Unit
    override fun openJob(jobId: String) = Unit
    override fun closeJob() = Unit
    override fun editJob() = Unit
    override fun runNow() = Unit
    override fun togglePaused() = Unit
    override fun askDelete() = Unit
    override fun cancelDelete() = Unit
    override fun deleteJob() = Unit
}

private class PreviewRoomActions : RoomActions {
    override val composer = TextFieldState()
    override fun send() = Unit
    override fun stop() = Unit
    override fun loadEarlier() = Unit
    override fun approve(action: RoomPendingAction, choice: String) = Unit
    override fun retry(action: RoomPendingAction) = Unit
    override fun renameRoom(room: Room, name: String) = Unit
    override fun deleteRoom(room: Room) = Unit
    override fun dismissRoomNotice() = Unit
}

@Composable
private fun SampleChat(
    state: ChatState,
    voiceChat: VoiceChatState = VoiceChatState(),
    placeholder: String = ChatSamples.PLACEHOLDER,
    comments: List<PendingComment> = emptyList(),
    composerText: String = "",
    /** The gateway can't be reached and the link is being made again. */
    offline: Boolean = false,
) {
    ChatView(
        title = state.title ?: "New chat",
        state = state,
        picker = ModelPickerState(),
        connected = !offline,
        connectionLabel = "No connection · connecting again…",
        linkStatus = "Reconnecting…".takeIf { offline },
        attachments = emptyList(),
        attachmentError = null,
        comments = comments,
        voiceChat = voiceChat,
        dictation = DictationState(),
        suggestions = emptyList(),
        sprite = null,
        placeholder = placeholder,
        notice = null,
        actions = remember(composerText) { PreviewChatActions(composerText) },
        onOpenSidebar = {},
        onNewChat = {},
        onOpenMenu = {},
        onOpenModels = {},
        onAttach = {},
        onDictate = {},
        onVoiceChat = {},
        onOpenPets = {},
        onViewImage = {},
        onNotice = {},
        place = "homelab",
    )
}

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun ReplyPreview() = HeraldPreview(PreviewScene.Reply)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun ReplyLightPreview() = HeraldPreview(PreviewScene.Reply, dark = false)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun WorkingPreview() = HeraldPreview(PreviewScene.Working)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun ApprovalPreview() = HeraldPreview(PreviewScene.Approval)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun SubagentsPreview() = HeraldPreview(PreviewScene.Subagents)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun VoicePreview() = HeraldPreview(PreviewScene.Voice)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun NewChatPreview() = HeraldPreview(PreviewScene.NewChat)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun SidebarPreview() = HeraldPreview(PreviewScene.Sidebar)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun SettingsPreview() = HeraldPreview(PreviewScene.Settings)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun InsightsPreview() = HeraldPreview(PreviewScene.Insights)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun CapabilitiesPreview() = HeraldPreview(PreviewScene.Capabilities)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun JobEditorPreview() = HeraldPreview(PreviewScene.JobEditor)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun UsagePreview() = HeraldPreview(PreviewScene.Usage)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun ProcessesPreview() = HeraldPreview(PreviewScene.Processes)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun CheckpointsPreview() = HeraldPreview(PreviewScene.Checkpoints)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun ProjectOptionsPreview() = HeraldPreview(PreviewScene.ProjectOptions)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun NewProjectPreview() = HeraldPreview(PreviewScene.NewProject)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun ModelsPreview() = HeraldPreview(PreviewScene.Models)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun ModelSearchPreview() = HeraldPreview(PreviewScene.ModelSearch)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun ModelsAllPreview() = HeraldPreview(PreviewScene.ModelsAll)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun ConnectPreview() = HeraldPreview(PreviewScene.Connect)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun ConnectFailedPreview() = HeraldPreview(PreviewScene.ConnectFailed)

@Preview(widthDp = 412, heightDp = 892)
@Composable
private fun SignInPreview() = HeraldPreview(PreviewScene.SignIn)

/** The welcome screen after a test of [address] came back with [result]. */
@Composable
private fun SampleConnect(result: ProbeResult, address: String = "hermes.example.ts.net") {
    ConnectView(
        url = remember { TextFieldState(address) },
        state = ConnectUiState(result = result),
        access = AccessToken(remember { TextFieldState() }, remember { TextFieldState() }, saved = false, onForget = {}),
        onTest = {},
        onContinue = {},
        onCancel = null,
    )
}
