import Foundation

/// Page image → Page for a specific sheet, the Dock's render/fit.py — line-by-line with
/// go/core's Render.kt: trim white margins, auto-rotate to match orientation, scale to the
/// visible area (contain), center on a white canvas honoring the bezel insets,
/// serpentine Floyd–Steinberg dither to the palette. Pixels are ARGB UInt32.
public enum Render {

    public static func forSheet(_ pixels: [UInt32], width: Int, height: Int,
                                model: SheetModel, dither: Bool = true) -> Page {
        var img = Raster(px: pixels, w: width, h: height)
        if let box = trimBox(img) { img = img.crop(box.0, box.1, box.2, box.3) }

        let vis = model.visible
        let vw = vis.2 - vis.0, vh = vis.3 - vis.1
        if (img.w > img.h) != (vw > vh) { img = img.rotate90() }

        let scale = min(Double(vw) / Double(img.w), Double(vh) / Double(img.h))
        let sw = max(1, Int((Double(img.w) * scale).rounded()))
        let sh = max(1, Int((Double(img.h) * scale).rounded()))
        img = img.resize(sw, sh)

        var canvas = Raster(px: [UInt32](repeating: WHITE, count: model.width * model.height),
                            w: model.width, h: model.height)
        canvas.paste(img, vis.0 + (vw - sw) / 2, vis.1 + (vh - sh) / 2)

        return Page(indexes: ditherToPalette(canvas, model.colors, dither), model: model)
    }

    private static let WHITE: UInt32 = 0xFFFFFFFF

    /// Bounding box of non-white content (luma < 239, like the Dock's `invert > 16` trim).
    private static func trimBox(_ img: Raster) -> (Int, Int, Int, Int)? {
        var x0 = img.w, y0 = img.h, x1 = -1, y1 = -1
        for y in 0..<img.h {
            for x in 0..<img.w where luma(img.px[y * img.w + x]) < 239 {
                if x < x0 { x0 = x }; if x > x1 { x1 = x }
                if y < y0 { y0 = y }; if y > y1 { y1 = y }
            }
        }
        return x1 < 0 ? nil : (x0, y0, x1 + 1, y1 + 1)
    }

