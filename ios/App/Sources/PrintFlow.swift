import Foundation
import CoreBluetooth
import CoreGraphics
import ImageIO
import RePaperKit

/// The actual printing path — Android's PrintFlow, Swift edition:
/// job file (PDF or image) → ARGB pixels → Render.forSheet → BLE → sheet.
enum PrintFlow {

    @MainActor static func printJob(_ url: URL, sheet: Sheet, narrate: @escaping (String) -> Void = { _ in }) async throws {
        let model = sheet.model
        guard let (px, w, h) = rasterize(url, maxDim: 2 * max(model.width, model.height)) else {
            throw OdError.protocolError("couldn't read \(url.lastPathComponent)")
        }
        let page = Render.forSheet(px, width: w, height: h, model: model)
        try await printPage(page, sheet: sheet, narrate: narrate)
    }

    @MainActor static func printPage(_ page: Page, sheet: Sheet, narrate: @escaping (String) -> Void = { _ in }) async throws {
        narrate("looking for the sheet")
        DiagLog.log("print → \(sheet.id) (\(page.model.width)×\(page.model.height) \(page.model.palette))")
        let peripheral: CoreBluetooth.CBPeripheral
        do { peripheral = try await SheetRadio.shared.find(name: sheet.address) }
        catch { DiagLog.log("print: sheet not found in scan"); throw error }
        narrate("connecting")
        let link = try await SheetRadio.shared.connect(peripheral)
        defer { link.close() }
        do {
            let od = OdDevice(link: link, masterKey: sheet.keyHex.flatMap { Data(hexString: $0) })
            try await od.print(page) { phase in
                DiagLog.log("print: \(phase)")
                Task { @MainActor in narrate(phase) }
            }
            Prefs.bumpPrinted()
            DiagLog.log("print: done")
        } catch {
            DiagLog.log("print FAILED: \(error.localizedDescription)")
            throw error
        }
    }

    // ── rasterizing: PDF page 1 or image file → ARGB pixels ─────────────────

    static func rasterize(_ url: URL, maxDim: Int) -> ([UInt32], Int, Int)? {
        if url.pathExtension.lowercased() == "pdf" { return rasterizePdf(url, maxDim: maxDim) }
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let img = CGImageSourceCreateImageAtIndex(src, 0, [kCGImageSourceShouldCache: false] as CFDictionary)
        else { return nil }
        let scale = min(1.0, Double(maxDim) / Double(max(img.width, img.height)))
        let w = max(1, Int(Double(img.width) * scale)), h = max(1, Int(Double(img.height) * scale))
        guard let ctx = argbContext(w, h) else { return nil }
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
        return (pixels(from: ctx, count: w * h), w, h)
    }

    private static func rasterizePdf(_ url: URL, maxDim: Int) -> ([UInt32], Int, Int)? {
        guard let doc = CGPDFDocument(url as CFURL), let page = doc.page(at: 1) else { return nil }
        let box = page.getBoxRect(.mediaBox)
        let scale = Double(maxDim) / Double(max(box.width, box.height))
        let w = max(1, Int(box.width * scale)), h = max(1, Int(box.height * scale))
        guard let ctx = argbContext(w, h) else { return nil }
        ctx.scaleBy(x: scale, y: scale)
        ctx.translateBy(x: -box.minX, y: -box.minY)
        ctx.drawPDFPage(page)
        return (pixels(from: ctx, count: w * h), w, h)
    }

    /// A white-filled context whose memory reads back directly as ARGB UInt32s.
    private static func argbContext(_ w: Int, _ h: Int) -> CGContext? {
        let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                            space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
                                | CGBitmapInfo.byteOrder32Little.rawValue)
        ctx?.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        ctx?.fill(CGRect(x: 0, y: 0, width: w, height: h))
        return ctx
    }

    private static func pixels(from ctx: CGContext, count: Int) -> [UInt32] {
        guard let data = ctx.data else { return [] }
        let buf = data.bindMemory(to: UInt32.self, capacity: count)
        return [UInt32](UnsafeBufferPointer(start: buf, count: count))
    }

    /// Small first-page preview for job cards.
    static func thumb(_ url: URL, maxDim: Int = 224) -> CGImage? {
        guard let (px, w, h) = rasterize(url, maxDim: maxDim) else { return nil }
        var data = px
        return data.withUnsafeMutableBytes { raw -> CGImage? in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8,
                                      bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
                                          | CGBitmapInfo.byteOrder32Little.rawValue)
            else { return nil }
            return ctx.makeImage()
        }
    }
}
