import SwiftUI

/// The three bottom-bar destinations — the printer is home, in the middle.
enum RootTab { case sheets, printer, settings }

/// Which tab is showing. Owned by RootView; screens read it to jump between tabs
/// (e.g. "Set me up" → the Sheets tab).
@MainActor final class Nav: ObservableObject {
    @Published var tab: RootTab = .printer
}

// ── shared settings-row furniture, used by both the Sheets and Settings tabs ──

/// Hairline between rows inside a card.
struct RowDivider: View {
    var body: some View { Rectangle().fill(Ui.border).frame(height: 1).padding(.vertical, 10) }
}

/// A leading icon in a rounded tile — the left rail of a settings row.
struct SettingIcon: View {
    let name: String
    var tint: Color = Ui.text2
    var body: some View {
        Image(systemName: name)
            .font(.system(size: 15, weight: .medium)).foregroundColor(tint)
            .frame(width: 34, height: 34)
            .background(RoundedRectangle(cornerRadius: 9).fill(Ui.surface2))
            .overlay(RoundedRectangle(cornerRadius: 9).stroke(Ui.border, lineWidth: 1))
    }
}

/// The slim technology strip at the foot of a section — title/sub on the left,
/// chips stacked on the right.
struct TechRow: View {
    let icon: String, title: String, sub: String, chips: [String]
    var body: some View {
        HStack(spacing: 12) {
            SettingIcon(name: icon)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Ui.body(14, weight: 600)).foregroundColor(Ui.text)
                Text(sub).font(Ui.body(12)).foregroundColor(Ui.text3)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 5) {
                ForEach(chips, id: \.self) { Chip(text: $0).fixedSize() }
            }
        }
    }
}

/// A tab screen's title header (the bottom bar replaced the old back-arrow headers).
struct TabHeader: View {
    let title: String
    var body: some View {
        HStack {
            Text(title).font(Ui.display(24, weight: 800, width: 100)).foregroundColor(Ui.text)
            Spacer()
            RingMark(size: 22)
        }
        .padding(.bottom, 4)
    }
}
