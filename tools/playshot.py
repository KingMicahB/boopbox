"""Grabs the phone's screen over adb and saves it as a Google Play phone screenshot.

    python tools/playshot.py 01-player

Play wants 16:9 or 9:16; phones are taller (e.g. 1220x2712), so the capture is centred on a 9:16 canvas in the
BoopBox navy instead of being cropped. Saved to branding/play/screenshots/<name>.png (PNG, well under 8 MB).
"""
import io
import os
import subprocess
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NAVY = (4, 22, 78)


def adb_path():
    for base in (os.environ.get("ANDROID_HOME"), os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")):
        if base and os.path.isfile(os.path.join(base, "platform-tools", "adb.exe")):
            return os.path.join(base, "platform-tools", "adb.exe")
    return "adb"


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    raw = subprocess.run([adb_path(), "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
    shot = Image.open(io.BytesIO(raw)).convert("RGB")
    w, h = shot.size
    # 9:16 canvas as tall as the capture (sides stay within Play's 320..3840 px), capture centred.
    ch = min(h, 3840)
    if ch != h:
        shot = shot.resize((round(w * ch / h), ch), Image.LANCZOS)
        w, h = shot.size
    cw = round(ch * 9 / 16)
    if cw < w:  # wider than 9:16: fit the width instead
        cw, ch = w, round(w * 16 / 9)
    canvas = Image.new("RGB", (cw, ch), NAVY)
    canvas.paste(shot, ((cw - w) // 2, (ch - h) // 2))
    out_dir = os.path.join(ROOT, "branding", "play", "screenshots")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, sys.argv[1] + ".png")
    canvas.save(out, optimize=True)
    print(out, canvas.size, f"{os.path.getsize(out) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
