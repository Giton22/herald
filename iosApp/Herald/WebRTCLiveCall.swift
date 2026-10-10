import AVFAudio
import HeraldShared
import WebRTC

/// A GPT-Live call over WebRTC, as AndroidLiveCall is on Android: the microphone with WebRTC's voice processing
/// (the voice comes out of the speaker, so without echo cancelling the model hears itself), the voice played as a
/// call, and the `oai-events` data channel. The shared code drives it through NativeLiveCall.
final class WebRTCLiveCall: NSObject, NativeLiveCall {
    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        // A call: play-and-record in voice-chat mode (the system's echo cancelling), out of the speaker unless
        // a headset is in. WebRTC applies this when it starts the audio.
        let audio = RTCAudioSessionConfiguration.webRTC()
        audio.category = AVAudioSession.Category.playAndRecord.rawValue
        audio.mode = AVAudioSession.Mode.voiceChat.rawValue
        audio.categoryOptions = [.defaultToSpeaker, .allowBluetooth]
        RTCAudioSessionConfiguration.setWebRTC(audio)
        return RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
    }()

    private static let iceGatherSeconds = 10.0
    private static let speakingLevel = 0.02
    private static let speakingHangover = 0.4
    /// Stats report audio levels as 0..1 amplitude; the shared code's 1.0 is loud speech (Android's RMS / 256 / 42).
    private static let micScale = 32_768.0 / (256.0 * 42.0)
    /// The microphone's running energy at the last poll.
    private var micEnergy: (energy: Double, duration: Double)?

    private let lock = NSLock()
    private var peer: RTCPeerConnection?
    private var channel: RTCDataChannel?
    private var track: RTCAudioTrack?
    private var listener: NativeLiveCallListener?
    private var closed = false
    private var offered = false
    private var loudAt = Date.distantPast
    private var mic: Float = 0
    private var muted = false
    private var statsPending = false

    func start(listener: NativeLiveCallListener) {
        self.listener = listener
        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.iceServers = []
        let none = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let peer = Self.factory.peerConnection(with: config, constraints: none, delegate: self) else {
            return fail("Couldn't set up the call.")
        }
        self.peer = peer

        let processing = ["googEchoCancellation", "googNoiseSuppression", "googAutoGainControl", "googHighpassFilter"]
        let source = Self.factory.audioSource(with: RTCMediaConstraints(
            mandatoryConstraints: Dictionary(uniqueKeysWithValues: processing.map { ($0, "true") }),
            optionalConstraints: nil
        ))
        let track = Self.factory.audioTrack(with: source, trackId: "mic")
        self.track = track
        peer.add(track, streamIds: ["mic"])

        // Before the offer, so the offer carries the channel.
        guard let channel = peer.dataChannel(forLabel: "oai-events", configuration: RTCDataChannelConfiguration()) else {
            return fail("Couldn't open the call's event channel.")
        }
        channel.delegate = self
        self.channel = channel

        peer.offer(for: none) { [weak self] description, error in
            guard let self else { return }
            guard let description else { return self.fail(error?.localizedDescription ?? "Couldn't make the call offer.") }
            peer.setLocalDescription(description) { [weak self] error in
                guard let self else { return }
                if let error { return self.fail(error.localizedDescription) }
                // The vendor answers with whatever candidates are in the offer, so a slow gathering only waits so long.
                DispatchQueue.main.asyncAfter(deadline: .now() + Self.iceGatherSeconds) { [weak self] in self?.sendOffer() }
            }
        }
    }

    func accept(answerSdp: String) {
        guard let peer = currentPeer() else { return }
        peer.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: answerSdp)) { [weak self] error in
            guard let self else { return }
            if let error { self.fail(error.localizedDescription) } else { self.currentListener()?.onAccepted() }
        }
    }

    func send(event: String) -> Bool {
        lock.lock()
        let channel = closed ? nil : channel
        lock.unlock()
        guard let channel, channel.readyState == .open else { return false }
        return channel.sendData(RTCDataBuffer(data: Data(event.utf8), isBinary: false))
    }

    func speaking() -> Bool {
        pollStats()
        lock.lock()
        defer { lock.unlock() }
        return Date().timeIntervalSince(loudAt) < Self.speakingHangover
    }

    func micLevel() -> Float {
        pollStats()
        lock.lock()
        defer { lock.unlock() }
        return muted ? 0 : mic
    }

    func setMuted(muted: Bool) {
        lock.lock()
        self.muted = muted
        if muted { mic = 0 }
        let track = track
        lock.unlock()
        track?.isEnabled = !muted
    }

    func close() {
        lock.lock()
        if closed { return lock.unlock() }
        closed = true
        let (peer, channel, listener) = (self.peer, self.channel, self.listener)
        self.peer = nil
        self.channel = nil
        self.track = nil
        self.listener = nil
        lock.unlock()
        channel?.delegate = nil
        channel?.close()
        peer?.close()
        listener?.onClosed()
    }

    /// The voice's and the microphone's levels from the call's stats, one request at a time.
    private func pollStats() {
        lock.lock()
        guard let peer, !closed, !statsPending else { return lock.unlock() }
        statsPending = true
        lock.unlock()
        peer.statistics { [weak self] report in
            guard let self else { return }
            var voice: Double?
            var energy: Double?
            var duration: Double?
            for stats in report.statistics.values where (stats.values["kind"] as? String) == "audio" {
                if stats.type == "inbound-rtp" { voice = (stats.values["audioLevel"] as? NSNumber)?.doubleValue }
                if stats.type == "media-source" {
                    energy = (stats.values["totalAudioEnergy"] as? NSNumber)?.doubleValue
                    duration = (stats.values["totalSamplesDuration"] as? NSNumber)?.doubleValue
                }
            }
            self.lock.lock()
            self.statsPending = false
            if let voice, voice > Self.speakingLevel { self.loudAt = Date() }
            // audioLevel is a peak; Android's level is an RMS. The RMS since the last poll comes from the
            // running energy (the sum of squared levels times their duration).
            if let energy, let duration {
                if let last = self.micEnergy, duration > last.duration, !self.muted {
                    let rms = ((energy - last.energy) / (duration - last.duration)).squareRoot()
                    self.mic = Float(min(1.0, rms * Self.micScale))
                }
                self.micEnergy = (energy, duration)
            }
            self.lock.unlock()
        }
    }

    private func sendOffer() {
        lock.lock()
        guard !offered, !closed, let sdp = peer?.localDescription?.sdp else { return lock.unlock() }
        offered = true
        let listener = listener
        lock.unlock()
        listener?.onOffer(sdp: sdp)
    }

    /// Hangs up and reports [message] instead of a plain close.
    private func fail(_ message: String) {
        lock.lock()
        let listener = self.listener
        self.listener = nil
        lock.unlock()
        close()
        listener?.onFailed(message: message)
    }

    private func currentPeer() -> RTCPeerConnection? {
        lock.lock()
        defer { lock.unlock() }
        return closed ? nil : peer
    }

    private func currentListener() -> NativeLiveCallListener? {
        lock.lock()
        defer { lock.unlock() }
        return listener
    }
}

extension WebRTCLiveCall: RTCPeerConnectionDelegate {
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        if newState == .complete { sendOffer() }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        if newState == .failed || newState == .closed { close() }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}

extension WebRTCLiveCall: RTCDataChannelDelegate {
    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        if dataChannel.readyState == .closed { close() }
    }

    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        guard let event = String(data: buffer.data, encoding: .utf8) else { return }
        currentListener()?.onEvent(event: event)
    }
}
