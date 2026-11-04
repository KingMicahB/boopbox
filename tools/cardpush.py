#!/usr/bin/env python3
"""
cardpush: build a Card Player card on this computer and push it to the phone over adb.

    python tools/cardpush.py push cards/rotemama-medley.json       # queue it: next new card tapped gets it
    python tools/cardpush.py push cards/x.json --card <uid|name>   # put it straight onto an existing card
    python tools/cardpush.py list                                  # cards + waiting pushes on the phone
    python tools/cardpush.py analyze song.mp3 [--transcribe]       # silences / cut points / lyrics
    python tools/cardpush.py icons "ice cream" bus --sheet s.png     # search yotoicons.com

A manifest (see cards/*.json):

    {
      "id": "rotemama-medley",              # stable: pushing the same id again updates the card in place
      "name": "Rotemama songs", "emoji": "🎶", "color": "#FFB74D",
      "cover": "yoto:2696",                  # yoto:<id> | http(s) url | local image path
      "resume": false, "shuffle": false,
      "audiobook": false, "speed": 1,        # optional: audiobook = cover on every part + resume on; speed 0.5-2
      "source": "C:/path/medley.mp3",       # default file for tracks that only give start/end
      "tracks": [
        {"title": "Ice cream", "start": 0, "end": 59.36, "icon": "yoto:2696"},
        {"title": "Another", "file": "C:/path/other.m4a", "icon": "pics/bus.png"}
      ]
    }

Needs: ffmpeg on PATH, adb (Android SDK platform-tools), a debug build of the app (uses run-as),
pip packages pillow + mutagen (+ faster-whisper for --transcribe).
"""
import argparse
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

# applicationId of the debug build on the phone (BOOPBOX_PKG overrides it, e.g. com.boopbox.full for the
# archived sideload build). Kotlin classes still live in com.cardplayer.
PKG = os.environ.get("BOOPBOX_PKG", "com.boopboxapp")
STAGE = "/data/local/tmp/cardpush"
UA = {"User-Agent": "Mozilla/5.0 CardPlayer-cardpush/1.0"}
WHISPER_MODEL = "ivrit-ai/whisper-large-v3-turbo-ct2"  # Hebrew-tuned; fine for English too

# Git Bash rewrites "/sdcard/..." style args into Windows paths; adb must see them untouched.
os.environ["MSYS_NO_PATHCONV"] = "1"
for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding="utf-8")
    except Exception:
        pass


# ------------------------------------------------------------------ adb

