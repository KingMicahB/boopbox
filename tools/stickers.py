"""Render a print-ready sticker PNG for each card in cards/.

Size is a Yoto card face: 54 x 85.6 mm portrait at 300 dpi = 638 x 1011 px
(Yoto's own card art ships at exactly this size).

Layout, top to bottom:
  art     Yoto card art, else album/source art, else pixel art from the card's yotoicons
  panel   track list with each track's pixel icon (left out for single-item cards)
  footer  navy BoopBox bar: logo bottom-left on every card, item count + length on the right

    python -P tools/stickers.py            # all cards -> stickers/<id>.png
    python -P tools/stickers.py moana-music
"""
import glob, json, os, re, shutil, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from bidi import get_display

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "stickers", "art")
OUT = os.path.join(ROOT, "stickers")
W, H = 638, 1011
FOOT = 64
NAVY = (11, 22, 71)
FONT = "C:/Windows/Fonts/Rubik-Bold.ttf"
FONT_REG = "C:/Windows/Fonts/Rubik-Regular.ttf"

# Where each card's art came from. kind: yoto = official Yoto card art (already card-shaped),
# album = square source art, pixel = built from the card's own yotoicons. focus = vertical crop centre.
ART_SOURCES = {
    "moana-music": {"file": "yoto-moana.png", "kind": "yoto", "focus": 0.0},
    "kpop-demon-hunters": {"file": "yoto-kpdh.png", "kind": "yoto", "focus": 0.03},
    "aladdin-storyteller": {"file": "aladdin-storyteller.jpg", "kind": "album", "focus": 0.12},
    "yoto-daily": {"file": "yoto-daily.png", "kind": "album", "focus": 0.5},
    # Rotemama's own YouTube thumbnails (youtube.com/@rotemama4kids): band = thumbnail strip over pixel art
    "rotemama-animals": {"file": "rotemama/rotemama-animals.jpg", "kind": "band"},
    "rotemama-autumn": {"file": "rotemama/rotemama-autumn.jpg", "kind": "band"},
    "rotemama-bloop-bloop": {"file": "rotemama/rotemama-bloop-bloop.jpg", "kind": "band"},
    "rotemama-counting": {"file": "rotemama/rotemama-counting.jpg", "kind": "band"},
    "rotemama-daily": {"file": "rotemama/rotemama-daily.jpg", "kind": "band"},
    "rotemama-me": {"file": "rotemama/rotemama-me.jpg", "kind": "band"},
    "rotemama-medley": {"file": "rotemama/rotemama-medley.jpg", "kind": "band"},
    "rotemama-vehicles": {"file": "rotemama/rotemama-vehicles.jpg", "kind": "band"},
}
# Titles too long for a sticker row, or in a script Rubik can't draw.
SHORT_TITLES = {
    "TAKEDOWN (JEONGYEON, JIHYO, CHAEYOUNG)": "TAKEDOWN (TWICE)",
    "사랑인가 봐 Love, Maybe": "Love, Maybe",
    "오솔길 Path": "Path",
    "I Am Moana (Song of the Ancestors)": "I Am Moana",
    "How Far I'll Go (Alessia Cara)": "How Far I'll Go (Alessia)",
}
SUBTITLES = {"yoto-daily": "A new show every day"}
# Cards that only live on the phone (video cards), with art from the app's image cache.
EXTRA_CARDS = [
    {"id": "moana-movie", "name": "Moana", "color": "#26A69A", "movie": True},
    {"id": "luca-movie", "name": "Luca", "color": "#1E88E5", "movie": True},
]
ART_SOURCES.update({
    "moana-movie": {"file": "movie/moana.png", "kind": "movie"},
    "luca-movie": {"file": "movie/luca.png", "kind": "movie"},
})
SKIP = {"yoto-daily.inbox"}

HEB = re.compile(r"[\u0590-\u05FF]")


def font(size, bold=True):
    return ImageFont.truetype(FONT if bold else FONT_REG, size)


def rgb(hex_color):
    h = hex_color.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def shown(text):
    return get_display(text) if HEB.search(text) else text


def icon(spec, size):
    """A yotoicons pixel icon, scaled with hard pixel edges."""
    if not spec or not spec.startswith("yoto:"):
        return None
    path = os.path.join(ART, "icons", spec[5:] + ".png")
    if not os.path.exists(path):
        return None
    im = Image.open(path).convert("RGBA")
    if im.width != 16 and im.width % 16 == 0:
        im = im.resize((16, 16), Image.NEAREST)
    return im.resize((size, size), Image.NEAREST)


