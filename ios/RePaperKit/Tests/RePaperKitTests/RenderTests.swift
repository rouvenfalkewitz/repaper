import XCTest
@testable import RePaperKit

/// The Swift twin of go/core's RenderTest — same scenarios, same expectations.
final class RenderTests: XCTestCase {
    let W: UInt32 = 0xFFFFFFFF
    let B: UInt32 = 0xFF000000
    let R: UInt32 = 0xFFFF0000

    func testSolidColorsMapExactly() {
        let model = SheetModel(width: 8, height: 8, palette: "BWR")
        let page = Render.forSheet([UInt32](repeating: R, count: 64), width: 8, height: 8, model: model)
        XCTAssertGreaterThan(page.indexes.filter { $0 == 2 }.count, 40)
        XCTAssertTrue(page.indexes.allSatisfy { (0...2).contains($0) })
    }

    func testWhiteSourceStaysWhite() {
        let page = Render.forSheet([UInt32](repeating: W, count: 64), width: 8, height: 8,
                                   model: SheetModel(width: 8, height: 8, palette: "BW"))
        XCTAssertTrue(page.indexes.allSatisfy { $0 == 0 })
    }

    func testAutoRotatesLandscapeContentOntoPortraitSheet() {
        // 20×10 black bar (landscape) onto a 10×20 sheet: content rotates to fill it
        let page = Render.forSheet([UInt32](repeating: B, count: 200), width: 20, height: 10,
                                   model: SheetModel(width: 10, height: 20, palette: "BW"))
        XCTAssertEqual(200, page.indexes.count)
        XCTAssertGreaterThan(page.indexes.filter { $0 == 1 }.count, 150)
    }

    func testInsetKeepsBezelWhite() {
        let model = SheetModel(width: 16, height: 16, palette: "BW", inset: [0, 0, 0, 6])
        let page = Render.forSheet([UInt32](repeating: B, count: 256), width: 16, height: 16, model: model)
        // bottom 6 rows are hidden under the bezel: must stay white
        for y in 10..<16 {
            for x in 0..<16 {
                XCTAssertEqual(0, page.indexes[y * 16 + x], "pixel \(x),\(y) should be white bezel")
            }
        }
        XCTAssertTrue((0..<10).allSatisfy { y in (0..<16).contains { x in page.indexes[y * 16 + x] == 1 } })
    }

    func testDitherProducesOnlyPaletteIndexes() {
        // a gradient exercises error diffusion across the full range
        let px = (0..<(32 * 32)).map { i -> UInt32 in
            let v = UInt32((i % 32) * 8)
            return (0xFF << 24) | (v << 16) | (v << 8) | v
        }
        let page = Render.forSheet(px, width: 32, height: 32, model: SheetModel(width: 32, height: 32, palette: "BWRY"))
        XCTAssertTrue(page.indexes.allSatisfy { (0...3).contains($0) })
        XCTAssertGreaterThanOrEqual(Set(page.indexes).count, 2)   // gradient must dither, not clip
    }
}
