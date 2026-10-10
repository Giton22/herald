import AppIntents
import HeraldShared

/// Siri, Shortcuts and Spotlight: the closest thing iOS has to Android's assist entry. Each opens Herald and
/// hands over through the shared links; nothing is sent until you send it.
struct AskHeraldIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask Herald"
    static let description = IntentDescription("Opens a new chat with your question in the composer, ready to send.")
    static let openAppWhenRun = true

    @Parameter(title: "Question", requestValueDialog: "What do you want to ask?")
    var question: String

    @MainActor
    func perform() async throws -> some IntentResult {
        MainViewControllerKt.askHerald(text: question)
        return .result()
    }
}

struct DictateToHeraldIntent: AppIntent {
    static let title: LocalizedStringResource = "Dictate to Herald"
    static let description = IntentDescription("Opens a new chat and starts dictating into its composer.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        MainViewControllerKt.askHerald(text: nil)
        return .result()
    }
}

struct OpenBotIntent: AppIntent {
    static let title: LocalizedStringResource = "Open a Bot"
    static let description = IntentDescription("Opens a bot's chat in Herald, by the bot's profile name.")
    static let openAppWhenRun = true

    @Parameter(title: "Bot", description: "The bot's profile name, as Hermes Desktop shows it.")
    var bot: String

    @MainActor
    func perform() async throws -> some IntentResult {
        let name = bot.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, let path = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) else {
            throw $bot.needsValueError("Which bot?")
        }
        _ = MainViewControllerKt.openLink(url: "hermes://bot/\(path)")
        return .result()
    }
}

/// The phrases Siri knows without any setup, and the actions Shortcuts lists for Herald.
struct HeraldShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AskHeraldIntent(),
            phrases: ["Ask \(.applicationName)", "Ask \(.applicationName) something", "New chat in \(.applicationName)"],
            shortTitle: "Ask Herald",
            systemImageName: "bubble.left.and.text.bubble.right"
        )
        AppShortcut(
            intent: DictateToHeraldIntent(),
            phrases: ["Dictate to \(.applicationName)", "Talk to \(.applicationName)"],
            shortTitle: "Dictate",
            systemImageName: "mic"
        )
    }
}
