import Foundation

/// Mirrors go/core Model.kt: palettes for BW/BWR/BWRY in RePaper order (white first);
/// extended schemes keep the SDK's own order (black first) so wire encodings are direct LUTs.
public struct Palette: Sendable {
    public let name: String
    public let colors: [[Int]]

    public static let all: [String: Palette] = {
        let W = [255, 255, 255], B = [0, 0, 0], R = [255, 0, 0], Y = [255, 255, 0]
        let G = [0, 255, 0], U = [0, 0, 255], O = [255, 128, 0]
        func gray(_ v: Int) -> [Int] { [v, v, v] }
        return [
            "BW": Palette(name: "BW", colors: [W, B]),
            "BWR": Palette(name: "BWR", colors: [W, B, R]),
            "BWRY": Palette(name: "BWRY", colors: [W, B, R, Y]),
            "BWGBRY": Palette(name: "BWGBRY", colors: [B, W, Y, R, U, G]),
            "7COLOR": Palette(name: "7COLOR", colors: [B, W, Y, R, U, G, O]),
            "GRAY4": Palette(name: "GRAY4", colors: [B, gray(85), gray(170), W]),
            "GRAY16": Palette(name: "GRAY16", colors: (0...15).map { gray($0 * 17) }),
        ]
    }()
}

public struct SheetModel: Sendable {
    public let width: Int
    public let height: Int
    public let palette: String
    public let inset: [Int]   // left, top, right, bottom

    public init(width: Int, height: Int, palette: String = "BW", inset: [Int] = [0, 0, 0, 0]) {
        self.width = width; self.height = height; self.palette = palette; self.inset = inset
    }
    public var colors: Palette { Palette.all[palette]! }
    public var visible: (Int, Int, Int, Int) { (inset[0], inset[1], width - inset[2], height - inset[3]) }
}

/// A rendered page: palette indexes, exactly width×height.
public struct Page: Sendable {
    public let indexes: [Int]
    public let model: SheetModel
    public init(indexes: [Int], model: SheetModel) {
        precondition(indexes.count == model.width * model.height, "Page must be at the sheet's exact size")
        self.indexes = indexes; self.model = model
    }
}

public enum ColorScheme: Int, Sendable, CaseIterable {
    case mono = 0, bwr = 1, bwy = 2, bwry = 3
    case bwgbry = 4, gray4 = 5, gray16 = 6, sevenColor = 7, bwgbrySplit = 8

    public var paletteKey: String {
        switch self {
        case .mono: return "BW"
        case .bwr, .bwy: return "BWR"
        case .bwry: return "BWRY"
        case .bwgbry, .bwgbrySplit: return "BWGBRY"
        case .sevenColor: return "7COLOR"
        case .gray4: return "GRAY4"
        case .gray16: return "GRAY16"
        }
    }

    public static func fromWire(_ v: Int) throws -> ColorScheme {
        guard let s = ColorScheme(rawValue: v) else { throw OdError.protocolError("unsupported color scheme \(v)") }
        return s
    }
}

public struct Capabilities: Sendable, CustomStringConvertible {
    public let width: Int
    public let height: Int
    public let scheme: ColorScheme
    public let rotation: Int
    public let panelIc: Int

    public init(width: Int, height: Int, scheme: ColorScheme, rotation: Int, panelIc: Int = 0) {
        self.width = width; self.height = height; self.scheme = scheme; self.rotation = rotation; self.panelIc = panelIc
    }
    public var viewedWidth: Int { rotation == 90 || rotation == 270 ? height : width }
    public var viewedHeight: Int { rotation == 90 || rotation == 270 ? width : height }
    public var description: String { "Capabilities(\(width)x\(height) \(scheme) rot\(rotation) ic\(panelIc))" }
}

public enum OdError: Error, LocalizedError, Equatable {
    case protocolError(String)
    case authError(String)
    case timeout(String)

    public var errorDescription: String? {
        switch self {
        case .protocolError(let m), .authError(let m), .timeout(let m): return m
        }
    }
}

public extension Data {
    var hexLower: String { map { String(format: "%02x", $0) }.joined() }
    init?(hexString: String) {
        let s = hexString.replacingOccurrences(of: ":", with: "").replacingOccurrences(of: " ", with: "")
        guard s.count % 2 == 0 else { return nil }
        var out = Data(capacity: s.count / 2)
        var idx = s.startIndex
        while idx < s.endIndex {
            let next = s.index(idx, offsetBy: 2)
            guard let b = UInt8(s[idx..<next], radix: 16) else { return nil }
            out.append(b); idx = next
        }
        self = out
    }
}
