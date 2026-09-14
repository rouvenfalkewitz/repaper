import Foundation

/// Print jobs waiting for a sheet — PDFs and images spooled by the share extension,
/// consumed by the app. Lives in the app group so both sides see the same folder.
/// Foundation-only: this file is compiled into the app AND the share extension.
enum JobStore {
    static let groupId = "group.net.repaper.go"
    static let extensions = ["pdf", "png", "jpg", "jpeg"]
    /// Marks a spool file as a claimed Print2Go page (not a user's own share job). These
    /// exist only transiently while the page prints, are shown as a MirrorCard (never a
    /// local JobCard), and are swept on launch if a print was interrupted.
    static let mirrorPrefix = "p2g-"

    static var dir: URL {
        let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupId)
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let d = base.appendingPathComponent("jobs", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    /// The user's own waiting jobs — share-to-print spools only. Print2Go spools are
    /// excluded, so a claimed page can never masquerade as a second, local job card.
    static func list() -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        return files.filter { extensions.contains($0.pathExtension.lowercased())
                              && !$0.lastPathComponent.hasPrefix(mirrorPrefix) }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    /// Job file names sort chronologically: <millis>-<label>.<ext>
    static func newJobURL(label: String, ext: String) -> URL {
        let millis = Int(Date().timeIntervalSince1970 * 1000)
        let safe = String(label.prefix(40)).replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_",
                                                                 options: .regularExpression)
        return dir.appendingPathComponent("\(millis)-\(safe.isEmpty ? "job" : safe).\(ext)")
    }

    /// A spool file for a claimed Print2Go page — marked so list() hides it and any
    /// orphan (an interrupted print) can be swept.
    static func newMirrorJobURL(label: String, ext: String) -> URL {
        let name = newJobURL(label: label, ext: ext).lastPathComponent
        return dir.appendingPathComponent(mirrorPrefix + name)
    }

    /// Delete leftover Print2Go spool files. They only exist while a claimed page prints,
    /// so any that survive a launch are orphans from an interrupted/killed run — clearing
    /// them here means a failed Print2Go print can never leave an undeletable ghost.
    static func sweepMirrorOrphans() {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        for f in files where f.lastPathComponent.hasPrefix(mirrorPrefix) { try? FileManager.default.removeItem(at: f) }
    }

    /// The human title a job card shows: the label without the timestamp and extension.
    static func title(_ url: URL) -> String {
        let stem = url.deletingPathExtension().lastPathComponent
        if let dash = stem.firstIndex(of: "-") { return String(stem[stem.index(after: dash)...]) }
        return stem
    }
}
