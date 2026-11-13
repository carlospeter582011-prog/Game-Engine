"""Stage a standalone HTML game and emit validated Android build settings."""
import argparse
import os
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument("html", type=Path)
parser.add_argument("--icon", type=Path)
parser.add_argument("--package", default=os.getenv("INPUT_PACKAGE", ""))
parser.add_argument("--name", default=os.getenv("INPUT_NAME", ""))
parser.add_argument("--orientation", default=os.getenv("INPUT_ORIENTATION", "auto"))
parser.add_argument("--version", default=os.getenv("INPUT_VERSION", "1.0.0"))
parser.add_argument("--number", default=os.getenv("INPUT_NUMBER", "1"))
args = parser.parse_args()
if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", args.package) or len(args.package) > 120 or args.package == "com.nexusengine.app":
    parser.error("invalid game package ID")
if not 1 <= len(args.name.strip()) <= 60 or re.search(r"[\x00-\x1f<>]", args.name):
    parser.error("invalid game label")
orientations = {"auto": "unspecified", "portrait": "sensorPortrait", "landscape": "sensorLandscape"}
if args.orientation not in orientations:
    parser.error("invalid orientation")
if not re.fullmatch(r"\d{1,6}\.\d{1,6}\.\d{1,6}", args.version) or not re.fullmatch(r"[1-9]\d{0,8}", args.number):
    parser.error("invalid Android version or build number")
game = args.html.read_bytes()
if not game.lstrip().lower().startswith(b"<!doctype html>") or len(game) > 20 * 1024 * 1024:
    parser.error("expected a standalone HTML document up to 20 MB")
target = Path("android/app/src/main/assets/game.html")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(game)
# This build includes only the player's game HTML, so editor assets stay out of the APK.
pubspec = Path("pubspec.yaml")
pubspec.write_text(pubspec.read_text().split("  assets:\n", 1)[0])
settings = {
    "NEXUS_GAME": "1",
    "NEXUS_PACKAGE": args.package,
    "NEXUS_NAME": args.name,
    "NEXUS_ORIENTATION": orientations[args.orientation],
    "NEXUS_VERSION": args.version,
    "NEXUS_NUMBER": args.number,
}
if args.icon and args.icon.exists():
    icon = args.icon.read_bytes()
    if len(icon) > 2 * 1024 * 1024 or not icon.startswith(b"\x89PNG\r\n\x1a\n") or len(icon) < 24 or icon[16:24] != b"\x00\x00\x02\x00" * 2:
        parser.error("expected a 512 × 512 PNG icon under 2 MB")
    drawable = Path("android/app/src/main/res/drawable-nodpi")
    drawable.mkdir(parents=True, exist_ok=True)
    (drawable / "game_icon.png").write_bytes(icon)
    settings["NEXUS_ICON"] = "@drawable/game_icon"
with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
    for key, value in settings.items():
        print(f"{key}={value}", file=output)