    private static func luma(_ argb: UInt32) -> Int {
        let r = Int((argb >> 16) & 0xFF), g = Int((argb >> 8) & 0xFF), b = Int(argb & 0xFF)
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /// Serpentine Floyd–Steinberg on RGB, error-diffused per channel.
    private static func ditherToPalette(_ img: Raster, _ palette: Palette, _ dither: Bool) -> [Int] {
        let w = img.w, h = img.h
        var r = [Double](repeating: 0, count: w * h)
        var g = r, b = r
        for i in 0..<(w * h) {
            let p = img.px[i]
            r[i] = Double((p >> 16) & 0xFF); g[i] = Double((p >> 8) & 0xFF); b[i] = Double(p & 0xFF)
        }
        var out = [Int](repeating: 0, count: w * h)
        for y in 0..<h {
            let ltr = y % 2 == 0
            let xs = ltr ? Array(0..<w) : Array((0..<w).reversed())
            for x in xs {
                let i = y * w + x
                let idx = nearest(palette, r[i], g[i], b[i])
                out[i] = idx
                if !dither { continue }
                let c = palette.colors[idx]
                let er = r[i] - Double(c[0]), eg = g[i] - Double(c[1]), eb = b[i] - Double(c[2])
                let dx = ltr ? 1 : -1
                diffuse(&r, &g, &b, w, h, x + dx, y, er, eg, eb, 7.0 / 16)
                diffuse(&r, &g, &b, w, h, x - dx, y + 1, er, eg, eb, 3.0 / 16)
                diffuse(&r, &g, &b, w, h, x, y + 1, er, eg, eb, 5.0 / 16)
                diffuse(&r, &g, &b, w, h, x + dx, y + 1, er, eg, eb, 1.0 / 16)
            }
        }
        return out
    }

    private static func diffuse(_ r: inout [Double], _ g: inout [Double], _ b: inout [Double],
                                _ w: Int, _ h: Int, _ x: Int, _ y: Int,
                                _ er: Double, _ eg: Double, _ eb: Double, _ f: Double) {
        guard x >= 0, x < w, y < h else { return }
        let i = y * w + x
        r[i] += er * f; g[i] += eg * f; b[i] += eb * f
    }

    private static func nearest(_ palette: Palette, _ r: Double, _ g: Double, _ b: Double) -> Int {
        var best = 0
        var bestD = Double.greatestFiniteMagnitude
        for (i, c) in palette.colors.enumerated() {
            let dr = r - Double(c[0]), dg = g - Double(c[1]), db = b - Double(c[2])
            let d = dr * dr + dg * dg + db * db
            if d < bestD { bestD = d; best = i }
        }
        return best
    }

    /// Minimal ARGB raster ops; bilinear resize is plenty for label sizes.
    struct Raster {
        var px: [UInt32]
        let w: Int
        let h: Int

        func crop(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) -> Raster {
            let nw = x1 - x0, nh = y1 - y0
            var out = [UInt32](repeating: 0, count: nw * nh)
            for i in 0..<(nw * nh) { out[i] = px[(y0 + i / nw) * w + x0 + i % nw] }
            return Raster(px: out, w: nw, h: nh)
        }

        func rotate90() -> Raster {   // counter-clockwise, like PIL rotate(90, expand)
            var out = [UInt32](repeating: 0, count: w * h)
            for i in 0..<(w * h) {
                let x = i % h, y = i / h
                out[i] = px[x * w + (w - 1 - y)]
            }
            return Raster(px: out, w: h, h: w)
        }

        func resize(_ nw: Int, _ nh: Int) -> Raster {
            var out = [UInt32](repeating: 0, count: nw * nh)
            for y in 0..<nh {
                for x in 0..<nw {
                    let sx = (Double(x) + 0.5) * Double(w) / Double(nw) - 0.5
                    let sy = (Double(y) + 0.5) * Double(h) / Double(nh) - 0.5
                    let x0 = min(max(Int(sx), 0), w - 1), y0 = min(max(Int(sy), 0), h - 1)
                    let x1 = min(x0 + 1, w - 1), y1 = min(y0 + 1, h - 1)
                    let fx = min(max(sx - Double(x0), 0), 1), fy = min(max(sy - Double(y0), 0), 1)
                    out[y * nw + x] = Raster.lerp2(px[y0 * w + x0], px[y0 * w + x1],
                                                   px[y1 * w + x0], px[y1 * w + x1], fx, fy)
                }
            }
            return Raster(px: out, w: nw, h: nh)
        }

        mutating func paste(_ src: Raster, _ atX: Int, _ atY: Int) {
            for y in 0..<src.h {
                let ty = atY + y
                guard ty >= 0, ty < h else { continue }
                for x in 0..<src.w {
                    let tx = atX + x
                    if tx >= 0 && tx < w { px[ty * w + tx] = src.px[y * src.w + x] }
                }
            }
        }

        private static func lerp2(_ c00: UInt32, _ c10: UInt32, _ c01: UInt32, _ c11: UInt32,
                                  _ fx: Double, _ fy: Double) -> UInt32 {
            func ch(_ shift: UInt32) -> UInt32 {
                let a = Double((c00 >> shift) & 0xFF) * (1 - fx) + Double((c10 >> shift) & 0xFF) * fx
                let b = Double((c01 >> shift) & 0xFF) * (1 - fx) + Double((c11 >> shift) & 0xFF) * fx
                return UInt32(min(max((a * (1 - fy) + b * fy).rounded(), 0), 255))
            }
            return (0xFF << 24) | (ch(16) << 16) | (ch(8) << 8) | ch(0)
        }
    }
}
