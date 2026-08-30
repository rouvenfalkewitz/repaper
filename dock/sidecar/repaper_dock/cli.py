"""Entry points: `repaper-print` (called by the printer per job) and `repaper-dockd` (the daemon + tools)."""
from __future__ import annotations
import argparse, logging, os, sys
from .config import ensure_home, load_config, SHEETS, HOME


def print_command(argv=None):
    """Called by the printer once per document. ippeveprinter passes the document as a file path in argv[1]
    (PAPPL: stdin); metadata comes from IPP_* environment variables. Decodes it into a spool job. Exit 0 = accepted."""
    ensure_home()
    from .render import decode_document
    from .spool import create_job
    args = sys.argv[1:] if argv is None else argv
    if args and os.path.isfile(args[-1]):
        data = open(args[-1], "rb").read()
    else:
        data = sys.stdin.buffer.read()
    ctype = os.environ.get("CONTENT_TYPE") or os.environ.get("IPP_DOCUMENT_FORMAT_SUPPLIED") or os.environ.get("IPP_DOCUMENT_FORMAT") or ""
    name = os.environ.get("IPP_JOB_NAME") or ""
    user = os.environ.get("IPP_JOB_ORIGINATING_USER_NAME") or os.environ.get("IPP_REQUESTING_USER_NAME") or ""
    try:
        pages = decode_document(data, ctype)
    except Exception as e:
        print(f"ERROR: cannot decode document ({ctype or 'unknown type'}): {e}", file=sys.stderr); return 1
    job = create_job(pages, name, user, source=ctype)
    print(f"INFO: job {job.id} · {job.pages} page(s) · {ctype} · {len(data)} bytes", file=sys.stderr); return 0


def dockd(argv=None):
    p = argparse.ArgumentParser(prog="repaper-dockd", description="RePaper Dock daemon and tools")
    sub = p.add_subparsers(dest="cmd")
    sub.add_parser("run", help="run the Dock (default)")
    sub.add_parser("discover", help="list sheets reachable by every loaded transport")
    a = sub.add_parser("add-sheet", help="register a sheet"); a.add_argument("id")
    a.add_argument("--qr", default=None, help="opendisplay: the sheet's QR / landing URL (https://opendisplay.org/l/?…) — sets transport, address and key")
    a.add_argument("--transport", default=None); a.add_argument("--address", default=None, help="mock: WxH · opendisplay-ble: MAC or OD name")
    a.add_argument("--name", default=None); a.add_argument("--size", default=None, help="WxH in pixels (opendisplay: read from the sheet if omitted)")
    a.add_argument("--palette", default=None, choices=["BW", "BWR", "BWRY"]); a.add_argument("--key", default=None, help="opendisplay: AES-128 key as hex")
    sub.add_parser("sheets", help="list registered sheets"); sub.add_parser("jobs", help="list spool jobs")
    st = sub.add_parser("status", help="battery / temperature / online for a sheet (no connection)"); st.add_argument("sheet")
    t = sub.add_parser("test-page", help="render + print a built-in test page to a sheet (no printer needed)"); t.add_argument("sheet")
    c = sub.add_parser("calibrate", help="print a full-bleed page with numbered ticks; then set-inset with what is hidden"); c.add_argument("sheet")
    i = sub.add_parser("set-inset", help="pixels hidden per edge: left,top,right,bottom (viewed orientation)"); i.add_argument("sheet"); i.add_argument("inset")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    ensure_home()
    from .sheets import SheetRegistry, SheetRef, SheetModel, load_transports
    reg = SheetRegistry(SHEETS); cfg = load_config()
    if args.cmd == "add-sheet":
        transport, address, key = args.transport, args.address, args.key
        if args.qr:
            from .sheets.opendisplay_ble import parse_landing_url
            info = parse_landing_url(args.qr); transport = transport or "opendisplay-ble"; address = address or info["name"]; key = key or info["key"]
            print(f"QR: device {info['name']} · key {'set' if info['key'] else 'none'} · manufacturer {info['manufacturer_id']} · tag_type {info['tag_type']}")
        if not transport or not address: p.error("need --qr, or --transport and --address")
        keys = {"key": key} if key else {}
        ref = SheetRef(transport, address, keys, args.name or (address if transport == "opendisplay-ble" else None))
        if args.size:
            w, h = (int(v) for v in args.size.lower().split("x")); model = SheetModel(w, h, args.palette or "BW")
        elif transport == "opendisplay-ble":
            print("reading size and colours from the sheet over BLE (keep it awake and nearby) …")
            from .sheets.opendisplay_ble import OpenDisplayBLETransport
            model = OpenDisplayBLETransport(None).describe(ref)
            if args.palette: model.palette = args.palette
        else: p.error("--size is required for this transport")
        reg.add(args.id, ref, model)
        print(f"registered {args.id}: {transport} {address} {model.width}×{model.height} {model.palette}"); return 0
    if args.cmd == "sheets":
        for k, e in reg.all().items(): print(f'{k:16} {e["transport"]:16} {e["address"]:20} {e["model"]["width"]}×{e["model"]["height"]} {e["model"]["palette"]}  {e["name"] or ""}')
        return 0
    if args.cmd == "jobs":
        from .spool import list_jobs
        for j in list_jobs(()): print(f"{j.id}  {j.state:10} {j.pages}p  {j.name}")
        return 0
    transports = load_transports(cfg, reg)
    if args.cmd == "discover":
        for tid, tr in transports.items():
            for ref in tr.discover():
                known = reg.find_by_address(tid, ref.address)
                print(f"{tid:16} {ref.address:20} {ref.name or '':24} {'→ ' + known if known else '(not registered)'}")
        return 0
    if args.cmd == "status":
        ref, model = reg.get(args.sheet); tr = transports[ref.transport_id]; st = tr.status(ref)
        v = "?" if st.battery_volts is None else f"{st.battery_volts:.2f} V"; t = "?" if st.temperature_c is None else f"{st.temperature_c:.1f} °C"
        print(f"{args.sheet}: online={st.online} battery={v} temp={t} min_to_print={tr.capabilities().get('min_battery_mv','-')} mV"); return 0
    if args.cmd == "set-inset":
        l, t, r, b = (int(v) for v in args.inset.split(","))
        ref, model = reg.get(args.sheet); model.inset = (l, t, r, b); reg.add(args.sheet, ref, model)
        print(f"{args.sheet}: inset left={l} top={t} right={r} bottom={b} → visible {model.visible}"); return 0
    if args.cmd == "calibrate":
        from .render import render_for_sheet
        from .testpage import calibration_page
        ref, model = reg.get(args.sheet); tr = transports[ref.transport_id]
        model.inset = (0, 0, 0, 0); page = render_for_sheet(calibration_page(model), model, dither=False, auto_rotate=False, trim=False)
        res = tr.print(ref, page); print("ok" if res.ok else "FAILED", res.message); return 0 if res.ok else 1
    if args.cmd == "test-page":
        from .render import render_for_sheet
        from .testpage import test_page
        ref, model = reg.get(args.sheet); tr = transports[ref.transport_id]
        model = tr.describe(ref); page = render_for_sheet(test_page(model), model)
        res = tr.print(ref, page); print("ok" if res.ok else "FAILED", res.message); return 0 if res.ok else 1
    from .dockd import Dock, serve
    dock = Dock(); serve(dock)
    try: dock.run()
    except KeyboardInterrupt: return 0