def cover_crop(im, w, h, focus):
    scale = max(w / im.width, h / im.height)
    im = im.resize((round(im.width * scale), round(im.height * scale)), Image.LANCZOS)
    x = (im.width - w) // 2
    y = round((im.height - h) * focus)
    return im.crop((x, y, x + w, y + h))


def band_art(card, w, full_h, h, thumb):
    """Video thumbnail across the top, the card's pixel checker under it with a row of its track icons.
    Returns the art and the y the card title goes at."""
    im = pixel_art(card, w, full_h, h, scatter=False)
    band = cover_crop(thumb, w, round(w * 9 / 16), 0.5)
    im.alpha_composite(band, (0, 0))
    ImageDraw.Draw(im).rectangle((0, band.height, w, band.height + 5), fill=NAVY)
    title_y = band.height + round((h - band.height) * 0.36)
    icons = [t["icon"] for t in card.get("tracks", []) if t.get("icon")][:5]
    size, gap = (64, 22) if len(icons) > 1 else (224, 0)
    x = (w - (len(icons) * size + (len(icons) - 1) * gap)) // 2
    for spec in icons:
        ic = icon(spec, size)
        if ic:
            im.alpha_composite(ic, (x, title_y + 66))
        x += size + gap
    return im, title_y


def movie_art(still, w, h):
    """Wide movie still across the middle, over a blurred, darkened, enlarged copy of itself."""
    bg = cover_crop(still, w, h, 0.5).filter(ImageFilter.GaussianBlur(28))
    bg.alpha_composite(Image.new("RGBA", bg.size, NAVY + (110,)))
    fg = still.resize((w, round(still.height * w / still.width)), Image.LANCZOS)
    y = round(h * 0.17)
    shadow = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rectangle((0, y + 10, w, y + fg.height + 18), fill=(0, 0, 0, 120))
    bg.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(14)))
    bg.alpha_composite(fg, (0, y))
    return bg