def adb_path():
    found = shutil.which("adb")
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    exe = os.path.join(sdk, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
    if os.path.exists(exe):
        return exe
    sys.exit("adb not found (install Android SDK platform-tools or put adb on PATH)")


def adb(*args, check=True, capture=True):
    r = subprocess.run([adb_path(), *args], capture_output=capture, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed:\n{r.stdout}{r.stderr}")
    return r.stdout if capture else ""


def run_as(script):
    """Runs a shell script as the app (debug builds only), inside its private files dir's parent."""
    return adb("shell", f"run-as {PKG} sh -c '{script}'")


# ------------------------------------------------------------------ icons

def fetch(url, timeout=20):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout).read()


ICON_RX = re.compile(r"populate_icon_modal\('([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)'\)")


def yoto_search(term, limit=12):
    html = fetch("https://yotoicons.com/icons?tag=" + urllib.parse.quote(term.lower()) + "&type=singles").decode("utf-8", "ignore")
    out, seen = [], set()
    for m in ICON_RX.finditer(html):
        icon_id, category, tag1, tag2, author, downloads = m.groups()
        if icon_id in seen:
            continue
        seen.add(icon_id)
        out.append({"id": icon_id, "tags": ", ".join(t for t in (tag1, tag2) if t.strip()), "author": author,
                    "downloads": int(downloads or 0), "url": f"https://yotoicons.com/static/uploads/{icon_id}.png"})
        if len(out) >= limit:
            break
    return out


def load_icon(spec, base_dir, native=False):
    """yoto:<id> | url | path -> PNG bytes on white.

    native=False: upscaled with hard edges (for the art embedded in the MP3, which other players show).
    native=True: small pixel icons kept at their own size; the app draws tiny art pixel-for-pixel,
    whereas shrinking a 512 px upscale back down blurs it.
    """
    from PIL import Image
    if spec.startswith("yoto:"):
        data = fetch(f"https://yotoicons.com/static/uploads/{spec[5:]}.png")
    elif re.match(r"https?://", spec):
        data = fetch(spec)
    else:
        with open(os.path.join(base_dir, spec), "rb") as f:
            data = f.read()
    im = Image.open(io.BytesIO(data)).convert("RGBA")
    bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
    bg.alpha_composite(im)
    im = bg.convert("RGB")
    if native and max(im.size) <= 64:
        pass
    elif max(im.size) < 512:
        scale = max(1, 512 // max(im.size))
        im = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
    buf = io.BytesIO()
    im.save(buf, "PNG")
    return buf.getvalue()


# ------------------------------------------------------------------ audio

def decode_mono16k(path):
    import numpy as np
    raw = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", path, "-ac", "1", "-ar", "16000", "-f", "s16le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.int16).astype(np.float32) / 32768.0


def encode_track(src, dst, start=None, end=None):
    cmd = ["ffmpeg", "-nostdin", "-v", "error", "-y"]
    if start is not None:
        cmd += ["-ss", str(start)]
    if end is not None:
        cmd += ["-to", str(end)]
    cmd += ["-i", src, "-map", "0:a:0"]
    if start is not None and end is not None:
        d = end - start
        cmd += ["-af", f"afade=t=in:d=0.05,afade=t=out:st={max(0.0, d - 0.4):.2f}:d=0.4"]
    cmd += ["-c:a", "libmp3lame", "-b:a", "192k", "-map_metadata", "-1", dst]
    subprocess.run(cmd, check=True)


def tag_track(path, title, artist, album, n, total, art_png):
    from mutagen.id3 import ID3, TIT2, TPE1, TALB, TRCK, APIC
    t = ID3()
    t.add(TIT2(encoding=3, text=title))
    if artist:
        t.add(TPE1(encoding=3, text=artist))
    if album:
        t.add(TALB(encoding=3, text=album))
    t.add(TRCK(encoding=3, text=f"{n}/{total}"))
    if art_png:
        t.add(APIC(encoding=3, mime="image/png", type=3, desc="Cover", data=art_png))
    t.save(path, v2_version=3)


def parse_color(c):
    if isinstance(c, int):
        return c
    c = (c or "#FFB74D").lstrip("#")
    if len(c) == 6:
        c = "FF" + c
    return int(c, 16)


# ------------------------------------------------------------------ commands

def cmd_push(a):
    manifest_path = os.path.abspath(a.manifest)
    base = os.path.dirname(manifest_path)
    with open(manifest_path, encoding="utf-8") as f:
        m = json.load(f)
    card_id = m["id"]
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]*", card_id):
        sys.exit("manifest id must be lowercase letters, digits, . _ -")
    podcast = m.get("podcast")  # RSS feed url: a podcast card instead of a playlist
    tracks = [] if podcast else m["tracks"]
    feed = podcast_info(podcast) if podcast else {}
    target_uid = resolve_target(a.card) if a.card else None

    work = tempfile.mkdtemp(prefix="cardpush_")
    lib = os.path.join(work, card_id)
    os.makedirs(os.path.join(lib, "art"))
    playlist, track_art = [], {}
    try:
        for i, t in enumerate(tracks, 1):
            src = t.get("file") or m.get("source")
            if not src:
                sys.exit(f"track {i} has no file and the manifest has no source")
            src = os.path.join(base, src)
            name = f"{i:02d}.mp3"
            print(f"  {i:02d} {t['title']}")
            encode_track(src, os.path.join(lib, name), t.get("start"), t.get("end"))
            art = load_icon(t["icon"], base) if t.get("icon") else None
            art_small = load_icon(t["icon"], base, native=True) if t.get("icon") else None
            tag_track(os.path.join(lib, name), t["title"], m.get("artist"), m.get("name"), i, len(tracks), art)
            playlist.append({"uri": f"lib:{card_id}/{name}", "title": t["title"]})
            if art:
                with open(os.path.join(lib, "art", f"{i:02d}.png"), "wb") as f:
                    f.write(art_small)
                track_art[f"lib:{card_id}/{name}"] = f"lib:{card_id}/art/{i:02d}.png"

        cover = m.get("cover") or (tracks[0].get("icon") if tracks else None) or feed.get("image")
        if cover:
            with open(os.path.join(lib, "cover.png"), "wb") as f:
                f.write(load_icon(cover, base, native=True))

        uses_yoto = any(str(t.get("icon", "")).startswith("yoto:") for t in tracks) or str(cover or "").startswith("yoto:")
        card = {
            "uid": f"pending:{card_id}",
            "name": m.get("name") or feed.get("title") or card_id,
            "emoji": m.get("emoji", "🎵"),
            "color": parse_color(m.get("color")),
            "sourceType": "PODCAST" if podcast else "PLAYLIST",
            # Podcast: the feed. The app plays the newest episode and queues the rest.
            "uri": podcast or f"lib:{card_id}/",
            "podcastMode": "LATEST",
            "artworkUrl": f"lib:{card_id}/cover.png" if cover else None,
            "subtitle": m.get("artist") or feed.get("author"),
            # Podcasts and audiobooks carry on where they stopped unless told otherwise.
            "resume": bool(m.get("resume", bool(podcast) or bool(m.get("audiobook")))),
            "shuffle": bool(m.get("shuffle", False)),
            # An audiobook keeps its cover on every part; "speed" is the playback speed (1 = normal).
            "audiobook": bool(m.get("audiobook", False)),
            "speed": float(m.get("speed", 1)),
            "playlist": playlist,
            "trackArt": track_art,
            "artworkCredit": m.get("credit") or ("icons from yotoicons.com" if uses_yoto else None),
        }
        inbox = {"id": card_id, "card": card, "targetUid": target_uid}
        inbox_file = os.path.join(work, f"{card_id}.json")
        with open(inbox_file, "w", encoding="utf-8") as f:
            json.dump(inbox, f, ensure_ascii=False)

        print("  pushing to phone...")
        adb("shell", f"rm -rf {STAGE} && mkdir -p {STAGE}")
        adb("push", lib, f"{STAGE}/")
        adb("push", inbox_file, f"{STAGE}/{card_id}.json")
        adb("shell", f"chmod -R a+rX {STAGE}")
        # Library first, inbox note last, so the app never imports a half-copied card.
        run_as(f"mkdir -p files/library files/inbox && rm -rf files/library/{card_id} && "
               f"cp -r {STAGE}/{card_id} files/library/ && cp {STAGE}/{card_id}.json files/inbox/")
        adb("shell", f"rm -rf {STAGE}")
        adb("shell", f"am broadcast -n {PKG}/com.cardplayer.data.InboxReceiver", check=False)
    finally:
        shutil.rmtree(work, ignore_errors=True)

    name = card["name"]
    if target_uid:
        print(f"Done: \"{name}\" is on card {target_uid}.")
    else:
        print(f"Done: \"{name}\" pushed. If it was already on a card, that card is updated; "
              f"otherwise tap a new card on the phone to get it.")


