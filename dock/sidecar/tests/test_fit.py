from PIL import Image, ImageDraw
from repaper_dock.sheets.base import SheetModel, PALETTES
from repaper_dock.render import render_for_sheet
from repaper_dock.sheets.mock import MockTransport
from repaper_dock.sheets.base import SheetRef


def test_render_fits_rotates_and_uses_palette(tmp_path):
    src = Image.new("RGB", (300, 800), "white"); d = ImageDraw.Draw(src)
    d.rectangle([20, 20, 280, 120], fill="black"); d.rectangle([20, 600, 280, 780], fill=(230, 20, 20))
    model = SheetModel(400, 300, "BWR")
    page = render_for_sheet(src, model)
    assert page.image.size == (400, 300) and page.image.mode == "P"
    used = set(page.image.getdata()); assert used <= {0, 1, 2}
    assert 2 in used  # red survived quantisation
    res = MockTransport(tmp_path).print(SheetRef("mock", "400x300", {}, "test"), page)
    assert res.ok and list(tmp_path.glob("*.png"))


def test_bw_only_has_two_indices():
    src = Image.new("L", (200, 100), 128)
    page = render_for_sheet(src, SheetModel(296, 128, "BW"))
    assert set(page.image.getdata()) <= {0, 1}
