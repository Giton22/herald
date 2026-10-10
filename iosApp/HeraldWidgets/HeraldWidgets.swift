import SwiftUI
import WidgetKit

/// Herald's widgets: plain SwiftUI that reads what the app wrote to the App Group (IosWidgets.kt). No shared
/// framework here and no network: a widget shows the chats as Herald last read them.
@main
struct HeraldWidgetBundle: WidgetBundle {
    var body: some Widget {
        RecentChatsWidget()
        NewChatWidget()
    }
}

// MARK: - Data

/// The App Group file the app writes; the same name as IosWidgets.FILE.
struct WidgetData: Decodable {
    struct Chat: Decodable, Identifiable {
        let id: String
        let title: String
        let at: Double?

        var date: Date? { at.map { Date(timeIntervalSince1970: $0) } }

        var url: URL {
            let path = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? id
            return URL(string: "hermes://session/\(path)") ?? Self.newChat
        }

        static let newChat = URL(string: "hermes://new-chat")!
    }

    let locked: Bool
    let signedIn: Bool
    let chats: [Chat]

    static let appGroup = "group.dev.herald"

    static func read() -> WidgetData? {
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("widget.json"),
            let data = try? Data(contentsOf: url)
        else { return nil }
        return try? JSONDecoder().decode(WidgetData.self, from: data)
    }

    static let sample = WidgetData(
        locked: false,
        signedIn: true,
        chats: [
            Chat(id: "1", title: "Plan the week", at: Date().timeIntervalSince1970 - 600),
            Chat(id: "2", title: "Fix the build", at: Date().timeIntervalSince1970 - 7200),
            Chat(id: "3", title: "Trip ideas", at: Date().timeIntervalSince1970 - 86400),
        ]
    )
}

struct ChatsEntry: TimelineEntry {
    let date: Date
    let data: WidgetData?
}

/// The app reloads the widgets whenever what they show changes, so one entry is enough; the hourly refresh
/// only keeps the "10 min ago" times roughly right.
struct ChatsProvider: TimelineProvider {
    func placeholder(in context: Context) -> ChatsEntry {
        ChatsEntry(date: Date(), data: .sample)
    }

    func getSnapshot(in context: Context, completion: @escaping (ChatsEntry) -> Void) {
        completion(ChatsEntry(date: Date(), data: context.isPreview ? .sample : WidgetData.read()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ChatsEntry>) -> Void) {
        let entry = ChatsEntry(date: Date(), data: WidgetData.read())
        completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(3600))))
    }
}

// MARK: - Recent chats

struct RecentChatsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dev.herald.recent-chats", provider: ChatsProvider()) { entry in
            RecentChatsView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Recent chats")
        .description("Your newest chats, one tap away.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct RecentChatsView: View {
    @Environment(\.widgetFamily) private var family
    let entry: ChatsEntry

    private var rows: Int {
        switch family {
        case .systemSmall: return 1
        case .systemMedium: return 3
        default: return 7
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Herald").font(.headline)
                Spacer()
                // The small size opens one link for the whole widget: no button of its own there.
                if family != .systemSmall {
                    Link(destination: WidgetData.Chat.newChat) {
                        Image(systemName: "square.and.pencil").font(.headline)
                    }
                    .accessibilityLabel("New chat")
                }
            }
            content
            Spacer(minLength: 0)
        }
        .widgetURL(family == .systemSmall ? entry.data?.chats.first?.url ?? WidgetData.Chat.newChat : nil)
    }

    @ViewBuilder
    private var content: some View {
        if let data = entry.data, data.locked {
            Message(icon: "lock.fill", text: "App lock is on: open Herald to see your chats.")
        } else if let data = entry.data, !data.signedIn {
            Message(icon: "person.crop.circle.badge.questionmark", text: "Sign in to Herald to see your chats.")
        } else if let chats = entry.data?.chats, !chats.isEmpty {
            ForEach(chats.prefix(rows)) { chat in
                Link(destination: chat.url) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(chat.title).font(.subheadline).lineLimit(family == .systemSmall ? 3 : 1)
                        if let date = chat.date {
                            Text(date, style: .relative).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    // Redacted where the widget shows on a locked device (StandBy, the iPad Lock Screen).
                    .privacySensitive()
                }
            }
        } else {
            Message(icon: "bubble.left", text: "Open Herald to see your chats here.")
        }
    }
}

private struct Message: View {
    let icon: String
    let text: String

    var body: some View {
        Label(text, systemImage: icon).font(.footnote).foregroundStyle(.secondary)
    }
}

// MARK: - New chat (Lock Screen)

struct NewChatWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dev.herald.new-chat", provider: ChatsProvider()) { _ in
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "bubble.left.and.text.bubble.right").font(.title3)
            }
            .widgetURL(WidgetData.Chat.newChat)
            .containerBackground(.clear, for: .widget)
        }
        .configurationDisplayName("New chat")
        .description("Starts a new chat in Herald.")
        .supportedFamilies([.accessoryCircular])
    }
}