def pixel_art(card, w, full_h, h, scatter=True):
    """Fallback cover: the card's cover icon big on its colour, track icons scattered round it.
    The checker fills full_h (it runs on behind the list); everything else is laid out in the top h."""
    base = rgb(card.get("color", "#888888"))
    im = Image.new("RGBA", (w, full_h), base + (255,))
    d = ImageDraw.Draw(im)
    cell = 32  # soft two-tone pixel checker
    light = mix(base, (255, 255, 255), 0.12)
    for yy in range(0, full_h, cell):
        for xx in range(0, w, cell):
            if (xx // cell + yy // cell) % 2:
                d.rectangle((xx, yy, xx + cell - 1, yy + cell - 1), fill=light)
    if not scatter:
        return im
    tracks = card.get("tracks", [])
    others = [t["icon"] for t in tracks if t.get("icon") and t["icon"] != card.get("cover")]
    spots = [(0.12, 0.11), (0.88, 0.11), (0.11, 0.47), (0.89, 0.47), (0.11, 0.93), (0.89, 0.93)]
    for spec, (fx, fy) in zip(others, spots):
        ic = icon(spec, 96)
        if ic:
            im.alpha_composite(ic, (round(fx * w - 48), round(fy * h - 48)))
    big = icon(card.get("cover") or (tracks[0]["icon"] if tracks else None), 288)
    if big:
        shadow = Image.new("RGBA", big.size, (0, 0, 0, 0))
        shadow.putalpha(big.getchannel("A").point(lambda a: 90 if a else 0))
        im.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(6)), ((w - 288) // 2 + 8, round(h * 0.37) - 144 + 10))
        im.alpha_composite(big, ((w - 288) // 2, round(h * 0.37) - 144))
    return im


def outlined(d, xy, text, f, fill, stroke, anchor):
    d.text(xy, text, font=f, fill=fill, stroke_width=max(3, f.size // 9), stroke_fill=stroke, anchor=anchor)


def fit(d, text, max_w, size, min_size, bold=True):
    """Largest font from size down to min_size that fits; ellipsis if even min_size doesn't."""
    for s in range(size, min_size - 1, -1):
        f = font(s, bold)
        if d.textlength(text, font=f) <= max_w:
            return text, f
    f = font(min_size, bold)
    while text and d.textlength(text + "…", font=f) > max_w:
        text = text[:-1]
    return text.rstrip() + "…", f


def probe_seconds(path):
    ffprobe = shutil.which("ffprobe")
    if not (ffprobe and path and os.path.exists(path)):
        return None
    out = subprocess.run([ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                         capture_output=True, text=True).stdout.strip()
    try:
        return float(out)
    except ValueError:
        return None


def length_minutes(card):
    """Total running time, or None when a track's length can't be worked out."""
    total = 0
    for t in card.get("tracks", []):
        end = t.get("end")
        if end is None:
            end = probe_seconds(t.get("file") or card.get("source"))
        if end is None:
            return None
        total += end - (t.get("start") or 0)
    return max(1, round(total / 60))


def footer_label(card, hebrew):
    tracks = card.get("tracks", [])
    if card.get("podcast"):
        return "פודקאסט" if hebrew else "Podcast"
    if card.get("movie"):
        return "Movie"
    n, mins = len(tracks), length_minutes(card)
    story = card.get("resume") and not card.get("podcast")
    if hebrew:
        label = f"{n} " + ("שיר" if n == 1 else "שירים")
        return label + (f" · {mins} דק׳" if mins else "")
    label = f"{n} " + ("chapter" if story else "song") + ("" if n == 1 else "s")
    return label + (f" · {mins} min" if mins else "")


_logo = None


def logo(size):
    """The BoopBox app icon with its white surround cut away (rounded navy square only)."""
    global _logo
    if _logo is None:
        im = Image.open(os.path.join(ROOT, "branding", "boopbox-logo.png")).convert("RGBA")
        dark = im.convert("L").point(lambda v: 255 if v < 200 else 0)
        box = dark.getbbox()
        im = im.crop(box)
        mask = Image.new("L", im.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.width - 1, im.height - 1), radius=round(im.width * 0.22), fill=255)
        im.putalpha(mask)
        _logo = im
    return _logo.resize((size, size), Image.LANCZOS)


def render(card):
    cid = card["id"]
    tracks = card.get("tracks", [])
    color = rgb(card.get("color", "#888888"))
    hebrew = bool(HEB.search(card.get("name", "") + "".join(t.get("title", "") for t in tracks)))
    src = ART_SOURCES.get(cid)
    listed = len(tracks) > 1

    canvas = Image.new("RGBA", (W, H), (255, 255, 255, 255))
    d = ImageDraw.Draw(canvas)

    # ---- track list geometry decides how much room the art gets
    pad, gap = 26, 18
    cols = 1 if len(tracks) <= 7 else 2
    rows = -(-len(tracks) // cols) if listed else 0
    row_h = 50 if cols == 1 else 44 if rows <= 7 else 37
    title_block = 0 if (src and listed) else 0
    list_h = (rows * row_h + 2 * pad) if listed else 0
    art_h = H - FOOT - list_h - title_block
    if not listed:
        art_h = W if (src and src["kind"] == "album") else H - FOOT

    # ---- art: runs on behind the see-through list panel, down to the footer
    bleed_h = H - FOOT if listed else art_h
    ty = round(art_h * 0.74)
    if src and src["kind"] == "band":
        art, ty = band_art(card, W, bleed_h, art_h, Image.open(os.path.join(ART, src["file"])).convert("RGBA"))
    elif src and src["kind"] == "movie":
        art = movie_art(Image.open(os.path.join(ART, src["file"])).convert("RGBA"), W, bleed_h)
    elif src:
        art = Image.open(os.path.join(ART, src["file"])).convert("RGBA")
        art = cover_crop(art, W, bleed_h, src["focus"])
    else:
        art = pixel_art(card, W, bleed_h, art_h)
    canvas.alpha_composite(art, (0, 0))

    # Pixel-art covers carry no title of their own, so draw it on the art.
    if not src or src["kind"] == "band":
        name = shown(card.get("name", ""))
        text, f = fit(d, name, W - 60, 64, 36)
        outlined(d, (W // 2, ty), text, f, (255, 255, 255), NAVY, "mm")
        artist = card.get("artist")
        if artist and artist not in card.get("name", "") and not src:
            outlined(d, (W // 2, ty + f.size * 0.5 + 34), shown(artist), font(34), mix(color, (255, 255, 255), 0.75), NAVY, "mm")
    elif src["kind"] == "movie":
        text, f = fit(d, card.get("name", ""), W - 60, 120, 60)
        outlined(d, (W // 2, round((H - FOOT) * 0.76)), text, f, (255, 255, 255), NAVY, "mm")
    elif not listed and art_h < H - FOOT:
        d.rectangle((0, art_h, W, H - FOOT), fill=color)
        mid = (art_h + H - FOOT) // 2
        text, f = fit(d, shown(card.get("name", "")), W - 60, 64, 36)
        d.text((W // 2, mid - (28 if SUBTITLES.get(cid) else 0)), text, font=f, fill=(255, 255, 255), anchor="mm")
        if SUBTITLES.get(cid):
            d.text((W // 2, mid + 40), SUBTITLES[cid], font=font(32, bold=False), fill=(255, 255, 255), anchor="mm")

    # ---- track list panel
    if listed:
        top = art_h
        # Frosted, lightly see-through panel: blur the art behind it, then a translucent tint.
        box = (0, top, W, top + list_h)
        canvas.paste(canvas.crop(box).filter(ImageFilter.GaussianBlur(10)), box[:2])
        canvas.alpha_composite(Image.new("RGBA", (W, list_h), mix(color, (255, 255, 255), 0.86) + (200,)), (0, top))
        d.rectangle((0, top, W, top + 7), fill=color)
        col_w = (W - 2 * pad - gap * (cols - 1)) // cols
        ic_size = 32 if cols == 1 else 28 if row_h >= 44 else 24
        size = 30 if cols == 1 else 23 if row_h >= 44 else 21
        ink = NAVY
        num_ink = mix(color, NAVY, 0.45)
        num_f = font(size)
        num_w = round(d.textlength(str(len(tracks)), font=num_f))
        lead = num_w + 8 + ic_size + 10  # number, icon, then the title
        for i, t in enumerate(tracks):
            c, r = (i // rows, i % rows)
            if hebrew:
                c = cols - 1 - c  # right-to-left: first column on the right
            x0 = pad + c * (col_w + gap)
            cy = top + pad + r * row_h + row_h // 2 + 3
            title = SHORT_TITLES.get(t.get("title", ""), t.get("title", ""))
            text, f = fit(d, title, col_w - lead, size, size - 5)
            text = shown(text)
            ic = icon(t.get("icon"), ic_size)
            if hebrew:
                x1 = x0 + col_w
                d.text((x1 - num_w // 2, cy), str(i + 1), font=num_f, fill=num_ink, anchor="mm")
                if ic:
                    canvas.alpha_composite(ic, (x1 - num_w - 8 - ic_size, cy - ic_size // 2))
                d.text((x1 - lead, cy), text, font=f, fill=ink, anchor="rm")
            else:
                d.text((x0 + num_w // 2, cy), str(i + 1), font=num_f, fill=num_ink, anchor="mm")
                if ic:
                    canvas.alpha_composite(ic, (x0 + num_w + 8, cy - ic_size // 2))
                d.text((x0 + lead, cy), text, font=f, fill=ink, anchor="lm")

    # ---- footer: logo always bottom-left, same size and spot on every card
    fy = H - FOOT
    d.rectangle((0, fy, W, H), fill=NAVY)
    lg = 46
    canvas.alpha_composite(logo(lg), (18, fy + (FOOT - lg) // 2))
    d.text((18 + lg + 12, fy + FOOT // 2 + 1), "BoopBox", font=font(28), fill=(255, 255, 255), anchor="lm")
    d.text((W - 22, fy + FOOT // 2 + 1), shown(footer_label(card, hebrew)), font=font(24, bold=False),
           fill=mix(NAVY, (255, 255, 255), 0.75), anchor="rm")

    out = os.path.join(OUT, cid + ".png")
    canvas.convert("RGB").save(out, dpi=(300, 300))
    return out


def phone_cards():
    """Cards as the phone has them (stickers/phone-cards.json, pulled with
    adb shell run-as com.boopboxapp cat files/cards.json), keyed by library folder = card id."""
    path = os.path.join(OUT, "phone-cards.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as fh:
        cards = json.load(fh)
    return {c["uri"].rstrip("/").rsplit("/", 1)[-1]: c for c in cards if "/files/library/" in (c.get("uri") or "")}


def apply_phone(card, phone):
    """Track order, titles, name and colour as edited on the phone. Pushed files are NN.mp3 = repo track NN."""
    p = phone.get(card["id"])
    if not p:
        return card
    tracks = card.get("tracks", [])
    order = []
    for item in p.get("playlist") or []:
        m = re.match(r"(\d+)\.\w+$", item["uri"].rsplit("/", 1)[-1])
        if m and 0 < int(m.group(1)) <= len(tracks):
            order.append(dict(tracks[int(m.group(1)) - 1], title=item.get("title") or tracks[int(m.group(1)) - 1]["title"]))
    if len(order) == len(tracks):
        card = dict(card, tracks=order)
    card["name"] = p.get("name") or card.get("name")
    if p.get("color"):
        card["color"] = "#%06X" % (p["color"] & 0xFFFFFF)
    return card


def main():
    want = set(sys.argv[1:])
    phone = phone_cards()
    for path in sorted(glob.glob(os.path.join(ROOT, "cards", "*.json"))):
        stem = os.path.basename(path)[:-5]
        if stem in SKIP or (want and stem not in want):
            continue
        with open(path, encoding="utf-8") as fh:
            card = json.load(fh)
        print(render(apply_phone(card, phone)))
    for card in EXTRA_CARDS:
        if not want or card["id"] in want:
            print(render(card))


if __name__ == "__main__":
    main()
