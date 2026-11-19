"""Draws the FireIPTV wordmark icons: "Fire" in orange-red, "IPTV" in white.

Letters come from a font's outlines (fontTools), so the drawables are plain vector paths.
Writes:
  core/ui/src/main/res/drawable/ic_launcher.xml   square app icon, "Fire" over "IPTV"
  core/ui/src/main/res/drawable/wordmark.xml      one-line wordmark, no background (home header)
  app/src/main/res/drawable/banner.xml            Fire TV launcher banner, one line
and PNG previews (Pillow) of each into OUTDIR.

Usage: PYTHONPATH=<dir with fonttools> python3 tools/make-icon.py OUTDIR [FONT [FONT_INDEX]] [--write]
Default font: DejaVu Sans Bold (fonts-dejavu-core). Without --write it only makes the previews.
"""
import os, re, sys
from fontTools.ttLib import TTFont, TTCollection
from fontTools.pens.basePen import BasePen
from PIL import Image, ImageChops, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIRE = ("#FFFFA726", "#FFFF3D1F")   # orange at the top, red at the bottom
IPTV = "#FFF2F3F7"
BG = ("#FF23263A", "#FF0B0C11")

class PathPen(BasePen):
    """Records a glyph as an M/L/C/Z path string, flipped to y-down and placed at (dx, base)."""
    def __init__(self, glyphset, scale, dx, base):
        super().__init__(glyphset); self.d = []; self.s, self.dx, self.base = scale, dx, base
    def p(self, pt): return f"{self.dx + pt[0]*self.s:.2f},{self.base - pt[1]*self.s:.2f}"
    def _moveTo(self, pt): self.d.append("M" + self.p(pt))
    def _lineTo(self, pt): self.d.append("L" + self.p(pt))
    def _curveToOne(self, a, b, c): self.d.append("C" + " ".join(map(self.p, (a, b, c))))
    def _qCurveToOne(self, a, b):
        p0 = self._getCurrentPoint()
        c1 = (p0[0] + 2/3*(a[0]-p0[0]), p0[1] + 2/3*(a[1]-p0[1]))
        c2 = (b[0] + 2/3*(a[0]-b[0]), b[1] + 2/3*(a[1]-b[1]))
        self._curveToOne(c1, c2, b)
    def _closePath(self): self.d.append("Z")
    def _endPath(self): pass

class Font:
    def __init__(self, path, index=0):
        self.f = TTCollection(path).fonts[index] if path.endswith(".ttc") else TTFont(path)
        self.gs = self.f.getGlyphSet(); self.cmap = self.f.getBestCmap()
        self.upm = self.f["head"].unitsPerEm
        os2 = self.f["OS/2"]; self.cap = getattr(os2, "sCapHeight", 0) or self.upm * 0.7
    def width(self, text, track=0.0):
        return sum(self.gs[self.cmap[ord(c)]].width for c in text) + track * self.upm * (len(text) - 1)
    def path(self, text, size_cap, x, base, track=0.0):
        """Text whose capitals are size_cap tall, starting at x on baseline base. Returns (path, width)."""
        s = size_cap / self.cap; parts = []
        for c in text:
            g = self.gs[self.cmap[ord(c)]]
            pen = PathPen(self.gs, s, x, base); g.draw(pen); parts += pen.d
            x += (g.width + track * self.upm) * s
        return " ".join(parts), self.width(text, track) * s

def wordmark(font, cap, cx, base, track):
    """One line "Fire" + "IPTV" centred on cx."""
    s = cap / font.cap
    w1, w2 = font.width("Fire", track) * s, font.width("IPTV", track) * s
    gap = track * font.upm * s
    x = cx - (w1 + gap + w2) / 2
    fire, _ = font.path("Fire", cap, x, base, track)
    iptv, _ = font.path("IPTV", cap, x + w1 + gap, base, track)
    return [dict(d=fire, grad=((cx, base - cap), (cx, base), *FIRE)), dict(d=iptv, fill=IPTV)]

def background(W, H):
    return [dict(d=f"M0,0 L{W},0 L{W},{H} L0,{H} Z", grad=((0, 0), (W, H), *BG))]

def icon(font, track):
    # Two lines: "Fire" over "IPTV", each as wide as the other allows.
    W = 108; cap = 26
    s = cap / font.cap
    wf, wi = font.width("Fire", track) * s, font.width("IPTV", track) * s
    fire, _ = font.path("Fire", cap, (W - wf) / 2, 50, track)
    iptv, _ = font.path("IPTV", cap, (W - wi) / 2, 84, track)
    return background(W, W) + [dict(d=fire, grad=((54, 24), (54, 50), *FIRE)), dict(d=iptv, fill=IPTV)]

def fitted(font, W, H, margin, track):
    """One-line wordmark as large as fits W wide with margin."""
    cap = (W - 2 * margin) / ((font.width("Fire", track) + font.width("IPTV", track) + track * font.upm) / font.cap)
    return wordmark(font, cap, W / 2, H / 2 + cap / 2, track)

