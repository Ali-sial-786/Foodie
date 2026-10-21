import Foundation
import NouriShared

/// App data as JSON files in Application Support, excluded from iCloud backup and
/// protected until the phone is first unlocked. Writes are atomic.
final class FileTextStorage: NSObject, TextStorage {
    private let directory: URL

    init(directory: URL) {
        self.directory = directory
        super.init()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = directory
        try? url.setResourceValues(values)
    }

    static func appSupport() -> FileTextStorage {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return FileTextStorage(directory: base.appendingPathComponent("Nouri", isDirectory: true))
    }

    func read(name: String) -> String? {
        try? String(contentsOf: directory.appendingPathComponent(name), encoding: .utf8)
    }

    func write(name: String, text: String) {
        try? Data(text.utf8).write(
            to: directory.appendingPathComponent(name),
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        )
    }

    func delete(name: String) {
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
    }

    func names() -> [String] {
        (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
    }
}
