"""Draws the FireIPTV icon and banner: shapes in one place -> PNG previews (Pillow) + Android vector drawables.

Usage: python3 tools/make-icon.py OUTDIR, then copy ic_launcher.xml to core/ui/src/main/res/drawable/
and banner.xml to app/src/main/res/drawable/. Preview PNG corners show small nicks; Android draws them clean.
"""
import re, sys
from PIL import Image, ImageDraw

def rrect(x0, y0, x1, y1, r):
    k = r * 0.5523
    return (f"M{x0+r},{y0} L{x1-r},{y0} C{x1-r+k},{y0} {x1},{y0+r-k} {x1},{y0+r} "
            f"L{x1},{y1-r} C{x1},{y1-r+k} {x1-r+k},{y1} {x1-r},{y1} L{x0+r},{y1} "
            f"C{x0+r-k},{y1} {x0},{y1-r+k} {x0},{y1-r} L{x0},{y0+r} C{x0},{y0+r-k} {x0+r-k},{y0} {x0+r},{y0} Z")

def flame(cx, top, base, w):
    # outer flame, w = half width; scaled from a 0..1 design
    s = (base - top) / 33.0
    def P(x, y): return f"{cx + x*s:.2f},{top + y*s:.2f}"
    return ("M" + P(0, 0) +
            " C" + P(3, 7) + " " + P(12.5, 11.5) + " " + P(12.5, 21.5) +
            " C" + P(12.5, 28.5) + " " + P(7, 33) + " " + P(0, 33) +
            " C" + P(-7, 33) + " " + P(-12.5, 28.5) + " " + P(-12.5, 21.5) +
            " C" + P(-12.5, 15.5) + " " + P(-9, 11.5) + " " + P(-5.5, 8) +
            " C" + P(-5.5, 12.5) + " " + P(-4, 15) + " " + P(-1.5, 16) +
            " C" + P(-3, 10) + " " + P(-2.5, 4.5) + " " + P(0, 0) + " Z")

def inner(cx, top, base):
    s = (base - top) / 17.0
    def P(x, y): return f"{cx + x*s:.2f},{top + y*s:.2f}"
    return ("M" + P(0, 0) +
            " C" + P(2.5, 4) + " " + P(6.5, 6.5) + " " + P(6.5, 11) +
            " C" + P(6.5, 14.5) + " " + P(3.6, 17) + " " + P(0, 17) +
            " C" + P(-3.6, 17) + " " + P(-6.5, 14.5) + " " + P(-6.5, 11) +
            " C" + P(-6.5, 7.5) + " " + P(-4, 5) + " " + P(-2.5, 3.5) +
            " C" + P(-2, 5.5) + " " + P(-1, 6.5) + " " + P(0.3, 7) +
            " C" + P(-0.5, 4.5) + " " + P(-0.5, 2) + " " + P(0, 0) + " Z")

def design(W, H, tv):
    """tv = (x0, y0, x1, y1) of the screen; everything else follows it."""
    x0, y0, x1, y1 = tv
    sw = (x1 - x0) / 13.0            # stroke width
    cx = (x0 + x1) / 2
    r = (y1 - y0) * 0.22
    ah = (y1 - y0) * 0.26            # antenna height
    shapes = []
    shapes.append(dict(d=f"M0,0 L{W},0 L{W},{H} L0,{H} Z", grad=((0, 0), (W, H), "#FF262A3A", "#FF0C0D12")))
    # warm glow behind the screen
    g = (x1 - x0) * 0.18
    # antennas
    shapes.append(dict(d=f"M{cx - (x1-x0)*0.17},{y0 - ah} L{cx},{y0 - sw*0.2} L{cx + (x1-x0)*0.17},{y0 - ah}",
                       stroke="#FFF2F3F7", sw=sw * 0.8))
    # screen fill + frame
    shapes.append(dict(d=rrect(x0, y0, x1, y1, r), fill="#FF14161F"))
    shapes.append(dict(d=rrect(x0, y0, x1, y1, r), stroke="#FFF2F3F7", sw=sw))
    # flame
    top, base = y0 + (y1 - y0) * 0.11, y1 - (y1 - y0) * 0.12
    shapes.append(dict(d=flame(cx, top, base, 0), grad=((cx, top), (cx, base), "#FFFF3B30", "#FFFF9A1F")))
    itop = base - (base - top) * 0.52
    shapes.append(dict(d=inner(cx, itop, base - (base - top) * 0.03), grad=((cx, itop), (cx, base), "#FFFFD54A", "#FFFFF4C2")))
    return shapes