# ---------- preview rasterizer ----------
def flatten(d):
    toks = re.findall(r"[MLCZ]|-?[\d.]+", d); polys, pts, i, cur, cmd = [], [], 0, (0, 0), None
    def num():
        nonlocal i; v = float(toks[i]); i += 1; return v
    while i < len(toks):
        t = toks[i]
        if t in "MLCZ":
            cmd = t; i += 1
            if t == "Z":
                if pts: polys.append(pts); pts = []
                continue
        if cmd == "M":
            if pts: polys.append(pts)
            cur = (num(), num()); pts = [cur]; cmd = "L"
        elif cmd == "L":
            cur = (num(), num()); pts.append(cur)
        elif cmd == "C":
            p1, p2, p3, p0 = (num(), num()), (num(), num()), (num(), num()), cur
            for k in range(1, 13):
                u = k / 12
                pts.append(tuple((1-u)**3*a + 3*(1-u)**2*u*b + 3*(1-u)*u**2*c + u**3*e for a, b, c, e in zip(p0, p1, p2, p3)))
            cur = p3
    if pts: polys.append(pts)
    return polys

def hexc(h): h = h.lstrip("#"); return (int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16), int(h[0:2], 16))

def render(shapes, W, H, scale, out):
    S = scale * 4; size = (int(W*S), int(H*S)); img = Image.new("RGBA", size, (0, 0, 0, 0))
    for sh in shapes:
        mask = Image.new("L", size, 0)
        for pts in flatten(sh["d"]):   # even-odd: holes in P, e, ...
            m = Image.new("L", size, 0); ImageDraw.Draw(m).polygon([(x*S, y*S) for x, y in pts], fill=255)
            mask = ImageChops.logical_xor(mask.convert("1"), m.convert("1")).convert("L")
        if "grad" in sh:
            (gx0, gy0), (gx1, gy1), c0, c1 = sh["grad"]; a, b = hexc(c0), hexc(c1)
            col = Image.linear_gradient("L").resize(size) if gx0 == gx1 else None
            layer = Image.new("RGBA", size); px = layer.load(); bb = mask.getbbox()
            dx, dy = (gx1-gx0)*S, (gy1-gy0)*S; L2 = dx*dx + dy*dy
            if bb:
                for y in range(bb[1], bb[3]):
                    for x in range(bb[0], bb[2]):
                        t = max(0, min(1, ((x-gx0*S)*dx + (y-gy0*S)*dy) / L2))
                        px[x, y] = tuple(int(a[j] + (b[j]-a[j])*t) for j in range(4))
        else:
            layer = Image.new("RGBA", size, hexc(sh["fill"]))
        layer.putalpha(ImageChops.multiply(layer.split()[3], mask))
        img = Image.alpha_composite(img, layer)
    img.resize((int(W*scale), int(H*scale)), Image.LANCZOS).save(out)

# ---------- Android vector ----------
def vector(shapes, W, H):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Generated by tools/make-icon.py; edit that, not this. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    xmlns:aapt="http://schemas.android.com/aapt"',
           f'    android:width="{W:g}dp"', f'    android:height="{H:g}dp"',
           f'    android:viewportWidth="{W:g}"', f'    android:viewportHeight="{H:g}">']
    for sh in shapes:
        d = sh["d"]
        if "grad" in sh:
            (gx0, gy0), (gx1, gy1), c0, c1 = sh["grad"]
            out += [f'    <path android:fillType="evenOdd" android:pathData="{d}">',
                    '        <aapt:attr name="android:fillColor">',
                    f'            <gradient android:type="linear" android:startX="{gx0:.2f}" android:startY="{gy0:.2f}" '
                    f'android:endX="{gx1:.2f}" android:endY="{gy1:.2f}"',
                    f'                android:startColor="{c0}" android:endColor="{c1}" />',
                    '        </aapt:attr>', '    </path>']
        else:
            out.append(f'    <path android:fillType="evenOdd" android:fillColor="{sh["fill"]}" android:pathData="{d}" />')
    out.append("</vector>")
    return "\n".join(out) + "\n"

if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--write"]
    outdir = args[0]
    font = Font(args[1] if len(args) > 1 else "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                int(args[2]) if len(args) > 2 else 0)
    track = -0.01
    ic = icon(font, track)
    bn = background(320, 180) + fitted(font, 320, 180, 22, track)
    wm_w, wm_h = 200, 40
    wm = fitted(font, wm_w, wm_h, 0, track)
    render(ic, 108, 108, 4, f"{outdir}/icon.png")
    render(bn, 320, 180, 2, f"{outdir}/banner.png")
    render(background(wm_w, wm_h) + wm, wm_w, wm_h, 3, f"{outdir}/wordmark.png")
    if "--write" in sys.argv:
        open(f"{ROOT}/core/ui/src/main/res/drawable/ic_launcher.xml", "w").write(vector(ic, 108, 108))
        open(f"{ROOT}/core/ui/src/main/res/drawable/wordmark.xml", "w").write(vector(wm, wm_w, wm_h))
        open(f"{ROOT}/app/src/main/res/drawable/banner.xml", "w").write(vector(bn, 320, 180))
