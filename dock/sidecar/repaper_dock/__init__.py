"""RePaper Dock sidecar.

Pipeline:  printer (PAPPL / ippeveprinter) → `repaper-print` decodes the document into a spool job
           → `repaper-dockd` waits for a tap, renders each page for the tapped sheet, sends it via a SheetTransport.
"""
__version__ = "0.0.9"