# ---------- preview rasterizer ----------
def flatten(d):
    toks = re.findall(r"[MLCZ]|-?[\d.]+", d)
    pts, polys, i, cur, cmd = [], [], 0, (0, 0), None
    def num():
        nonlocal i; v = float(toks[i]); i += 1; return v
    while i < len(toks):
        t = toks[i]
        if t in "MLCZ":
            cmd = t; i += 1
            if t == "Z":
                if pts: polys.append((pts, True)); pts = []
                continue
        if cmd == "M":
            if pts: polys.append((pts, False))
            cur = (num(), num()); pts = [cur]; cmd = "L"
        elif cmd == "L":
            cur = (num(), num()); pts.append(cur)
        elif cmd == "C":
            p1 = (num(), num()); p2 = (num(), num()); p3 = (num(), num()); p0 = cur
            for k in range(1, 25):
                u = k / 24
                pts.append(tuple((1-u)**3*a + 3*(1-u)**2*u*b + 3*(1-u)*u**2*c + u**3*e for a, b, c, e in zip(p0, p1, p2, p3)))
            cur = p3
    if pts: polys.append((pts, False))
    return polys

def hexc(h): h = h.lstrip("#"); return (int(h[2:4],16), int(h[4:6],16), int(h[6:8],16), int(h[0:2],16))

def render(shapes, W, H, scale, out):
    S = scale * 4
    img = Image.new("RGBA", (W*S, H*S), (0, 0, 0, 0))
    for sh in shapes:
        mask = Image.new("L", img.size, 0); md = ImageDraw.Draw(mask)
        for pts, closed in flatten(sh["d"]):
            P = [(x*S, y*S) for x, y in pts]
            if "stroke" in sh:
                w = int(sh["sw"]*S)
                md.line(P + ([P[0]] if closed else []), fill=255, width=w, joint="curve")
                for x, y in ([P[0], P[-1]] if not closed else []):
                    md.ellipse([x-w/2, y-w/2, x+w/2, y+w/2], fill=255)
            else:
                md.polygon(P, fill=255)
        if "grad" in sh:
            (gx0, gy0), (gx1, gy1), c0, c1 = sh["grad"]; a, b = hexc(c0), hexc(c1)
            layer = Image.new("RGBA", img.size); px = layer.load()
            dx, dy = (gx1-gx0)*S, (gy1-gy0)*S; L2 = dx*dx + dy*dy
            bb = mask.getbbox()
            if bb:
                for y in range(bb[1], bb[3], 1):
                    for x in range(bb[0], bb[2], 1):
                        t = max(0, min(1, ((x-gx0*S)*dx + (y-gy0*S)*dy) / L2))
                        px[x, y] = tuple(int(a[j] + (b[j]-a[j])*t) for j in range(4))
        else:
            layer = Image.new("RGBA", img.size, hexc(sh.get("fill") or sh["stroke"]))
        alpha = Image.eval(mask, lambda v: v)
        la = layer.split()[3]
        from PIL import ImageChops
        layer.putalpha(ImageChops.multiply(la, alpha))
        img = Image.alpha_composite(img, layer)
    img.resize((W*scale, H*scale), Image.LANCZOS).save(out)

# ---------- Android vector ----------
def vector(shapes, W, H, dp_w, dp_h):
    out = [f'<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           f'    xmlns:aapt="http://schemas.android.com/aapt"',
           f'    android:width="{dp_w}dp"', f'    android:height="{dp_h}dp"',
           f'    android:viewportWidth="{W}"', f'    android:viewportHeight="{H}">']
    for sh in shapes:
        d = re.sub(r"(\d+\.\d{2})\d+", r"\1", sh["d"])
        if "grad" in sh:
            (gx0, gy0), (gx1, gy1), c0, c1 = sh["grad"]
            out += [f'    <path android:pathData="{d}">',
                    '        <aapt:attr name="android:fillColor">',
                    f'            <gradient android:type="linear" android:startX="{gx0:g}" android:startY="{gy0:g}" android:endX="{gx1:g}" android:endY="{gy1:g}"',
                    f'                android:startColor="{c0}" android:endColor="{c1}" />',
                    '        </aapt:attr>', '    </path>']
        elif "stroke" in sh:
            out.append(f'    <path android:pathData="{d}" android:strokeColor="{sh["stroke"]}" android:strokeWidth="{sh["sw"]:.2f}" '
                       f'android:strokeLineCap="round" android:strokeLineJoin="round" />')
        else:
            out.append(f'    <path android:pathData="{d}" android:fillColor="{sh["fill"]}" />')
    out.append("</vector>")
    return "\n".join(out) + "\n"

if __name__ == "__main__":
    outdir = sys.argv[1]
    icon = design(108, 108, (14, 38, 94, 90))
    banner = design(320, 180, (92, 60, 228, 146))
    render(icon, 108, 108, 4, f"{outdir}/icon.png")
    render(banner, 320, 180, 2, f"{outdir}/banner.png")
    open(f"{outdir}/ic_launcher.xml", "w").write(vector(icon, 108, 108, 108, 108))
    open(f"{outdir}/banner.xml", "w").write(vector(banner, 320, 180, 320, 180))