def podcast_info(url):
    """Title, author, artwork and episode count from an RSS feed (sanity check before pushing)."""
    xml = fetch(url, timeout=30).decode("utf-8", "ignore")
    def first(rx):
        mm = re.search(rx, xml, re.S)
        return re.sub(r"<!\[CDATA\[|\]\]>", "", mm.group(1)).strip() if mm else None
    info = {
        "title": first(r"<channel>.*?<title>(.*?)</title>"),
        "author": first(r"<itunes:author>(.*?)</itunes:author>"),
        "image": first(r'<itunes:image href="([^"]+)"') or first(r"<image>.*?<url>(.*?)</url>"),
        "episodes": xml.count("<item>") + xml.count("<item "),
    }
    if not info["episodes"]:
        sys.exit(f"{url} doesn't look like a podcast feed (no <item> episodes)")
    print(f"  podcast: {info['title']} — {info['episodes']} episodes")
    return info


def phone_json(name, default):
    out = adb("shell", f"run-as {PKG} cat files/{name}", check=False)
    try:
        return json.loads(out)
    except Exception:
        return default


def resolve_target(card):
    cards = phone_json("cards.json", [])
    for c in cards:
        if c["uid"] == card or c.get("name", "").strip().lower() == card.strip().lower():
            return c["uid"]
    sys.exit(f"no card with uid or name '{card}' on the phone (see: cardpush.py list)")


def cmd_list(a):
    cards = phone_json("cards.json", [])
    pending = phone_json("pending.json", [])
    inbox = adb("shell", f"run-as {PKG} ls files/inbox", check=False).split()
    print(f"Cards on the phone ({len(cards)}):")
    for c in cards:
        n = len(c.get("playlist") or []) or 1
        lib = "  [pushed]" if "/library/" in (c.get("uri") or "") + (c.get("artworkUrl") or "") else ""
        print(f"  {c['uid']:<20} {c.get('sourceType', ''):<14} {n:>3} track(s)  {c.get('name') or c.get('subtitle') or ''}{lib}")
    print(f"Waiting for a tap ({len(pending)}):")
    for c in pending:
        what = "podcast" if c.get("sourceType") == "PODCAST" else f"{len(c.get('playlist') or [])} tracks"
        print(f"  {c.get('name')}  ({what})")
    if inbox:
        print(f"Not imported yet (open the app): {' '.join(inbox)}")


