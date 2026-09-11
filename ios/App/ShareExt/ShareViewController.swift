import UIKit
import UniformTypeIdentifiers
import UserNotifications

/// The share-sheet intake: whatever arrives (PDF or image) is spooled into the app
/// group's jobs folder, then we open RePaper Go so the job is ready to print. The panel
/// speaks the design language: carbon, the brand ring, a spinner that resolves to a tick.
final class ShareViewController: UIViewController {
    private let bg = UIColor(red: 0x0C / 255, green: 0x10 / 255, blue: 0x0F / 255, alpha: 1)
    private let surface = UIColor(red: 0x15 / 255, green: 0x1A / 255, blue: 0x18 / 255, alpha: 1)
    private let surface2 = UIColor(red: 0x1C / 255, green: 0x23 / 255, blue: 0x20 / 255, alpha: 1)
    private let border = UIColor(red: 0x36 / 255, green: 0x41 / 255, blue: 0x39 / 255, alpha: 1)
    private let accent = UIColor(red: 0x1E / 255, green: 0xE3 / 255, blue: 0xA5 / 255, alpha: 1)
    private let red = UIColor(red: 1, green: 0.36, blue: 0.30, alpha: 1)
    private let text = UIColor(red: 0xEF / 255, green: 0xF1 / 255, blue: 0xEE / 255, alpha: 1)
    private let text2 = UIColor(red: 0x9A / 255, green: 0xA5 / 255, blue: 0xA0 / 255, alpha: 1)

    private let dim = UIView()
    private let panel = UIView()
    private let iconWrap = UIView()
    private let glyph = UIImageView()
    private let spinner = CAShapeLayer()
    private let titleLabel = UILabel()
    private let status = UILabel()
    private var spinning = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear

        dim.backgroundColor = UIColor.black.withAlphaComponent(0.45)
        dim.frame = view.bounds
        dim.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        dim.alpha = 0
        view.addSubview(dim)

        panel.backgroundColor = surface
        panel.layer.cornerRadius = 24
        panel.layer.cornerCurve = .continuous
        panel.layer.borderWidth = 1
        panel.layer.borderColor = border.cgColor
        panel.layer.shadowColor = UIColor.black.cgColor
        panel.layer.shadowOpacity = 0.5
        panel.layer.shadowRadius = 24
        panel.layer.shadowOffset = CGSize(width: 0, height: 10)
        panel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(panel)

        iconWrap.backgroundColor = surface2
        iconWrap.layer.cornerRadius = 30
        iconWrap.layer.borderWidth = 1
        iconWrap.layer.borderColor = border.cgColor
        iconWrap.translatesAutoresizingMaskIntoConstraints = false
        panel.addSubview(iconWrap)

        glyph.image = UIImage(systemName: "arrow.up.doc.fill",
                              withConfiguration: UIImage.SymbolConfiguration(pointSize: 24, weight: .semibold))
        glyph.tintColor = accent
        glyph.contentMode = .center
        glyph.translatesAutoresizingMaskIntoConstraints = false
        iconWrap.addSubview(glyph)

        titleLabel.text = "RePaper Go"
        titleLabel.textColor = text
        titleLabel.font = .systemFont(ofSize: 17, weight: .heavy)
        titleLabel.textAlignment = .center
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        panel.addSubview(titleLabel)

        status.text = "Adding your page…"
        status.textColor = text2
        status.font = .systemFont(ofSize: 14, weight: .regular)
        status.textAlignment = .center
        status.numberOfLines = 2
        status.translatesAutoresizingMaskIntoConstraints = false
        panel.addSubview(status)

