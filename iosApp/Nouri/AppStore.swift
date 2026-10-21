import Foundation
import Observation
import NouriShared

/// Holds the shared app model. The model isn't observable itself, so every change
/// bumps `revision`, and views read `revision` before reading from the model.
@Observable
final class AppStore {
    let model: NouriAppModel
    private(set) var revision = 0
    var toast: String?
    /// The diary day being viewed or logged to (ISO date).
    var selectedDate: String

    init(model: NouriAppModel) {
        self.model = model
        self.selectedDate = model.todayIso()
    }

    static func live() -> AppStore {
        let tsv = Bundle.main.url(forResource: "usda_sr28", withExtension: "tsv").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        let library = Bundle.main.url(forResource: "library", withExtension: "json").flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        let model = IosPlatform.shared.makeModel(
            storage: FileTextStorage.appSupport(), secrets: KeychainSecretStore(), usdaTsv: tsv, libraryJson: library, appVersion: version
        )
        return AppStore(model: model)
    }

    func changed(_ message: String? = nil) {
        revision += 1
        if let message { show(message) }
    }

    func show(_ message: String) {
        toast = message
        let shown = message
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if toast == shown { toast = nil }
        }
    }
}
