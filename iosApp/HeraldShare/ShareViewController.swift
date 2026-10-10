import UIKit
import UniformTypeIdentifiers

/// The share sheet's "Herald": copies what was shared (text, a link, files) into the App Group's inbox. When
/// Herald next comes to the front, it becomes a new chat's draft there; nothing is sent until you send it. The
/// shared UI never runs here: an extension has little memory.
final class ShareViewController: UIViewController {
    /// Shared with the app (IosLinks.APP_GROUP).
    private static let appGroup = "group.dev.herald"
    /// Larger files stay behind: the app attaches at most 25 MB per file anyway. Photos may be larger, as the
    /// app scales them down before attaching them.
    private static let maxFileBytes = 25 * 1024 * 1024
    private static let maxImageBytes = 50 * 1024 * 1024
    private static let maxFiles = 10

    /// Why a file stayed behind.
    private enum Skip: Error { case tooLarge, unreadable }

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
        // Written under a hidden name and moved into place at the end, so the app never reads half a share.
        let staging = inbox.appendingPathComponent(".\(id)", isDirectory: true)
        let folder = inbox.appendingPathComponent(id, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        } catch {
            return fail("Herald couldn't take this share.")
        }

        let items = extensionContext?.inputItems.compactMap { $0 as? NSExtensionItem } ?? []
        var texts: [String] = []
        var files: [String] = []
        var tooLarge = 0
        var tooMany = 0
        var unreadable = 0
        for provider in items.flatMap({ $0.attachments ?? [] }) {
            if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) || isFile(provider) {
                if files.count >= Self.maxFiles {
                    tooMany += 1
                    continue
                }
                switch await copy(provider, into: staging, number: files.count + 1) {
                case .success(let name): files.append(name)
                case .failure(.tooLarge): tooLarge += 1
                case .failure(.unreadable): unreadable += 1
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

        var share: [String: Any] = ["files": files, "tooLarge": tooLarge, "tooMany": tooMany, "unreadable": unreadable]
        if !texts.isEmpty { share["text"] = texts.joined(separator: "\n") }
        if let title = items.compactMap({ $0.attributedTitle?.string }).first(where: { !$0.isEmpty }) { share["subject"] = title }
        guard let data = try? JSONSerialization.data(withJSONObject: share),
              (try? data.write(to: staging.appendingPathComponent("share.json"))) != nil,
              (try? FileManager.default.moveItem(at: staging, to: folder)) != nil
        else {
            try? FileManager.default.removeItem(at: staging)
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

    /// Copies one file into `<number>/<its name>` under [folder], a folder of its own so two files of the same
    /// name don't collide and each keeps its name. Returns that path, as the manifest lists it.
    private func copy(_ provider: NSItemProvider, into folder: URL, number: Int) async -> Result<String, Skip> {
        // The file itself, never a link to it: a shared web page is a link and goes in as text.
        let type = provider.registeredTypeIdentifiers.first { identifier in
            guard let type = UTType(identifier) else { return false }
            return type.conforms(to: .data) && !type.conforms(to: .url)
        } ?? UTType.data.identifier
        let limit = UTType(type)?.conforms(to: .image) == true ? Self.maxImageBytes : Self.maxFileBytes
        return await withCheckedContinuation { done in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                // The provider's copy is gone once this returns: copy it out now.
                guard let url, let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize else {
                    return done.resume(returning: .failure(.unreadable))
                }
                guard size <= limit else { return done.resume(returning: .failure(.tooLarge)) }
                var name = provider.suggestedName ?? url.lastPathComponent
                if (name as NSString).pathExtension.isEmpty && !url.pathExtension.isEmpty {
                    name += ".\(url.pathExtension)"
                }
                name = name.replacingOccurrences(of: "/", with: "_")
                if name.isEmpty || name == "." || name == ".." { name = "file" + (url.pathExtension.isEmpty ? "" : ".\(url.pathExtension)") }
                let slot = folder.appendingPathComponent(String(number), isDirectory: true)
                do {
                    try FileManager.default.createDirectory(at: slot, withIntermediateDirectories: true)
                    try FileManager.default.copyItem(at: url, to: slot.appendingPathComponent(name))
                    done.resume(returning: .success("\(number)/\(name)"))
                } catch {
                    done.resume(returning: .failure(.unreadable))
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
