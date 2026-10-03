package dev.hermeskotlin.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import dev.hermeskotlin.designsystem.HermesTheme
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
    Subagents("Subagents"),
    Voice("Voice chat"),
    NewChat("A new chat"),
    Sidebar("The sessions sidebar"),
    Settings("Settings"),
}

/** One scene full-screen in the app theme: Android Studio previews and the debug screenshot gallery both use it. */
@Composable
fun HeraldPreview(scene: PreviewScene, dark: Boolean = true) {
    HermesTheme(if (dark) ColorScheme.Dark else ColorScheme.Light) {
        CompositionLocalProvider(LocalAppSettings provides AppSettings(showPet = false)) {
            when (scene) {
                PreviewScene.Reply -> SampleChat(ChatSamples.reply)
                PreviewScene.Working -> SampleChat(ChatSamples.working)
                PreviewScene.Approval -> SampleChat(ChatSamples.approval)
                PreviewScene.Subagents -> SampleChat(ChatSamples.subagents)
                PreviewScene.Voice -> SampleChat(ChatSamples.voice, voiceChat = ChatSamples.listening)
                PreviewScene.NewChat -> SampleChat(ChatSamples.empty, placeholder = "What are we building?")
                PreviewScene.Sidebar -> {
                    // Opened as on a phone: the drawer over the chat. A static preview shows it closed.
                    val sidebar = rememberSidebarState()
                    LaunchedEffect(sidebar) { sidebar.open() }
                    SidebarLayout(
                        state = sidebar,
                        sidebar = { SessionsSidebarSample(ChatSamples.sessions(), selectedId = "s1", userLabel = ChatSamples.USER) },
                    ) { SampleChat(ChatSamples.reply) }
                }
                PreviewScene.Settings -> SettingsView(
                    settings = AppSettings(theme = if (dark) ThemeMode.Dark else ThemeMode.Light),
                    info = GatewayInfo(userLabel = ChatSamples.USER, version = "0.9.0"),
                    gatewayUrl = "https://hermes.example.ts.net",
                    onUpdate = {},
                    onBack = {},
                    onSignOut = {},
                    onChangeGateway = {},
                )
            }
        }
    }
}

@Composable
private fun SampleChat(
    state: ChatState,
    voiceChat: VoiceChatState = VoiceChatState(),
    placeholder: String = ChatSamples.PLACEHOLDER,
) {
    ChatView(
        title = state.title ?: "New chat",
        state = state,
        picker = ModelPickerState(),
        connected = true,
        attachments = emptyList(),
        attachmentError = null,
        voiceChat = voiceChat,
        dictation = DictationState(),
        suggestions = emptyList(),
        sprite = null,
        placeholder = placeholder,
        notice = null,
        actions = PreviewChatActions(),
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
