import SwiftUI

/// The light ring, on screen — behaves exactly like the physical one (brand guidelines §06):
/// Ready = breathe dim (30 %), Job waiting = blink 1 Hz full, Printing = blink 3 Hz,
/// Printed = solid, Failed = blink 1 Hz red, Setup = blink 1 Hz Signal Blue.
struct RingView: View {
    enum Led { case ready, wait, busy, done, err, setup }
    var led: Led = .ready

    var body: some View {
        TimelineView(.animation(minimumInterval: 0.04)) { tl in
            Canvas { ctx, size in
                let t = tl.date.timeIntervalSince1970
                let color: Color = {
                    switch led {
                    case .err: return Ui.red        // Label Red
                    case .setup: return Ui.blue     // Signal Blue
                    default: return Ui.accent       // Re Green
                    }
                }()
                let alpha: Double = {
                    switch led {
                    case .ready: return 0.18 + 0.30 * (0.5 + 0.5 * sin(t / 3.0 * 2 * .pi))          // breathe, dim
                    case .wait, .err, .setup: return t.truncatingRemainder(dividingBy: 1) < 0.5 ? 1 : 0.08   // 1 Hz
                    case .busy: return t.truncatingRemainder(dividingBy: 0.333) < 0.166 ? 1 : 0.08           // 3 Hz
                    case .done: return 1                                                                      // solid
                    }
                }()
                // the logo ring's exact geometry: inside its box the ring spans 72/112 of the
                // width, and the band is (1169-965)/1169 of the outer radius — same as the SVG mark
                let outer = min(size.width, size.height) * 0.321
                let r = outer * 0.913
                let stroke = outer * 0.175
                let rect = CGRect(x: size.width / 2 - r, y: size.height / 2 - r, width: r * 2, height: r * 2)
                var glow = ctx
                glow.addFilter(.blur(radius: stroke))
                glow.stroke(Path(ellipseIn: rect), with: .color(color.opacity(alpha * 70 / 255)), lineWidth: stroke * 2.2)
                ctx.stroke(Path(ellipseIn: rect), with: .color(color.opacity(alpha)), lineWidth: stroke)
            }
        }
    }
}
