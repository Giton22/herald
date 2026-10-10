import AVFAudio
import CallKit
import HeraldShared
import WebRTC

/// A voice chat as a call in the system's call screen: on the Lock Screen and in the status bar while Herald is
/// out of sight, with End there ending the chat. CallKit owns the audio session for the call's length, so
/// WebRTC waits for CallKit to activate it (manual audio) instead of activating it itself.
final class CallKitCall: NSObject, NativeSystemCall, CXProviderDelegate {
    /// App Review turns down CallKit for the China storefront; there a chat carries on without the call screen.
    static var available: Bool {
        Locale.current.region?.identifier != "CN"
    }

    private let provider: CXProvider
    private let controller = CXCallController()
    private var call: UUID?
    private var onEnd: (() -> Void)?

    override init() {
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallGroups = 1
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.generic]
        // A chat with an agent isn't a phone call: keep it out of the Phone app's recents.
        config.includesCallsInRecents = false
        provider = CXProvider(configuration: config)
        super.init()
        // Callbacks on the main queue, where the shared code expects them.
        provider.setDelegate(self, queue: nil)
        RTCAudioSession.sharedInstance().useManualAudio = true
        RTCAudioSession.sharedInstance().isAudioEnabled = false
    }

    func start(onEnd: @escaping () -> Void) {
        end()
        // WebRTC waits for CallKit's activation of this call's audio.
        RTCAudioSession.sharedInstance().isAudioEnabled = false
        let id = UUID()
        call = id
        self.onEnd = onEnd
        let action = CXStartCallAction(call: id, handle: CXHandle(type: .generic, value: "Herald"))
        action.isVoiceCall = true
        controller.request(CXTransaction(action: action)) { [weak self] error in
            guard error != nil else { return }
            // No call screen (refused, or another call is on): the chat goes on without it, with its own audio.
            DispatchQueue.main.async {
                guard let self, self.call == id else { return }
                self.call = nil
                RTCAudioSession.sharedInstance().isAudioEnabled = true
            }
        }
    }

    func end() {
        guard let id = call else { return }
        call = nil
        onEnd = nil
        controller.request(CXTransaction(action: CXEndCallAction(call: id))) { _ in }
    }

    func providerDidReset(_ provider: CXProvider) {
        endedOutside()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        // The category before CallKit activates the session: a call, out of the speaker unless a headset is in.
        try? AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth])
        action.fulfill()
        provider.reportOutgoingCall(with: action.callUUID, connectedAt: Date())
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        action.fulfill()
        // Ended in Herald, through end(): the chat is over already.
        if action.callUUID == call { endedOutside() }
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        // The chat's mute is in Herald's own controls.
        action.fail()
    }

    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        action.fail()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        RTCAudioSession.sharedInstance().audioSessionDidActivate(audioSession)
        RTCAudioSession.sharedInstance().isAudioEnabled = true
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        RTCAudioSession.sharedInstance().isAudioEnabled = false
        RTCAudioSession.sharedInstance().audioSessionDidDeactivate(audioSession)
    }

    private func endedOutside() {
        let onEnd = self.onEnd
        call = nil
        self.onEnd = nil
        onEnd?()
    }
}
