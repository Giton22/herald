package dev.hermeskotlin.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.ui.chat.PendingComment
import dev.hermeskotlin.ui.connect.ConnectionChecklist
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
import dev.hermeskotlin.ui.sessions.CapabilityTab
import dev.hermeskotlin.ui.sessions.InsightsView
import dev.hermeskotlin.ui.sessions.JobEditorPage
import dev.hermeskotlin.designsystem.components.SidebarLayout
import dev.hermeskotlin.designsystem.components.rememberSidebarState
import dev.hermeskotlin.core.settings.ThemeMode
import dev.hermeskotlin.ui.sessions.SessionsSidebarSample
import dev.hermeskotlin.ui.settings.GatewayInfo
import dev.hermeskotlin.ui.settings.SettingsView
import androidx.compose.ui.tooling.preview.Preview
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.DEFAULT_ACCENT
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalAccentPalette
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.ui.LocalAppSettings
import dev.hermeskotlin.ui.chat.ChatView
import dev.hermeskotlin.ui.chat.ModelPickerState
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
    Settings("Settings"),
    Insights("Insights"),
    Capabilities("Capabilities"),
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
                        "Archived",
                        modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, top = 64.dp),
                        actionLabel = "Undo",
                        onDismiss = {},
                    )
                }
                PreviewScene.Comments -> SampleChat(ChatSamples.reply, comments = remember { ChatSamples.comments() })
                PreviewScene.LongChat -> SampleChat(ChatSamples.longChat)
                PreviewScene.MidTask ->SampleChat(ChatSamples.working, composerText = "Also check the photos share")
                PreviewScene.Insights -> OpenSidebar {
                    SidebarPage { InsightsView(PageSamples.insights(), onBack = {}, onSelectPeriod = {}, onRetry = {}) }
                }
                PreviewScene.Capabilities -> OpenSidebar {
                    SidebarPage { CapabilitiesView(PageSamples.capabilities, remember { TextFieldState() }, PreviewCapabilitiesActions, onBack = {}) }
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
                PreviewScene.Processes -> Box(Modifier.fillMaxSize()) {
                    SampleChat(ChatSamples.reply)
                    ProcessesSheetView(visible = true, state = PageSamples.processes, onKill = {}, onDismiss = {}, initiallyExpanded = "proc_1")
                }
                PreviewScene.Settings -> SettingsView(
                    settings = AppSettings(theme = if (dark) ThemeMode.Dark else ThemeMode.Light, accent = accent),
                    info = GatewayInfo(userLabel = ChatSamples.USER, version = "0.9.0"),
                    gatewayUrl = "https://hermes.example.ts.net",
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
                        onUpdate = {},
                        onBack = {},
                        onSignOut = {},
                        onOpenGateways = {},
                    )
                    BottomSheet(visible = true, onDismiss = {}) {
                        SheetHeader("Check connection", subtitle = "Each stage is tested on its own.")
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ConnectionChecklist(PageSamples.connectionCheck, running = false)
                            Button("Check again", onClick = {}, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw, modifier = Modifier.fillMaxWidth())
                        }
                    }
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

@Composable
private fun SampleChat(
    state: ChatState,
    voiceChat: VoiceChatState = VoiceChatState(),
    placeholder: String = ChatSamples.PLACEHOLDER,
    comments: List<PendingComment> = emptyList(),
    composerText: String = "",
) {
    ChatView(
        title = state.title ?: "New chat",
        state = state,
        picker = ModelPickerState(),
        connected = true,
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
