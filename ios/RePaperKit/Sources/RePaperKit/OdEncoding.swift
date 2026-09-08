import Foundation

/// Panel byte encodings, byte-identical to py-opendisplay's encoders (golden-tested).
/// BW/BWR/BWRY indexes arrive in RePaper order (white first); extended schemes use the
/// SDK's own order (black first). Rows zero-pad to whole bytes, MSB first. Some panels
/// change wire codes per IC — hence panelIc.
public enum OdEncoding {

    public static func encode(_ indexes: [Int], width: Int, height: Int, scheme: ColorScheme, panelIc: Int = 0) -> Data {
        switch scheme {
        case .mono:
            return packBits(indexes, width, height) { $0 == 0 }
        case .bwr:
            return packBits(indexes, width, height) { $0 == 0 || $0 == 2 } + packBits(indexes, width, height) { $0 == 2 }
        case .bwy:
            return packBits(indexes, width, height) { $0 == 0 } + packBits(indexes, width, height) { $0 >= 2 }
        case .bwry:
            return packBitsPerPixel(indexes, width, height, bits: 2, lut: bwryMap(panelIc))
        case .bwgbry:
            return packBitsPerPixel(indexes, width, height, bits: 4, lut: bwgbryLut)
        case .bwgbrySplit:
            let mid = width / 2
            return packHalf(indexes, width, height, x0: 0, x1: mid, lut: bwgbryLut)
                 + packHalf(indexes, width, height, x0: mid, x1: width, lut: bwgbryLut)
        case .sevenColor, .gray16:
            return packBitsPerPixel(indexes, width, height, bits: 4, lut: Array(0..<16))
        case .gray4:
            let codes = gray4Codes(panelIc)
            return packBits(indexes, width, height) { codes[$0 & 3] & 1 != 0 }
                 + packBits(indexes, width, height) { codes[$0 & 3] & 2 != 0 }
        }
    }

    static let bwgbryLut = [0, 1, 2, 3, 5, 6] + Array(repeating: 0, count: 10)

    static func bwryMap(_ panelIc: Int) -> [Int] {
        panelIc == 0x001D || panelIc == 0x001E ? [1, 0, 2, 3] : [1, 0, 3, 2]
    }

    static func gray4Codes(_ panelIc: Int) -> [Int] {
        panelIc == 0x0028 || panelIc == 0x0048 ? [3, 2, 1, 0] : [3, 1, 2, 0]
    }

    static func packBits(_ indexes: [Int], _ width: Int, _ height: Int, bit: (Int) -> Bool) -> Data {
        let bpl = (width + 7) / 8
        var out = [UInt8](repeating: 0, count: bpl * height)
        for y in 0..<height {
            let row = y * width
            for x in 0..<width where bit(indexes[row + x]) {
                out[y * bpl + x / 8] |= UInt8(0x80 >> (x % 8))
            }
        }
        return Data(out)
    }

    static func packBitsPerPixel(_ indexes: [Int], _ width: Int, _ height: Int, bits: Int, lut: [Int]) -> Data {
        let ppb = 8 / bits
        let bpl = (width + ppb - 1) / ppb
        var out = [UInt8](repeating: 0, count: bpl * height)
        for y in 0..<height {
            let row = y * width
            for x in 0..<width {
                let code = lut[indexes[row + x] & (lut.count - 1)]
                let shift = (ppb - 1 - x % ppb) * bits
                out[y * bpl + x / ppb] |= UInt8(code << shift)
            }
        }
        return Data(out)
    }

    static func packHalf(_ indexes: [Int], _ width: Int, _ height: Int, x0: Int, x1: Int, lut: [Int]) -> Data {
        let w = x1 - x0
        var half = [Int](repeating: 0, count: w * height)
        for y in 0..<height { for x in 0..<w { half[y * w + x] = indexes[y * width + x0 + x] } }
        return packBitsPerPixel(half, w, height, bits: 4, lut: lut)
    }

    /// Rotate viewed-orientation indexes clockwise by the mounting rotation to native.
    public static func rotateToNative(_ indexes: [Int], viewedW: Int, viewedH: Int, rotation: Int) -> [Int] {
        switch ((rotation % 360) + 360) % 360 {
        case 0: return indexes
        case 90:
            return (0..<indexes.count).map { i in
                let x = i % viewedH, y = i / viewedH
                return indexes[(viewedH - 1 - x) * viewedW + y]
            }
        case 180: return indexes.reversed()
        case 270:
            return (0..<indexes.count).map { i in
                let x = i % viewedH, y = i / viewedH
                return indexes[x * viewedW + (viewedW - 1 - y)]
            }
        default: return indexes
        }
    }
}
