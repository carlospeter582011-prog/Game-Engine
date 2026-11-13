"""Copy the compiled Android player to a browser-readable template."""
import base64
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
with zipfile.ZipFile(ROOT / "android/app/src/main/assets/nexus-player.apk") as apk:
    files = {name: base64.b64encode(apk.read(name)).decode("ascii") for name in sorted(apk.namelist())}
(ROOT / "assets/web/player-template.json").write_text(json.dumps(files, indent=2) + "\n")
