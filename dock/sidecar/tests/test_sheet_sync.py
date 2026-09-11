"""Dock-Labels sync: the registry snapshot the Dock publishes, and the NFC write-back it applies."""
import json
from repaper_dock.sheets.base import SheetRegistry, SheetRef, SheetModel


def test_snapshot_shape_and_set_tag(tmp_path):
    reg = SheetRegistry(tmp_path / "sheets.json")
    reg.add(
        "kitchen",
        SheetRef("opendisplay-ble", "A1", {"landing": "https://x/kitchen", "key": "ab"}, "Kitchen"),
        SheetModel(296, 128, "BWR"),
    )

    snap = reg.snapshot()
    assert len(snap) == 1
    s = snap[0]
    assert set(s) >= {"id", "name", "address", "link", "model", "tag_uid", "tag_programmed"}
    assert s["id"] == "kitchen"
    assert s["name"] == "Kitchen"
    assert s["address"] == "A1"
    assert s["link"] == "https://x/kitchen"
    assert json.loads(s["model"])["width"] == 296        # model is a JSON string
    assert s["tag_uid"] is None
    assert s["tag_programmed"] is False

    # NFC write-back: learn a tag, persisted and reflected in the next snapshot
    assert reg.set_tag("kitchen", "0455AA", False) is True
    assert reg.set_tag("does-not-exist", "x", False) is False
    again = reg.snapshot()[0]
    assert again["tag_uid"] == "0455AA"

    # survives a reload (persisted to disk)
    reg2 = SheetRegistry(tmp_path / "sheets.json")
    assert reg2.snapshot()[0]["tag_uid"] == "0455AA"
