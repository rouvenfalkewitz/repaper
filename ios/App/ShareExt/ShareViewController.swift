import UIKit
import UniformTypeIdentifiers

/// The share-sheet intake: whatever arrives (PDF or image) is spooled into the app
/// group's jobs folder; RePaper Go picks it up as "Job waiting" on next open.
/// The little panel speaks the design language: carbon, the ring, one calm line.
final class ShareViewController: UIViewController {
    private let bg = UIColor(red: 0x0C / 255, green: 0x10 / 255, blue: 0x0F / 255, alpha: 1)
    private let surface = UIColor(red: 0x15 / 255, green: 0x1A / 255, blue: 0x18 / 255, alpha: 1)
    private let border = UIColor(red: 0x36 / 255, green: 0x41 / 255, blue: 0x39 / 255, alpha: 1)
    private let accent = UIColor(red: 0x1E / 255, green: 0xE3 / 255, blue: 0xA5 / 255, alpha: 1)
    private let text = UIColor(red: 0xEF / 255, green: 0xF1 / 255, blue: 0xEE / 255, alpha: 1)
    private let text2 = UIColor(red: 0x9A / 255, green: 0xA5 / 255, blue: 0xA0 / 255, alpha: 1)

    private let label = UILabel()
    private let ring = UIView()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear

        let panel = UIView()
        panel.backgroundColor = surface
        panel.layer.cornerRadius = 18
        panel.layer.borderWidth = 1
        panel.layer.borderColor = border.cgColor
        panel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(panel)

        ring.translatesAutoresizingMaskIntoConstraints = false
        ring.layer.cornerRadius = 17
        ring.layer.borderWidth = 6
        ring.layer.borderColor = accent.cgColor
        panel.addSubview(ring)

        label.text = "Sending to RePaper Go…"
        label.textColor = text
        label.font = .systemFont(ofSize: 16, weight: .semibold)
        label.textAlignment = .center
        label.numberOfLines = 2
        label.translatesAutoresizingMaskIntoConstraints = false
        panel.addSubview(label)

        NSLayoutConstraint.activate([
            panel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            panel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            panel.widthAnchor.constraint(equalToConstant: 270),
            panel.heightAnchor.constraint(equalToConstant: 140),
            ring.topAnchor.constraint(equalTo: panel.topAnchor, constant: 22),
            ring.centerXAnchor.constraint(equalTo: panel.centerXAnchor),
            ring.widthAnchor.constraint(equalToConstant: 34),
            ring.heightAnchor.constraint(equalToConstant: 34),
            label.topAnchor.constraint(equalTo: ring.bottomAnchor, constant: 14),
            label.leadingAnchor.constraint(equalTo: panel.leadingAnchor, constant: 16),
            label.trailingAnchor.constraint(equalTo: panel.trailingAnchor, constant: -16),
        ])

        UIView.animate(withDuration: 0.9, delay: 0, options: [.repeat, .autoreverse]) {
            self.ring.alpha = 0.25
        }

        spool()
    }

    /// One attachment, PDF or image, into the shared jobs folder.
    private func spool() {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem])?
            .flatMap { $0.attachments ?? [] } ?? []
        let types: [(UTType, String)] = [(.pdf, "pdf"), (.png, "png"), (.jpeg, "jpg"), (.image, "png")]

        for (type, ext) in types {
            guard let p = providers.first(where: { $0.hasItemConformingToTypeIdentifier(type.identifier) }) else { continue }
            p.loadFileRepresentation(forTypeIdentifier: type.identifier) { [weak self] url, _ in
                guard let self else { return }
                guard let url else { return self.finish(ok: false) }
                let label = url.deletingPathExtension().lastPathComponent
                let target = JobStore.newJobURL(label: label, ext: url.pathExtension.isEmpty ? ext
                                                    : url.pathExtension.lowercased())
                do {
                    try FileManager.default.copyItem(at: url, to: target)   // copy NOW — url dies with this callback
                    self.finish(ok: true)
                } catch {
                    self.finish(ok: false)
                }
            }
            return
        }
        finish(ok: false)
    }

    private func finish(ok: Bool) {
        DispatchQueue.main.async {
            self.label.text = ok ? "On its way — open RePaper Go\nto put it on a sheet."
                                 : "Nothing printable here."
            if !ok { self.ring.layer.borderColor = UIColor(red: 1, green: 0.36, blue: 0.30, alpha: 1).cgColor }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.1) {
                self.extensionContext?.completeRequest(returningItems: nil)
            }
        }
    }
}
