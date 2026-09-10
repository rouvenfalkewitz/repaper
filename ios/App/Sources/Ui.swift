import SwiftUI
import CoreText

/// The Paper design system, iOS edition — the same tokens as brand/tokens.css and the
/// Android app's Ui.kt, applied through factories so every screen styles alike.
enum Ui {
    // carbon scale + accent, verbatim from tokens.css
    static let bg = rgb(0x0C100F)            // --carbon
    static let surface = rgb(0x151A18)       // --carbon-2
    static let surface2 = rgb(0x1C2320)      // --carbon-3 (bezels)
    static let border = rgb(0x273029)        // --carbon-4
    static let borderStrong = rgb(0x364139)
    static let text = rgb(0xEFF1EE)
    static let text2 = rgb(0x9AA5A0)
    static let text3 = rgb(0x5E6A64)
    static let accent = rgb(0x1EE3A5)        // --re-400
    static let onAccent = bg
    static let accentTint = rgb(0x0A2E24)    // --re-900
    static let red = rgb(0xFF5C4D)
    static let amber = rgb(0xFFB020)

    // e-paper + signal tokens (tokens.css)
    static let epaperPanel = rgb(0xE9EBE6)
    static let epaperBezel = rgb(0x1C2320)
    static let epaperRed = rgb(0xC8102E)
    static let ink = rgb(0x131614)
    static let epaperYellow = rgb(0xF2C400)
    static let blue = rgb(0x4D8DFF)
    static let redTint = rgb(0x3A1512)
    static let amberTint = rgb(0x3A2A08)
    static let blueTint = rgb(0x102340)

    static func rgb(_ v: UInt32) -> Color {
        Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }

    // ── fonts: the bundled Archivo/Figtree variable faces, weight + width via axes ──
    private static let wght: Int64 = 0x77676874   // 'wght'
    private static let wdth: Int64 = 0x77647468   // 'wdth'

    private static func variable(_ psName: String, size: CGFloat, weight: CGFloat, width: CGFloat? = nil) -> Font {
        // look the face up by its PostScript name (the variable TTFs register as
        // Archivo-SemiBold / Figtree-Light), then dial the axes on top
        let base = CTFontCreateWithName(psName as CFString, size, nil)
        guard (CTFontCopyPostScriptName(base) as String) == psName else {
            return .system(size: size, weight: weight >= 650 ? .bold : .regular)   // previews / missing font
        }
        var variation: [NSNumber: CGFloat] = [NSNumber(value: wght): weight]
        if let width { variation[NSNumber(value: wdth)] = width }
        let desc = CTFontDescriptorCreateWithAttributes([kCTFontVariationAttribute: variation] as CFDictionary)
        return Font(CTFontCreateCopyWithAttributes(base, size, nil, desc))
    }

    /// Display face: Archivo, like the web's font-stretch.
    static func display(_ size: CGFloat, weight: CGFloat = 700, width: CGFloat = 100) -> Font {
        variable("Archivo-SemiBold", size: size, weight: weight, width: width)
    }
    static func body(_ size: CGFloat, weight: CGFloat = 400) -> Font {
        variable("Figtree-Light", size: size, weight: weight)
    }
    static func mono(_ size: CGFloat) -> Font { .system(size: size, design: .monospaced) }
}

// ── components, Dock anatomy ─────────────────────────────────────────────────

/// The Dock's ringwrap: rounded square, carbon gradient, hairline border — the device outcut.
struct RingBox<Content: View>: View {
    var size: CGFloat = 148
    @ViewBuilder var content: Content
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size / 4)
                .fill(LinearGradient(colors: [Ui.surface2, Ui.bg], startPoint: .topLeading, endPoint: .bottomTrailing))
                .overlay(RoundedRectangle(cornerRadius: size / 4).stroke(Ui.borderStrong, lineWidth: 1))
            content
        }
        .frame(width: size, height: size)
    }
}

/// Status pill: dot + Archivo caps, tinted per state; the dot blinks like the LED.
struct Pill: View {
    let text: String
    let fg: Color
    let bg: Color
    var blinkMs: Double = 0
    var check: Bool = false

