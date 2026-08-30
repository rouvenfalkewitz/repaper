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
    a = sub.add_parser("add-sheet", help="register a sheet"); a.add_argument("id"); a.add_argument("--transport", required=True)
    a.add_argument("--address", required=True, help="mock: WxH · opendisplay-ble: MAC"); a.add_argument("--name", default=None)
    a.add_argument("--size", required=True, help="WxH in pixels"); a.add_argument("--palette", default="BW", choices=["BW", "BWR", "BWRY"])
    a.add_argument("--key", default=None, help="opendisplay: AES-128 key as hex (from the sheet's QR page)")
    sub.add_parser("sheets", help="list registered sheets"); sub.add_parser("jobs", help="list spool jobs")
    t = sub.add_parser("test-page", help="render + print a built-in test page to a sheet (no printer needed)"); t.add_argument("sheet")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    ensure_home()
    from .sheets import SheetRegistry, SheetRef, SheetModel, load_transports
    reg = SheetRegistry(SHEETS); cfg = load_config()
    if args.cmd == "add-sheet":
        w, h = (int(v) for v in args.size.lower().split("x"))
        keys = {"key": args.key} if args.key else {}
        reg.add(args.id, SheetRef(args.transport, args.address, keys, args.name), SheetModel(w, h, args.palette))
        print(f"registered {args.id}: {args.transport} {args.address} {w}×{h} {args.palette}"); return 0
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
