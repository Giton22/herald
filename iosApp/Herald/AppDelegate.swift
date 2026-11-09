import HeraldShared
import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        MainViewControllerKt.doInitKoin()
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = HostViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
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
