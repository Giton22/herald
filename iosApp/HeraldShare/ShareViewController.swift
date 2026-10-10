import UIKit
import UniformTypeIdentifiers

/// The share sheet's "Herald": copies what was shared (text, a link, files) into the App Group's inbox. When
/// Herald next comes to the front, it becomes a new chat's draft there; nothing is sent until you send it. The
/// shared UI never runs here: an extension has little memory.
final class ShareViewController: UIViewController {
    /// Shared with the app (IosLinks.APP_GROUP).
    private static let appGroup = "group.dev.herald"
    /// Larger files stay behind: the app attaches at most 25 MB per file anyway.
    private static let maxFileBytes = 25 * 1024 * 1024
    private static let maxFiles = 10

    private var started = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !started else { return }
        started = true
        Task { await handOver() }
    }

    private func handOver() async {
        guard let inbox = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup)?
            .appendingPathComponent("Inbox", isDirectory: true)
        else {
            return fail("Herald can't take shares on this device.")
        }
        let id = UUID().uuidString
        let folder = inbox.appendingPathComponent(id, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        } catch {
            return fail("Herald couldn't take this share.")
        }

        let items = extensionContext?.inputItems.compactMap { $0 as? NSExtensionItem } ?? []
        var texts: [String] = []
        var files: [String] = []
        var skipped = 0
        for provider in items.flatMap({ $0.attachments ?? [] }) {
            if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) || isFile(provider) {
                if files.count >= Self.maxFiles {
                    skipped += 1
                } else if let name = await copy(provider, into: folder, index: files.count) {
                    files.append(name)
                } else {
                    skipped += 1
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                if let url = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL {
                    texts.append(url.absoluteString)
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                if let text = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String {
                    texts.append(text)
                }
            }
        }

        var share: [String: Any] = ["files": files, "skipped": skipped]
        if !texts.isEmpty { share["text"] = texts.joined(separator: "\n") }
        if let title = items.compactMap({ $0.attributedTitle?.string }).first(where: { !$0.isEmpty }) { share["subject"] = title }
        guard let data = try? JSONSerialization.data(withJSONObject: share),
              (try? data.write(to: folder.appendingPathComponent("share.json"))) != nil
        else {
            try? FileManager.default.removeItem(at: folder)
            return fail("Herald couldn't take this share.")
        }
        // An extension can't open its app (only private workarounds can, which App Review turns down): say
        // where the share went instead. Herald takes it the next time it comes to the front.
        done(title: "Added to Herald", message: "Open Herald to look it over and send it.")
    }

    /// Photos, documents and the like: anything that isn't plain text or a web link.
    private func isFile(_ provider: NSItemProvider) -> Bool {
        provider.registeredTypeIdentifiers.contains { identifier in
            guard let type = UTType(identifier) else { return false }
            return type.conforms(to: .data) && !type.conforms(to: .text) && !type.conforms(to: .url)
        }
    }

    /// Copies one file into [folder]; its name there, or nil when it couldn't be read or is too large.
    private func copy(_ provider: NSItemProvider, into folder: URL, index: Int) async -> String? {
        let type = provider.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: .data) == true } ?? UTType.data.identifier
        return await withCheckedContinuation { done in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                // The provider's copy is gone once this returns: move it out now.
                guard let url,
                      let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize,
                      size <= Self.maxFileBytes
                else { return done.resume(returning: nil) }
                let original = provider.suggestedName.map { $0 + (url.pathExtension.isEmpty ? "" : ".\(url.pathExtension)") } ?? url.lastPathComponent
                // Numbered, so two files of the same name don't collide; the app shows the name after the number.
                let name = "\(index + 1)-" + original.replacingOccurrences(of: "/", with: "_")
                do {
                    try FileManager.default.copyItem(at: url, to: folder.appendingPathComponent(name))
                    done.resume(returning: name)
                } catch {
                    done.resume(returning: nil)
                }
            }
        }
    }

    private func done(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.extensionContext?.completeRequest(returningItems: nil)
        })
        present(alert, animated: true)
    }

    private func fail(_ message: String) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.extensionContext?.cancelRequest(withError: NSError(domain: "dev.herald.ios.share", code: 1))
        })
        present(alert, animated: true)
    }
}
