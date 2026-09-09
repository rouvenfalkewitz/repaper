import Foundation

/// Print jobs waiting for a sheet — PDFs and images spooled by the share extension,
/// consumed by the app. Lives in the app group so both sides see the same folder.
/// Foundation-only: this file is compiled into the app AND the share extension.
enum JobStore {
    static let groupId = "group.net.repaper.go"
    static let extensions = ["pdf", "png", "jpg", "jpeg"]

    static var dir: URL {
        let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupId)
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let d = base.appendingPathComponent("jobs", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    static func list() -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        return files.filter { extensions.contains($0.pathExtension.lowercased()) }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    /// Job file names sort chronologically: <millis>-<label>.<ext>
    static func newJobURL(label: String, ext: String) -> URL {
        let millis = Int(Date().timeIntervalSince1970 * 1000)
        let safe = String(label.prefix(40)).replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_",
                                                                 options: .regularExpression)
        return dir.appendingPathComponent("\(millis)-\(safe.isEmpty ? "job" : safe).\(ext)")
    }

    /// The human title a job card shows: the label without the timestamp and extension.
    static func title(_ url: URL) -> String {
        let stem = url.deletingPathExtension().lastPathComponent
        if let dash = stem.firstIndex(of: "-") { return String(stem[stem.index(after: dash)...]) }
        return stem
    }
}