    var body: some View {
        HStack(spacing: 7) {
            if check {
                Text("✓").font(.system(size: 12)).foregroundColor(fg)
            } else if blinkMs > 0 {
                TimelineView(.animation(minimumInterval: blinkMs / 2000)) { tl in
                    let t = tl.date.timeIntervalSince1970 * 1000
                    Circle().fill(fg).frame(width: 7, height: 7)
                        .opacity(t.truncatingRemainder(dividingBy: blinkMs * 2) < blinkMs ? 1 : 0.1)
                }.frame(width: 7, height: 7)
            } else {
                Circle().fill(fg).frame(width: 7, height: 7)
            }
            Text(text.uppercased())
                .font(Ui.display(11, weight: 700, width: 112))
                .kerning(1.0)
                .foregroundColor(fg)
        }
        .padding(.horizontal, 10).padding(.vertical, 4)
        .background(Capsule().fill(bg))
    }
}

/// Protocol chips (the Dock's AirPrint · IPP row).
struct Chip: View {
    let text: String
    var body: some View {
        Text(text)
            .font(Ui.mono(11)).foregroundColor(Ui.text3)
            .padding(.horizontal, 9).padding(.vertical, 3)
            .overlay(Capsule().stroke(Ui.border, lineWidth: 1))
    }
}

/// Uppercase micro-header, the web UI's section label.
struct SectionHeader: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(Ui.display(11, weight: 700, width: 112))
            .kerning(1.3)
            .foregroundColor(Ui.text3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 22).padding(.bottom, 8)
    }
}

/// A card: surface, hairline border, 14pt radius.
struct CardStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14).fill(Ui.surface))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Ui.border, lineWidth: 1))
    }
}
extension View { func card() -> some View { modifier(CardStyle()) } }

/// Pill buttons: primary = accent fill, outline = hairline.
struct UiButton: View {
    let label: String
    var primary = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label)
                .font(Ui.body(15, weight: 700))
                .foregroundColor(primary ? Ui.onAccent : Ui.text2)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 12).fill(primary ? Ui.accent : .clear))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(primary ? .clear : Ui.borderStrong, lineWidth: 1))
        }
    }
}

/// The Dock's palette dots: which inks this sheet speaks.
struct PalDots: View {
    let palette: String
    var body: some View {
        let W = Color.white, B = Color.black
        let colors: [Color] = {
            switch palette {
            case "BWR": return [W, B, Ui.epaperRed]
            case "BWRY": return [W, B, Ui.epaperRed, Ui.epaperYellow]
            case "BWGBRY": return [W, B, Ui.epaperRed, Ui.epaperYellow, Ui.rgb(0x2255DD), Ui.rgb(0x1FA84D)]
            case "7COLOR": return [W, B, Ui.epaperRed, Ui.epaperYellow, Ui.rgb(0x2255DD), Ui.rgb(0x1FA84D), Ui.rgb(0xEE7712)]
            case "GRAY4", "GRAY16": return [W, Ui.rgb(0xAAAAAA), Ui.rgb(0x555555), B]
            default: return [W, B]
            }
        }()
        HStack(spacing: 3) {
            ForEach(Array(colors.enumerated()), id: \.offset) { _, c in
                Circle().fill(c).frame(width: 10, height: 10)
                    .overlay(Circle().stroke(Ui.borderStrong, lineWidth: 1))
            }
        }
    }
}

/// The logo mark, small — for headers.
struct RingMark: View {
    var size: CGFloat = 24
    var body: some View {
        Circle()
            .stroke(Ui.accent, lineWidth: size * 0.321 * 0.175)
            .frame(width: size * 0.321 * 0.913 * 2, height: size * 0.321 * 0.913 * 2)
            .frame(width: size, height: size)
    }
}

/// The product lockup RE|PAPER GO — one composed image, GO rendered through the
/// logo pipeline's exact letterforms (Archivo wght 800 / wdth 125, −0.03 em).
struct BrandLockup: View {
    var height: CGFloat = 28
    var body: some View {
        Image("Lockup")
            .resizable()
            .scaledToFit()
            .frame(height: height)
    }
}
