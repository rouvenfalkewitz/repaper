"""The Dock's pages must at least parse: a syntax error renders empty sections with no server-side trace."""
import re, shutil, subprocess
from pathlib import Path
import pytest
UI = Path(__file__).resolve().parents[1] / "repaper_dock" / "ui"

@pytest.mark.skipif(shutil.which("node") is None, reason="node not installed")
@pytest.mark.parametrize("page", ["index.html", "settings.html"])
def test_page_script_parses(tmp_path, page):
    js = re.search(r"<script>(.*?)</script>", (UI / page).read_text(), re.S).group(1)
    f = tmp_path / "ui.js"; f.write_text(js)
    r = subprocess.run(["node", "--check", str(f)], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
