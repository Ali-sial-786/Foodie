import Foundation
import Network

/// Receives the Sign in with ChatGPT redirect on 127.0.0.1 (a loopback redirect, as
/// OpenAI's flow for native apps uses). Listens only on the loopback interface.
final class LoopbackServer {
    private var listener: NWListener?
    private(set) var port: UInt16 = 0
    var onCallback: ((String) -> Void)?

    func start() async throws -> UInt16 {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        listener.newConnectionHandler = { [weak self] connection in self?.handle(connection) }
        final class Once { var done = false }
        let once = Once()
        return try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [weak self] state in
                guard !once.done else { return }
                switch state {
                case .ready:
                    once.done = true
                    let port = listener.port?.rawValue ?? 0
                    self?.port = port
                    continuation.resume(returning: port)
                case .failed(let error):
                    once.done = true
                    continuation.resume(throwing: error)
                default:
                    break
                }
            }
            listener.start(queue: .main)
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func handle(_ connection: NWConnection) {
        connection.start(queue: .main)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, _, _ in
            guard let self, let data, let request = String(data: data, encoding: .utf8) else { connection.cancel(); return }
            let firstLine = request.split(separator: "\r\n", maxSplits: 1).first.map(String.init) ?? ""
            let parts = firstLine.split(separator: " ")
            let path = parts.count >= 2 ? String(parts[1]) : "/"
            let isCallback = path.hasPrefix("/auth/callback")
            let body = isCallback
                ? "<html><body style='font-family:-apple-system;padding:2em'><h2>Almost done</h2><p>You can return to Nouri.</p></body></html>"
                : "<html><body>Not found</body></html>"
            let response = "HTTP/1.1 \(isCallback ? "200 OK" : "404 Not Found")\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
            connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in connection.cancel() })
            if isCallback { self.onCallback?("http://127.0.0.1:\(self.port)\(path)") }
        }
    }
}
