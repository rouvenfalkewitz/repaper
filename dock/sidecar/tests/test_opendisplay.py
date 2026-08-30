from repaper_dock.sheets.opendisplay_ble import parse_landing_url, _is_mac, OpenDisplayBLETransport
from repaper_dock.sheets.base import SheetRef


def test_parse_landing_url():
    info = parse_landing_url("https://opendisplay.org/l/?AACX2b4fpogM3q10pFLXgtzZgtNhAAQ")
    assert info["name"] == "OD97D9BE" and info["device_id"] == "97D9BE"
    assert info["key"] == "1fa6880cdead74a452d782dcd982d361" and info["manufacturer_id"] == 4 and info["tag_type"] == 0


def test_address_forms_and_key():
    assert _is_mac("AA:bb:CC:DD:EE:FF") and not _is_mac("OD97D9BE")
    t = OpenDisplayBLETransport()
    assert t._key(SheetRef("opendisplay-ble", "OD97D9BE", {"key": "1fa6880cdead74a452d782dcd982d361"})) == bytes.fromhex("1fa6880cdead74a452d782dcd982d361")
    assert t._key(SheetRef("opendisplay-ble", "OD97D9BE", {})) is None


def test_discover_without_hardware_does_not_raise():
    assert isinstance(OpenDisplayBLETransport().discover(timeout=0.1), list)