def cmd_icons(a):
    rows = []
    for term in a.terms:
        icons = yoto_search(term, a.limit)
        rows.append((term, icons))
        print(f"{term}:")
        for ic in icons:
            print(f"  yoto:{ic['id']:<7} {ic['downloads']:>6} dl  by {ic['author']:<18} {ic['tags']}")
    if a.sheet:
        # One row per term, icons left to right in the order printed, so the picture can be eyeballed.
        from PIL import Image, ImageDraw
        size, label = 96, 160
        cols = max((len(icons) for _, icons in rows), default=1) or 1
        sheet = Image.new("RGB", (label + cols * size, len(rows) * (size + 4)), "white")
        draw = ImageDraw.Draw(sheet)
        for r, (term, icons) in enumerate(rows):
            y = r * (size + 4)
            draw.text((4, y + size // 2 - 6), term[:24], fill="black")
            for c, ic in enumerate(icons):
                im = Image.open(io.BytesIO(fetch(ic["url"]))).convert("RGBA").resize((size, size), Image.NEAREST)
                bg = Image.new("RGBA", im.size, "white")
                bg.alpha_composite(im)
                sheet.paste(bg.convert("RGB"), (label + c * size, y))
                draw.text((label + c * size + 2, y + size - 12), ic["id"], fill="red")
        sheet.save(a.sheet)
        print(f"sheet: {a.sheet}")


def cmd_analyze(a):
    import numpy as np
    audio = decode_mono16k(a.audio)
    dur = len(audio) / 16000
    hop = 160  # 10 ms
    energy = np.sqrt(np.convolve(audio ** 2, np.ones(hop) / hop, "same")[::hop])
    smooth = np.convolve(energy, np.ones(30) / 30, "same")
    db = 20 * np.log10(smooth + 1e-9)
    quiet = db < a.threshold
    gaps, i = [], 0
    while i < len(quiet):
        if quiet[i]:
            j = i
            while j < len(quiet) and quiet[j]:
                j += 1
            if (j - i) / 100 >= a.min_silence:
                gaps.append((i / 100, j / 100))
            i = j
        else:
            i += 1
    result = {"duration": round(dur, 2), "silences": [{"start": s, "end": e, "mid": round((s + e) / 2, 2)} for s, e in gaps]}
    print(f"duration {dur:.1f}s; silences < {a.threshold} dB for >= {a.min_silence}s:")
    for s, e in gaps:
        print(f"  {s:8.2f} - {e:8.2f}  ({e - s:.1f}s)")
    if a.transcribe:
        from faster_whisper import WhisperModel
        from huggingface_hub import snapshot_download
        path = snapshot_download(WHISPER_MODEL, local_dir=os.path.expanduser("~/.cache/whisper-he-turbo"))
        model = WhisperModel(path, device="cpu", compute_type="int8")
        segs, _ = model.transcribe(audio, language=a.language, beam_size=1)
        result["transcript"] = []
        for s in segs:
            print(f"  {s.start:8.1f} {s.end:8.1f}  {s.text.strip()[:120]}", flush=True)
            result["transcript"].append({"start": round(s.start, 2), "end": round(s.end, 2), "text": s.text.strip()})
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=1)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("push", help="build a manifest and push it to the phone")
    s.add_argument("manifest")
    s.add_argument("--card", help="uid or name of an existing card to put it on (default: next new card tapped)")
    s.set_defaults(fn=cmd_push)
    s = sub.add_parser("list", help="show cards and waiting pushes on the phone")
    s.set_defaults(fn=cmd_list)
    s = sub.add_parser("icons", help="search yotoicons.com")
    s.add_argument("terms", nargs="+", help="one or more search words")
    s.add_argument("--limit", type=int, default=12)
    s.add_argument("--sheet", help="also save a contact sheet PNG of the results here")
    s.set_defaults(fn=cmd_icons)
    s = sub.add_parser("analyze", help="find silences (and optionally transcribe) to plan cut points")
    s.add_argument("audio")
    s.add_argument("--threshold", type=float, default=-45.0, help="dB below which counts as silence")
    s.add_argument("--min-silence", type=float, default=0.7)
    s.add_argument("--transcribe", action="store_true")
    s.add_argument("--language", default="he")
    s.add_argument("--out", help="write JSON here")
    s.set_defaults(fn=cmd_analyze)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
