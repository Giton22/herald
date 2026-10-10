import HeraldShared
import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        MainViewControllerKt.doInitKoin()
        // Live voice calls run on the WebRTC package here; the shared code makes one per call.
        NativeLiveCallKt.setLiveCallMaker { WebRTCLiveCall() }
        return true
    }
}

/// The app's one window. Links, quick actions and shares arrive here and go to the shared code.
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = HostViewController()
        window.makeKeyAndVisible()
        self.window = window
        // Launched by a link or a quick action: open it once the UI is there.
        connectionOptions.urlContexts.forEach { open($0.url) }
        if let item = connectionOptions.shortcutItem { _ = perform(item) }
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        URLContexts.forEach { open($0.url) }
    }

    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem, completionHandler: @escaping (Bool) -> Void) {
        completionHandler(perform(shortcutItem))
    }

    private func perform(_ item: UIApplicationShortcutItem) -> Bool {
        guard let url = item.userInfo?["url"] as? String else { return false }
        return MainViewControllerKt.openLink(url: url)
    }

    private func open(_ url: URL) {
        _ = MainViewControllerKt.openLink(url: url.absoluteString)
    }
}

/// Holds the shared Compose UI and keeps the status bar readable on the theme it shows.
final class HostViewController: UIViewController {
    private var dark = false

    override var preferredStatusBarStyle: UIStatusBarStyle { dark ? .lightContent : .darkContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        let compose = MainViewControllerKt.MainViewController(onDarkTheme: { [weak self] isDark in
            guard let self else { return }
            self.dark = isDark.boolValue
            self.setNeedsStatusBarAppearanceUpdate()
        })
        addChild(compose)
        compose.view.frame = view.bounds
        compose.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(compose.view)
        compose.didMove(toParent: self)
    }
}