        NSLayoutConstraint.activate([
            panel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            panel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            panel.widthAnchor.constraint(equalToConstant: 280),
            iconWrap.topAnchor.constraint(equalTo: panel.topAnchor, constant: 28),
            iconWrap.centerXAnchor.constraint(equalTo: panel.centerXAnchor),
            iconWrap.widthAnchor.constraint(equalToConstant: 60),
            iconWrap.heightAnchor.constraint(equalToConstant: 60),
            glyph.centerXAnchor.constraint(equalTo: iconWrap.centerXAnchor),
            glyph.centerYAnchor.constraint(equalTo: iconWrap.centerYAnchor),
            titleLabel.topAnchor.constraint(equalTo: iconWrap.bottomAnchor, constant: 18),
            titleLabel.leadingAnchor.constraint(equalTo: panel.leadingAnchor, constant: 20),
            titleLabel.trailingAnchor.constraint(equalTo: panel.trailingAnchor, constant: -20),
            status.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 6),
            status.leadingAnchor.constraint(equalTo: panel.leadingAnchor, constant: 20),
            status.trailingAnchor.constraint(equalTo: panel.trailingAnchor, constant: -20),
            status.bottomAnchor.constraint(equalTo: panel.bottomAnchor, constant: -26),
        ])

        // the spinner arc lives around the icon
        spinner.fillColor = UIColor.clear.cgColor
        spinner.strokeColor = accent.cgColor
        spinner.lineWidth = 3
        spinner.lineCap = .round
        spinner.strokeStart = 0
        spinner.strokeEnd = 0.28
        iconWrap.layer.addSublayer(spinner)

        // enter: dim fades, panel springs in
        panel.alpha = 0
        panel.transform = CGAffineTransform(scaleX: 0.9, y: 0.9)
        UIView.animate(withDuration: 0.2) { self.dim.alpha = 1 }
        UIView.animate(withDuration: 0.45, delay: 0, usingSpringWithDamping: 0.7,
                       initialSpringVelocity: 0.6, options: []) {
            self.panel.alpha = 1
            self.panel.transform = .identity
        }
        startSpin()
        spool()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let inset: CGFloat = 4
        let r = iconWrap.bounds.insetBy(dx: inset, dy: inset)
        spinner.frame = iconWrap.bounds
        spinner.path = UIBezierPath(ovalIn: r).cgPath
    }

    private func startSpin() {
        guard !spinning else { return }
        spinning = true
        let rot = CABasicAnimation(keyPath: "transform.rotation.z")
        rot.fromValue = 0
        rot.toValue = 2 * Double.pi
        rot.duration = 0.9
        rot.repeatCount = .infinity
        spinner.add(rot, forKey: "spin")
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
            self.spinner.removeAnimation(forKey: "spin")
            self.spinner.isHidden = true
            let cfg = UIImage.SymbolConfiguration(pointSize: 26, weight: .bold)
            if ok {
                self.iconWrap.backgroundColor = self.accent
                self.iconWrap.layer.borderColor = self.accent.cgColor
                self.glyph.image = UIImage(systemName: "checkmark", withConfiguration: cfg)
                self.glyph.tintColor = self.bg
                self.titleLabel.text = "Added to RePaper Go"
                self.status.text = "Tap the notification to put it on a sheet."
                self.popIcon()
                self.notifyReady()
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.3) {
                    self.extensionContext?.completeRequest(returningItems: nil)
                }
            } else {
                self.iconWrap.layer.borderColor = self.red.cgColor
                self.glyph.image = UIImage(systemName: "xmark", withConfiguration: cfg)
                self.glyph.tintColor = self.red
                self.titleLabel.text = "Can't add this"
                self.status.text = "Nothing printable here."
                self.popIcon()
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                    self.extensionContext?.completeRequest(returningItems: nil)
                }
            }
        }
    }

    private func popIcon() {
        iconWrap.transform = CGAffineTransform(scaleX: 0.6, y: 0.6)
        UIView.animate(withDuration: 0.4, delay: 0, usingSpringWithDamping: 0.55,
                       initialSpringVelocity: 0.8, options: []) {
            self.iconWrap.transform = .identity
        }
    }

    /// A local notification so the person can jump back into the app and print — the
    /// sanctioned way for a share extension to hand off to its container app. It only
    /// shows if the app was granted notification permission; the job is spooled either way.
    private func notifyReady() {
        let content = UNMutableNotificationContent()
        content.title = "Ready to print"
        content.body = "A shared page is waiting in RePaper Go — tap to put it on a sheet."
        content.sound = .default
        content.userInfo = ["action": "print"]
        let req = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req, withCompletionHandler: nil)
    }
}
