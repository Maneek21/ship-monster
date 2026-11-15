#!/usr/bin/env python3
"""
Draws every Ship Monster sprite in code and writes the sheets the app loads.

    python3 art/generate.py            # writes android/app/src/main/assets/sprites/*.png
    python3 art/generate.py --preview  # also writes art/preview/*.png, scaled up, for looking at

Each species sheet is 7 columns by 8 rows of 32x32 cells:
    columns: idle, blink, happy, hungry, feral, sleep, stone
    rows:    stage 1 (Speck) to stage 8 (Titan)

Monsters are built from a body shape that grows with the stage, a palette, and species
features that appear as the monster grows. Nothing here is traced from any existing character.
"""
import math
import os
import sys
from PIL import Image

N = 32
GROUND = 29           # last body row; feet and outline go below
CX = 16.0             # centre line between pixel 15 and 16
VARIANTS = ["idle", "blink", "happy", "hungry", "feral", "sleep", "stone"]
STAGES = 8

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "sprites")
PREVIEW = os.path.join(HERE, "preview")


def rgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


INK = rgb("#2A1F3D")
WHITE = rgb("#FFFAF0")
PINK = rgb("#FF8FA3")
RED = rgb("#FF3B4E")
SWEAT = rgb("#6EC3FF")


class Grid:
    def __init__(self):
        self.px = [[None] * N for _ in range(N)]

    def get(self, x, y):
        if 0 <= x < N and 0 <= y < N:
            return self.px[y][x]
        return None

    def set(self, x, y, c):
        x, y = int(x), int(y)
        if 0 <= x < N and 0 <= y < N:
            self.px[y][x] = c

    def rect(self, x0, y0, x1, y1, c):
        for y in range(int(y0), int(y1) + 1):
            for x in range(int(x0), int(x1) + 1):
                self.set(x, y, c)

    def ellipse(self, cx, cy, rx, ry, c):
        for y in range(N):
            for x in range(N):
                if ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1.0:
                    self.set(x, y, c)

    def line(self, x0, y0, x1, y1, c):
        steps = int(max(abs(x1 - x0), abs(y1 - y0))) or 1
        for i in range(steps + 1):
            t = i / steps
            self.set(round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t), c)

    def tri(self, ax, ay, bx, by, cx, cy, c):
        """Filled triangle."""
        minx, maxx = int(min(ax, bx, cx)), int(max(ax, bx, cx))
        miny, maxy = int(min(ay, by, cy)), int(max(ay, by, cy))

        def side(px, py, x0, y0, x1, y1):
            return (px - x1) * (y0 - y1) - (x0 - x1) * (py - y1)

        for y in range(miny, maxy + 1):
            for x in range(minx, maxx + 1):
                d1 = side(x, y, ax, ay, bx, by)
                d2 = side(x, y, bx, by, cx, cy)
                d3 = side(x, y, cx, cy, ax, ay)
                neg = d1 < 0 or d2 < 0 or d3 < 0
                pos = d1 > 0 or d2 > 0 or d3 > 0
                if not (neg and pos):
                    self.set(x, y, c)

    def outline(self, c):
        add = []
        for y in range(N):
            for x in range(N):
                if self.px[y][x] is None:
                    if any(self.get(x + dx, y + dy) is not None for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                        add.append((x, y))
        for x, y in add:
            self.px[y][x] = c

    def image(self):
        im = Image.new("RGBA", (N, N), (0, 0, 0, 0))
        for y in range(N):
            for x in range(N):
                c = self.px[y][x]
                if c is not None:
                    im.putpixel((x, y), (c[0], c[1], c[2], 255))
        return im


# ---------------------------------------------------------------- bodies

WIDTH = {
    "blob":  [8, 10, 12, 15, 18, 21, 23, 25],
    "chunk": [7, 9, 11, 14, 16, 19, 21, 23],
    "tall":  [6, 8, 10, 12, 14, 16, 18, 19],
    "wide":  [9, 11, 14, 17, 20, 22, 24, 25],
    "wyrm":  [7, 9, 11, 13, 16, 19, 21, 23],
}
HEIGHT_F = {"blob": 0.9, "chunk": 0.95, "tall": 1.22, "wide": 0.66, "wyrm": 1.0}


class Body:
    """Geometry of one monster body at one stage."""

    def __init__(self, family, stage):
        self.family = family
        self.stage = stage
        self.w = WIDTH[family][stage - 1]
        self.h = max(5, int(round(self.w * HEIGHT_F[family])))
        self.bottom = GROUND
        self.top = self.bottom - self.h + 1
        self.left = CX - self.w / 2
        self.right = CX + self.w / 2
        self.cy = (self.top + self.bottom + 1) / 2
        self.mask = self._mask()
        ys = [y for (_, y) in self.mask]
        self.top = min(ys)

    def _mask(self):
        g = Grid()
        w, h = self.w, self.h
        t, b = self.top, self.bottom
        f = self.family
        if f == "blob":
            g.ellipse(CX, (t + b + 1) / 2, w / 2, h / 2, 1)
            g.ellipse(CX, b - h * 0.22, w / 2, h * 0.3, 1)        # fuller base
        elif f == "chunk":
            r = max(1, w // 5)
            g.rect(CX - w / 2 + r, t, CX + w / 2 - r - 1, b, 1)
            g.rect(CX - w / 2, t + r, CX + w / 2 - 1, b - max(1, r // 2), 1)
            for (ex, ey) in ((CX - w / 2 + r, t + r), (CX + w / 2 - r, t + r)):
                g.ellipse(ex, ey, r, r, 1)
            for (ex, ey) in ((CX - w / 2 + r, b - r / 2 + 0.5), (CX + w / 2 - r, b - r / 2 + 0.5)):
                g.ellipse(ex, ey, r, max(1, r / 2 + 0.5), 1)
        elif f == "tall":
            g.ellipse(CX, t + h * 0.36, w * 0.40, h * 0.36, 1)
            g.ellipse(CX, b - h * 0.36 + 1, w / 2, h * 0.38, 1)
            g.rect(CX - w * 0.38, t + h * 0.3, CX + w * 0.38 - 1, b - h * 0.3, 1)
        elif f == "wide":
            g.ellipse(CX, (t + b + 1) / 2, w / 2, h / 2, 1)
            g.ellipse(CX, b - h * 0.25, w / 2 - 0.5, h * 0.3, 1)
        elif f == "wyrm":
            # coiled base, a neck rising on the left, head on top
            coil_h = max(3, h * 0.42)
            g.ellipse(CX + w * 0.06, b - coil_h / 2 + 0.5, w / 2, coil_h / 2, 1)
            neck_w = max(2, w * 0.34)
            nx = CX - w * 0.18
            g.rect(nx - neck_w / 2, t + h * 0.3, nx + neck_w / 2 - 1, b - coil_h * 0.5, 1)
            g.ellipse(nx + w * 0.05, t + h * 0.24, max(2.2, w * 0.31), max(2.2, h * 0.25), 1)
        out = set()
        for y in range(N):
            for x in range(N):
                if g.px[y][x] is not None:
                    out.add((x, y))
        return out

    def row(self, y):
        """(min x, max x) of the body on a row, or None."""
        xs = [x for (x, yy) in self.mask if yy == y]
        return (min(xs), max(xs)) if xs else None

    def face_centre(self):
        """Where the face sits: x centre, eye row, mouth row."""
        if self.family == "wyrm":
            nx = CX - self.w * 0.18 + self.w * 0.05
            return nx, self.top + self.h * 0.22, self.top + self.h * 0.36
        if self.family == "tall":
            return CX, self.top + self.h * 0.34, self.top + self.h * 0.54
        if self.family == "wide":
            return CX, self.top + self.h * 0.40, self.top + self.h * 0.66
        return CX, self.top + self.h * 0.42, self.top + self.h * 0.66

    def head_top(self, x):
        """Topmost body row at column x."""
        ys = [y for (xx, y) in self.mask if xx == int(x)]
        return min(ys) if ys else self.top


# ---------------------------------------------------------------- species

def P(base, shade, light, belly, feat, feat2=None, outline="#2A1F3D"):
    return dict(base=rgb(base), shade=rgb(shade), light=rgb(light), belly=rgb(belly),
                feat=rgb(feat), feat2=rgb(feat2 or feat), outline=rgb(outline))


SPECIES = {
    "mossback":   dict(family="blob",  pal=P("#86C46A", "#5E9E4B", "#B9E394", "#EAF4C8", "#3F8F3F", "#FF7EB6", "#22331F"), eyes="dot", mouth="smile"),
    "cindermite": dict(family="chunk", pal=P("#F2793A", "#C6491E", "#FFB05C", "#FFD98A", "#FFD23F", "#4A2A22", "#3A1A14"), eyes="dot", mouth="tooth"),
    "puddlewump": dict(family="wide",  pal=P("#5AAFEA", "#3A7FC0", "#9AD4FF", "#D8F0FF", "#9AD4FF", "#FFFFFF", "#1C2F4F"), eyes="big", mouth="smile"),
    "gravelkin":  dict(family="chunk", pal=P("#A39E97", "#7B766F", "#C9C4BC", "#B8B3AA", "#5E5953", "#6FE0D0", "#2E2B28"), eyes="lid", mouth="underbite"),
    "fuzzlet":    dict(family="tall",  pal=P("#F3A6C8", "#D97BA6", "#FFD0E4", "#FFF0F6", "#FFFFFF", "#FFE07A", "#4A2338"), eyes="sleepy", mouth="smile"),
    "gloomfang":  dict(family="tall",  pal=P("#8E5BE8", "#6436B8", "#B592FF", "#D9C6FF", "#4B2A8F", "#FFFAF0", "#22123F"), eyes="smug", mouth="fangs"),
    "brasshorn":  dict(family="chunk", pal=P("#D9A441", "#A8782A", "#F5D27A", "#F9E7B0", "#FFF3D6", "#7A5520", "#3A2810"), eyes="dot", mouth="smile"),
    "thornback":  dict(family="wide",  pal=P("#3F8F6B", "#2A6B4E", "#6FC09A", "#BFE6C8", "#E8D36A", "#B89A2E", "#132A20"), eyes="cyclops", mouth="grin"),
    "frostnip":   dict(family="blob",  pal=P("#CFEFFF", "#9FC8E6", "#FFFFFF", "#EAF8FF", "#7FB8E8", "#FFFFFF", "#2C4A66"), eyes="lid", mouth="flat"),
    "voidling":   dict(family="tall",  pal=P("#2B2350", "#1A1436", "#4A3F85", "#3A3070", "#7FF0FF", "#FFF0A0", "#0E0A22"), eyes="three", mouth="none"),
    "sunmaw":     dict(family="wide",  pal=P("#FFB52E", "#E3861A", "#FFE07A", "#FFF3C4", "#FF7A1A", "#FFD23F", "#4A2408"), eyes="dot", mouth="grin"),
    "mergewyrm":  dict(family="wyrm",  pal=P("#2FB7A3", "#1E8576", "#7FE3D2", "#F4D35E", "#F4D35E", "#FFF3C4", "#0F332E"), eyes="dot", mouth="smile"),
}
ORDER = list(SPECIES.keys())


# ---------------------------------------------------------------- features drawn behind the body

def behind(g, sp, body, s, pal):
    f = pal["feat"]
    f2 = pal["feat2"]
    w, h, t, b = body.w, body.h, body.top, body.bottom
    L, R = int(body.left), int(body.right) - 1
    mid = int((t + b) / 2)

    if sp == "fuzzlet" and s >= 3:                       # little wings
        k = 2 + (s - 3) // 2
        for side in (-1, 1):
            ex = (L - 1) if side < 0 else (R + 1)
            g.tri(ex, mid - k, ex + side * (k + 1), mid - k - 1, ex, mid + 1, f)
    if sp == "gloomfang" and s >= 4:                     # bat wings, kept inside the frame
        k = min(3 + (s - 4), int(body.left) - 1)
        for side in (-1, 1):
            ex = (L + 1) if side < 0 else (R - 1)
            tipx = ex + side * (k + 1)
            g.tri(ex, mid - 1, tipx, mid - k, ex, mid + k // 2 + 1, f)
            g.tri(ex, mid + 1, tipx, mid + 1, ex, mid + k // 2 + 2, f)
            g.line(ex, mid - 1, tipx, mid - k, pal["shade"])
    if sp == "thornback":                                # back spikes fan
        n = min(7, 1 + s)
        span = w * 0.8
        length = 2 + s // 2
        for i in range(n):
            x = CX - span / 2 + span * (i + 0.5) / n
            top_y = body.head_top(x)
            g.tri(x - 1, top_y + 1, x, top_y - length, x + 1, top_y + 1, f)
            g.set(int(x), top_y - length + 1, f2)
    if sp == "sunmaw":                                   # rays
        n = 3 + s * 2 if s < 8 else 20
        rx, ry = w / 2, h / 2
        length = 1.5 + s * 0.45
        cy = (t + b + 1) / 2
        for i in range(n):
            a = math.pi * (1.05 + 0.9 * (i + 0.5) / n) if s < 6 else 2 * math.pi * i / n
            ox, oy = math.cos(a), math.sin(a)
            bx, by = CX + ox * rx * 0.9, cy + oy * ry * 0.9
            tx, ty = CX + ox * (rx + length), cy + oy * (ry + length)
            if ty > GROUND:
                continue
            c = f if i % 2 == 0 else f2
            g.tri(bx - oy * 1.2, by + ox * 1.2, tx, ty, bx + oy * 1.2, by - ox * 1.2, c)
    if sp == "voidling" and s >= 7:                      # dotted aura
        cy = (t + b) / 2
        for i in range(24):
            a = 2 * math.pi * i / 24
            x, y = CX + math.cos(a) * (w / 2 + 3), cy + math.sin(a) * (h / 2 + 2)
            if i % 2 == 0 and y < GROUND:
                g.set(round(x), round(y), f if i % 4 == 0 else f2)
    if sp == "mergewyrm":                                # tail curl
        if s >= 2:
            tx = R + 1
            k = 1 + s // 2
            g.line(tx - 1, b - 1, tx + 1, b - k, pal["base"])
            g.line(tx, b - 1, tx + 2, b - k, pal["base"])
            g.set(tx + 2, b - k - 1, f)
            if s >= 5:
                g.tri(tx + 1, b - k - 1, tx + 3, b - k - 3, tx + 3, b - k, f)
    if sp == "puddlewump" and s >= 4:                    # side fins
        k = 1 + (s - 4) // 2
        for side in (-1, 1):
            ex = L if side < 0 else R
            g.tri(ex, mid, ex + side * (k + 2), mid - k - 1, ex, mid + k + 1, f)
    if sp == "brasshorn" and s >= 6:                     # shoulder plates
        for side in (-1, 1):
            ex = L if side < 0 else R
            g.rect(min(ex, ex + side), mid - 2, max(ex, ex + side), mid + 1, pal["feat2"])
    if sp == "cindermite" and s >= 6:                    # side flames
        k = 2 + (s - 6)
        for side in (-1, 1):
            ex = L if side < 0 else R
            g.tri(ex, mid + 1, ex + side * (k + 1), mid - k, ex, mid - k + 1, rgb("#FF7A1A"))
            g.set(ex + side, mid - 1, f)


# ---------------------------------------------------------------- features drawn on top of the body

def on_top(g, sp, body, s, pal):
    f, f2 = pal["feat"], pal["feat2"]
    w, h, t, b = body.w, body.h, body.top, body.bottom
    fx, _, _ = body.face_centre()
    hx = int(round(fx))
    ht = body.head_top(hx)

    if sp == "mossback":
        stem = min(4, 1 + s // 2)
        g.line(hx, ht - 1, hx, ht - stem, rgb("#2F7A2F"))
        g.set(hx + 1, ht - stem, f)
        if s >= 2:
            g.set(hx - 1, ht - stem + 1, f)
            g.set(hx + 2, ht - stem - 1, rgb("#6CCB6C"))
        if s >= 4:
            g.tri(hx + 1, ht - stem, hx + 3, ht - stem - 2, hx + 3, ht - stem, f)
            g.tri(hx - 1, ht - stem + 1, hx - 3, ht - stem - 1, hx - 3, ht - stem + 1, rgb("#6CCB6C"))
        if s >= 6:
            g.ellipse(hx + 0.5, ht - stem - 1.5, 2.2, 2.2, f2)
            g.set(hx, ht - stem - 2, rgb("#FFE07A"))
        if s >= 8:
            for dx in (-5, 5):
                bx = hx + dx
                by = body.head_top(bx)
                g.line(bx, by - 1, bx, by - 2, rgb("#2F7A2F"))
                g.ellipse(bx + 0.5, by - 3.5, 1.6, 1.6, f2)
                g.set(bx, by - 4, rgb("#FFE07A"))
    elif sp == "cindermite":
        k = 2 + s // 2
        g.tri(hx - k // 2 - 1, ht, hx, ht - k - 1, hx + k // 2 + 1, ht, rgb("#FF7A1A"))
        g.tri(hx - max(1, k // 3), ht, hx, ht - max(1, k - 1), hx + max(1, k // 3), ht, f)
        if s >= 4:
            g.set(hx + k // 2 + 1, ht - k, rgb("#FF7A1A"))
    elif sp == "puddlewump":
        stalks = [0] if s < 6 else [-3, 3]
        if s >= 8:
            stalks = [-5, 0, 5]
        k = min(4, 1 + s // 2)
        for dx in stalks:
            x = hx + dx
            y = body.head_top(x)
            g.line(x, y - 1, x, y - k, pal["shade"])
            g.ellipse(x + 0.5, y - k - 0.5, 1.6, 1.6, f)
            g.set(x, y - k - 1, f2)
    elif sp == "gravelkin":
        if s >= 2:
            g.rect(hx - 2, ht - 1, hx + 1, ht - 1, rgb("#6FAE5A"))       # moss cap
            g.set(hx - 3, ht, rgb("#6FAE5A"))
        if s >= 5:
            n = 1 + (s - 5)
            for i in range(n):
                x = hx + 3 + i * 3
                y = body.head_top(min(x, int(body.right) - 2))
                g.tri(x - 1, y, x, y - 3 - i, x + 1, y, f2)
                g.set(x, y - 2 - i, WHITE)
        if s >= 8:
            x = hx - 6
            y = body.head_top(x)
            g.tri(x - 1, y, x, y - 5, x + 1, y, f2)
            g.set(x, y - 4, WHITE)
    elif sp == "fuzzlet":
        g.set(hx, ht - 1, pal["light"])
        g.set(hx + 1, ht - 2, pal["light"])
        if s >= 5:
            g.set(hx - 1, ht - 2, pal["light"])
        if s >= 8:                                      # a star to sleep under
            sx, sy = hx + 7, t - 3
            g.rect(sx - 1, sy, sx + 1, sy, f2)
            g.rect(sx, sy - 1, sx, sy + 1, f2)
    elif sp == "gloomfang":
        k = 2 + s // 3
        for side in (-1, 1):
            ex = hx + side * max(2, int(w * 0.28))
            ey = body.head_top(ex)
            g.tri(ex - 1, ey + 1, ex + side, ey - k, ex + 1, ey + 1, pal["base"])
            g.set(ex, ey - 1, PINK)
        if s >= 7:
            for side in (-1, 1):
                ex = hx + side * 1
                g.line(ex, ht - 1, ex + side * 2, ht - 4, f2)
    elif sp == "brasshorn":
        k = min(6, 1 + s)
        for side in (-1, 1):
            ex = hx + side * max(2, int(w * 0.3))
            ey = body.head_top(ex)
            g.line(ex, ey, ex + side * (k // 2), ey - k // 2 - 1, f)
            g.line(ex + side, ey, ex + side * (k // 2 + 1), ey - k // 2 - 1, f)
            if s >= 4:
                g.line(ex + side * (k // 2 + 1), ey - k // 2 - 1, ex + side * (k // 2), ey - k, f)
        if s >= 8:                                      # crown
            g.rect(hx - 2, ht - 2, hx + 1, ht - 1, rgb("#FFD23F"))
            g.set(hx - 2, ht - 3, rgb("#FFD23F"))
            g.set(hx + 1, ht - 3, rgb("#FFD23F"))
            g.set(hx - 1, ht - 3, RED)
    elif sp == "frostnip":
        n = min(5, 1 + s // 2)
        span = max(2, w * 0.5)
        for i in range(n):
            x = int(round(hx - span / 2 + span * (i + 0.5) / n))
            y = body.head_top(x)
            k = 2 + (s // 3) + (1 if i == n // 2 else 0)
            g.line(x, y, x, y - k, f)
            g.set(x, y - k, f2)
        if s >= 8:
            for (dx, dy) in ((-11, -2), (11, 1), (-9, 6)):
                g.set(hx + dx, t + dy, f2)
                g.set(hx + dx + 1, t + dy, f)
    elif sp == "voidling":
        if s >= 2:                                      # crescent horns
            k = 1 + s // 2
            for side in (-1, 1):
                ex = hx + side * max(1, int(w * 0.22))
                ey = body.head_top(ex)
                g.line(ex, ey, ex + side * (k // 2 + 1), ey - k, pal["base"])
                g.line(ex + side, ey, ex + side * (k // 2 + 2), ey - k + 1, pal["light"])
                g.set(ex + side * (k // 2), ey - k - 1, f)
        if s >= 4:                                      # a halo
            r = max(2, int(w * 0.3))
            for dx in range(-r, r + 1):
                if abs(dx) != r or s >= 6:
                    g.set(hx + dx, ht - 3 - (1 if abs(dx) < r else 0) - s // 3, f2 if dx % 2 == 0 else f)
        if s >= 5:
            orbs = [(-1, 0.25), (1, 0.6)] if s < 8 else [(-1, 0.2), (1, 0.45), (-1, 0.8)]
            for side, fr in orbs:
                ox = (body.left - 3) if side < 0 else (body.right + 2)
                oy = t + h * fr
                g.set(round(ox), round(oy), f)
                g.set(round(ox), round(oy) - 1, f2)
        if s >= 3:
            g.set(hx, ht - 2, f2)
        if s >= 6:
            g.set(hx - 3, ht - 1, f)
            g.set(hx + 3, ht - 1, f)
    elif sp == "mergewyrm":
        k = min(5, 1 + s // 2)
        for side in (-1, 1):
            ex = hx + side
            g.line(ex, ht - 1, ex + side * k // 2, ht - k, f)
            if s >= 4:
                g.set(ex + side * (k // 2 + 1), ht - k + 1, f)
            if s >= 6:
                g.set(ex + side * (k // 2 - 1), ht - k - 1, f)
        if s >= 8:
            g.set(hx, ht - 6, f2)
            g.set(hx - 4, ht - 4, f2)
            g.set(hx + 4, ht - 4, f2)


# ---------------------------------------------------------------- body paint

def paint_body(g, sp, body, s, pal):
    w, h = body.w, body.h
    x0, y0 = body.left, body.top
    for (x, y) in body.mask:
        u = (x + 0.5 - x0) / w
        v = (y + 0.5 - y0) / h
        c = pal["base"]
        if u + v > 1.42 or v > 0.9:
            c = pal["shade"]
        g.set(x, y, c)
    # highlight
    if s >= 2:
        k = 1 if s < 5 else 2
        hx = int(x0 + w * 0.27)
        hy = int(y0 + h * 0.2)
        for dy in range(k):
            for dx in range(k + (1 if s >= 6 else 0)):
                if (hx + dx, hy + dy) in body.mask:
                    g.set(hx + dx, hy + dy, pal["light"])

    def inside(x, y):
        return (int(x), int(y)) in body.mask

    fam = body.family
    # belly patch
    if sp in ("mossback", "puddlewump", "fuzzlet", "gloomfang", "sunmaw", "cindermite") and s >= 3:
        bg = Grid()
        bg.ellipse(CX, body.bottom - h * 0.2, w * 0.27, h * 0.24, 1)
        for y in range(N):
            for x in range(N):
                if bg.px[y][x] and inside(x, y):
                    g.set(x, y, pal["belly"])
    if sp == "mergewyrm" and s >= 2:                     # gold belly scales along the coil
        for (x, y) in body.mask:
            if y >= body.bottom - max(1, int(h * 0.16)) and (x + y) % 2 == 0:
                g.set(x, y, pal["belly"])
    # patterns
    if sp == "cindermite":
        spots = [(0.72, 0.3), (0.25, 0.62), (0.8, 0.7), (0.4, 0.22)][: 1 + s // 2]
        for (u, v) in spots:
            x, y = int(x0 + w * u), int(y0 + h * v)
            if inside(x, y):
                g.set(x, y, pal["feat2"])
                if s >= 5 and inside(x + 1, y):
                    g.set(x + 1, y, pal["feat2"])
    if sp == "gravelkin":
        cracks = [((0.7, 0.15), (0.6, 0.38)), ((0.2, 0.7), (0.34, 0.86)), ((0.82, 0.55), (0.72, 0.75))][: 1 + s // 3]
        for (a, b2) in cracks:
            ax, ay = int(x0 + w * a[0]), int(y0 + h * a[1])
            bx, by = int(x0 + w * b2[0]), int(y0 + h * b2[1])
            steps = max(abs(bx - ax), abs(by - ay)) or 1
            for i in range(steps + 1):
                x = round(ax + (bx - ax) * i / steps)
                y = round(ay + (by - ay) * i / steps)
                if inside(x, y):
                    g.set(x, y, pal["feat"])
    if sp == "brasshorn" and s >= 3:
        for (u, v) in [(0.14, 0.5), (0.86, 0.5), (0.5, 0.92), (0.2, 0.84), (0.8, 0.84)][: 2 + s // 2]:
            x, y = int(x0 + w * u), int(y0 + h * v)
            if inside(x, y):
                g.set(x, y, pal["feat2"])
    if sp == "frostnip" and s >= 2:
        for (u, v) in [(0.75, 0.3), (0.2, 0.55), (0.6, 0.82), (0.35, 0.2), (0.85, 0.62)][: s // 2 + 1]:
            x, y = int(x0 + w * u), int(y0 + h * v)
            if inside(x, y):
                g.set(x, y, WHITE)
    if sp == "voidling":
        stars = [(0.3, 0.72), (0.7, 0.62), (0.5, 0.86), (0.22, 0.5), (0.78, 0.82), (0.42, 0.6), (0.62, 0.94)][: 1 + s]
        for i, (u, v) in enumerate(stars):
            x, y = int(x0 + w * u), int(y0 + h * v)
            if inside(x, y):
                g.set(x, y, pal["feat2"] if i % 2 == 0 else pal["feat"])
    if sp == "thornback" and s >= 3:
        for i in range(2 + s // 2):
            x = int(x0 + w * (0.18 + 0.64 * i / max(1, 1 + s // 2)))
            y = int(y0 + h * 0.2)
            if inside(x, y):
                g.set(x, y, pal["light"])
    if sp == "fuzzlet":                                  # fluff: ragged edge
        edge = []
        for (x, y) in body.mask:
            for dx, dy in ((1, 0), (-1, 0), (0, -1)):
                p = (x + dx, y + dy)
                if p not in body.mask and y < body.bottom - 1 and (p[0] * 3 + p[1] * 5) % 3 == 0:
                    edge.append(p)
        for (x, y) in edge:
            if g.get(x, y) is None:
                g.set(x, y, pal["base"])
    # feet and arms
    if fam in ("blob", "chunk", "tall", "wide") and s >= 2 and sp != "voidling":
        fw = 1 if s < 4 else (2 if s < 7 else 3)
        off = max(2, int(w * 0.24))
        for side in (-1, 1):
            fx = int(CX + side * off - (fw / 2 if side > 0 else fw / 2))
            g.rect(fx, body.bottom + 1, fx + fw - 1, body.bottom + 1, pal["shade"])
    if fam in ("blob", "chunk", "tall") and s >= 4 and sp not in ("voidling", "fuzzlet"):
        ay = int(body.top + h * 0.62)
        k = 1 if s < 7 else 2
        for side in (-1, 1):
            r = body.row(ay)
            if r:
                ex = r[0] - 1 if side < 0 else r[1] + 1
                g.rect(min(ex, ex + side * (k - 1)), ay, max(ex, ex + side * (k - 1)), ay + k, pal["shade"])


# ---------------------------------------------------------------- faces

EYE_SIZE = [1, 1, 2, 2, 3, 3, 4, 4]
MOUTH_W = [2, 2, 3, 4, 5, 6, 7, 8]


def draw_eye(g, x, y, size, kind, mood, side, pal):
    """x, y is the top-left of the eye box."""
    x, y = int(round(x)), int(round(y))
    ink = INK
    glow = kind == "three"
    if glow:
        ink = RED if mood == "feral" else pal["feat"]
    if mood in ("blink", "sleep"):
        g.rect(x, y + size - 1, x + size - 1, y + size - 1, ink)
        return
    if mood == "happy":
        if size == 1:
            g.set(x, y, ink)
            g.set(x - 1, y + 1, ink)
            g.set(x + 1, y + 1, ink)
        else:
            for i in range(size + 1):
                dy = abs(i - size / 2)
                g.set(x + i - 0, y + int(round(dy * 0.9)), ink)
        return
    if mood == "stone":
        g.rect(x, y, x + size - 1, y + max(1, size) - 1 + (1 if size == 1 else 0), rgb("#8E94A0"))
        return
    colour = RED if mood == "feral" and not glow else ink
    if size == 1:
        g.rect(x, y, x, y + 1, colour)
    else:
        g.rect(x, y, x + size - 1, y + size - 1, colour)
        if size >= 4:                                   # round the corners
            for (cx, cy) in ((x, y), (x + size - 1, y), (x, y + size - 1), (x + size - 1, y + size - 1)):
                g.set(cx, cy, None)
        if mood == "feral" and not glow:
            g.rect(x + (size - 1 if side < 0 else 0), y + size // 2, x + (size - 1 if side < 0 else 0), y + size - 1, INK)
        elif not glow:
            g.set(x + (0 if size < 4 else 1), y + (0 if size < 4 else 1), WHITE)
    # lids and brows
    if kind in ("lid", "sleepy", "smug") and mood == "idle" and size >= 2:
        g.rect(x, y, x + size - 1, y + (size // 2 - 1 if kind != "sleepy" else size // 2), pal["shade"])
        if kind == "smug":
            g.rect(x, y + size // 2 - 1, x + size - 1, y + size // 2 - 1, INK)
    if mood == "hungry":
        outer = x - 1 if side < 0 else x + size
        inner = x + size - 1 if side < 0 else x
        g.line(inner, y - 2, outer, y - 1, INK)
    if mood == "feral":
        outer = x - 1 if side < 0 else x + size
        inner = x + size - 1 if side < 0 else x
        g.line(outer, y - 2, inner, y - 1, INK)


def draw_face(g, sp, body, s, mood, pal):
    cfg = SPECIES[sp]
    kind = cfg["eyes"]
    fx, ey, my = body.face_centre()
    size = EYE_SIZE[s - 1]
    head_w = body.w if body.family != "wyrm" else max(4, body.w * 0.6)
    gap = max(1.0, head_w * 0.17)
    ey = int(round(ey - size / 2))

    if kind == "cyclops":
        big = size + (1 if s < 5 else 2)
        x = fx - big / 2
        if mood in ("blink", "sleep", "happy", "stone"):
            draw_eye(g, x, ey, big, "dot", mood, 1, pal)
        else:
            g.rect(x, ey, x + big - 1, ey + big - 1, WHITE)
            if big >= 4:
                for (cx, cy) in ((x, ey), (x + big - 1, ey), (x, ey + big - 1), (x + big - 1, ey + big - 1)):
                    g.set(cx, cy, pal["base"])
            pup = max(1, big // 2)
            px = x + (big - pup) / 2
            py = ey + (big - pup) / 2 + (0.5 if mood == "hungry" else 0)
            g.rect(px, py, px + pup - 1, py + pup - 1, RED if mood == "feral" else INK)
            if mood == "feral":
                g.line(x - 1, ey - 2, x + big, ey - 1, INK)
            if mood == "hungry":
                g.line(x - 1, ey - 1, x + big, ey - 2, INK)
    elif kind == "three":
        size = max(1, size - 1)
        gap = max(1.0, gap * 0.8)
        draw_eye(g, fx - gap - size, ey, size, kind, mood, -1, pal)
        draw_eye(g, fx + gap, ey, size, kind, mood, 1, pal)
        if s >= 2:
            draw_eye(g, fx - size / 2, ey - size - 1, size, kind, mood, 1, pal)
    else:
        if kind == "big":
            size = min(5, size + 1)
            ey -= 0
        draw_eye(g, fx - gap - size, ey, size, kind, mood, -1, pal)
        draw_eye(g, fx + gap, ey, size, kind, mood, 1, pal)

    # cheeks
    if mood in ("idle", "blink", "happy") and sp in ("mossback", "puddlewump", "fuzzlet", "sunmaw") and s >= 3:
        cy = ey + size + 0
        g.set(int(fx - gap - size - 1), cy, PINK)
        g.set(int(fx + gap + size), cy, PINK)

    # mouth
    mw = MOUTH_W[s - 1]
    if body.family == "wyrm":
        mw = max(2, mw - 2)
    mk = cfg["mouth"]
    my = int(round(my))
    mx = int(round(fx - mw / 2))
    if mood == "stone":
        g.rect(mx, my, mx + mw - 1, my, rgb("#6B717D"))
    elif mood == "feral":
        hh = 1 if s < 3 else (2 if s < 6 else 3)
        g.rect(mx - 1, my, mx + mw, my + hh, INK)
        for i in range(mw + 2):
            if i % 2 == 0:
                g.set(mx - 1 + i, my, WHITE)
            elif hh >= 2:
                g.set(mx - 1 + i, my + hh, WHITE)
    elif mood == "happy":
        hh = 1 if s < 4 else 2
        g.rect(mx, my, mx + mw - 1, my + hh, INK)
        g.rect(mx + 1 if mw > 2 else mx, my + hh, mx + mw - 2 if mw > 2 else mx + mw - 1, my + hh, PINK)
    elif mood == "hungry":
        g.rect(mx, my, mx + mw - 1, my, INK)
        if mw >= 3:
            g.set(mx, my, None if False else pal["base"])
            g.set(mx + mw - 1, my, pal["base"])
            g.set(mx, my + 1, INK)
            g.set(mx + mw - 1, my + 1, INK)
    elif mk == "none":
        pass
    elif mood == "sleep":
        g.rect(mx + mw // 2 - 1 if mw > 2 else mx, my, mx + mw // 2, my, INK)
    else:
        if mk == "flat":
            g.rect(mx, my, mx + mw - 1, my, INK)
        else:
            g.rect(mx, my + (1 if mw >= 3 else 0), mx + mw - 1, my + (1 if mw >= 3 else 0), INK)
            if mw >= 3:
                g.set(mx, my + 1, pal["base"])
                g.set(mx + mw - 1, my + 1, pal["base"])
                g.set(mx, my, INK)
                g.set(mx + mw - 1, my, INK)
        base_y = my + (1 if (mw >= 3 and mk != "flat") else 0)
        if mk == "fangs" and s >= 2:
            g.set(mx, base_y + 1, WHITE)
            g.set(mx + mw - 1, base_y + 1, WHITE)
            if s >= 6:
                g.set(mx, base_y + 2, WHITE)
                g.set(mx + mw - 1, base_y + 2, WHITE)
        if mk == "tooth":
            g.set(mx + mw // 2, base_y + 1, WHITE)
        if mk == "underbite":
            g.set(mx, base_y - 1, WHITE)
            g.set(mx + mw - 1, base_y - 1, WHITE)
        if s >= 6 and mk in ("smile", "flat") and mw >= 5:      # big ones show a little tooth
            g.set(mx + 1, base_y + 1, WHITE)
            g.set(mx + mw - 2, base_y + 1, WHITE)
        if mk == "grin" and mw >= 3:
            for i in range(1, mw - 1, 2):
                g.set(mx + i, base_y - 1 if mw < 5 else base_y, WHITE)

    # extras
    if mood == "hungry":
        sx = int(body.right) + 1
        sy = int(body.top + body.h * 0.2)
        g.set(sx, sy, SWEAT)
        g.set(sx, sy + 1, SWEAT)
    if mood == "sleep":
        zx = int(min(N - 5, body.right + 1))
        zy = int(max(4, body.top - 1))
        z = pal["light"] if sp != "voidling" else pal["feat"]
        g.rect(zx, zy, zx + 2, zy, z)
        g.set(zx + 1, zy + 1, z)
        g.rect(zx, zy + 2, zx + 2, zy + 2, z)
        if zy - 4 >= 0:
            g.rect(zx + 2, zy - 4, zx + 3, zy - 4, z)
            g.rect(zx + 2, zy - 3, zx + 3, zy - 3, z)


# ---------------------------------------------------------------- recolours

def lum(c):
    return (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255.0


def to_stone(g):
    lo, hi = rgb("#59606B"), rgb("#D2D6DE")
    for y in range(N):
        for x in range(N):
            c = g.px[y][x]
            if c is not None:
                g.px[y][x] = mix(lo, hi, lum(c))


def to_feral(g, pal):
    keep = {RED, WHITE, INK}
    for y in range(N):
        for x in range(N):
            c = g.px[y][x]
            if c is not None and c not in keep:
                d = mix(c, rgb("#3A0F2A"), 0.28)
                g.px[y][x] = d


def elder(g, sp, body, s, pal):
    """Marks every species earns late: stripes from Colossus, sparks around a Titan."""
    if s >= 7 and body.family != "wyrm" and sp not in ("voidling", "gravelkin"):
        for side in (-1, 1):
            for i in range(2 + (s - 7)):
                y = int(body.top + body.h * (0.42 + 0.13 * i))
                r = body.row(y)
                if not r:
                    continue
                x = r[0] if side < 0 else r[1]
                for j in range(2 + (1 if s == 8 else 0)):
                    if (x - side * j, y) in body.mask:
                        g.set(x - side * j, y, pal["shade"] if g.get(x - side * j, y) != pal["shade"] else pal["outline"])
    if s == 8 and sp not in ("voidling", "frostnip", "sunmaw"):
        spark = pal["feat2"] if pal["feat2"] != pal["base"] else pal["light"]
        for (dx, dy) in ((-2, 0.18), (1, 0.05), (-1, 0.62), (2, 0.5)):
            x = int(body.left + dx) if dx < 0 else int(body.right + dx - 1)
            y = int(body.top + body.h * dy)
            if 0 <= x < N and g.get(x, y) is None:
                g.set(x, y, spark)


def make(sp, s, mood):
    cfg = SPECIES[sp]
    pal = cfg["pal"]
    body = Body(cfg["family"], s)
    g = Grid()
    behind(g, sp, body, s, pal)
    paint_body(g, sp, body, s, pal)
    elder(g, sp, body, s, pal)
    on_top(g, sp, body, s, pal)
    if mood == "feral":
        to_feral(g, pal)
    draw_face(g, sp, body, s, mood, pal)
    if mood == "stone":
        to_stone(g)
        # cracks
        for (u, v, du, dv) in ((0.3, 0.3, 0.12, 0.2), (0.68, 0.55, -0.1, 0.22)):
            x0, y0 = body.left + body.w * u, body.top + body.h * v
            for i in range(max(2, s)):
                x = int(x0 + body.w * du * i / max(2, s))
                y = int(y0 + body.h * dv * i / max(2, s))
                if (x, y) in body.mask:
                    g.set(x, y, rgb("#4A505B"))
        g.outline(rgb("#33383F"))
    else:
        g.outline(pal["outline"])
    return g.image()


# ---------------------------------------------------------------- eggs and icons

EGGS = {
    "common":    ("#FFF3D6", "#ECD3A0", "#8BC34A"),
    "rare":      ("#CFE8FF", "#9FC0EA", "#3B6CF6"),
    "epic":      ("#E3D2FF", "#B79CF0", "#8A4DFF"),
    "legendary": ("#FFE9A0", "#F0C24A", "#FF7A1A"),
}


def make_egg(rarity, cracks):
    base, shade, mark = (rgb(c) for c in EGGS[rarity])
    g = Grid()
    cx, cy = CX, 18.5
    g.ellipse(cx, cy + 2, 8.5, 9, base)
    g.ellipse(cx, cy - 2, 7, 9, base)
    for y in range(N):
        for x in range(N):
            if g.px[y][x] is not None:
                u = (x - 7) / 18.0
                v = (y - 7) / 22.0
                if u + v > 1.25 or v > 0.9:
                    g.px[y][x] = shade
    marks = {
        "common": [(12, 14), (13, 14), (19, 19), (20, 19), (20, 20), (11, 22), (16, 11)],
        "rare": [(x, 15 + (x % 2)) for x in range(9, 24)] + [(x, 21 + (x % 2)) for x in range(8, 24)],
        "epic": [(12, 13), (19, 12), (15, 18), (21, 20), (10, 21), (17, 24), (13, 12), (12, 14), (11, 13)],
        "legendary": [(x, 16) for x in range(9, 24)] + [(x, 17) for x in range(8, 24, 2)] + [(16, 11), (15, 12), (17, 12), (16, 13), (16, 22), (15, 23), (17, 23), (16, 24)],
    }[rarity]
    for (x, y) in marks:
        if g.get(x, y) is not None:
            g.set(x, y, mark)
    g.set(12, 11, WHITE)
    g.set(11, 12, WHITE)
    crack_paths = [
        [(16, 9), (15, 11), (17, 13), (15, 15), (16, 17)],
        [(22, 14), (20, 15), (21, 17), (19, 19)],
        [(9, 18), (11, 19), (10, 21), (12, 23), (11, 25)],
    ]
    for path in crack_paths[:cracks]:
        for a, b in zip(path, path[1:]):
            g.line(a[0], a[1], b[0], b[1], INK)
    g.outline(INK)
    return g.image()


def icon(name):
    g = Grid()
    if name == "dawn":          # a warm bun
        g.ellipse(16, 18, 9, 7, rgb("#FFC9A0"))
        g.ellipse(16, 21, 9, 4, rgb("#F29E6E"))
        for (x, y) in ((12, 15), (16, 14), (20, 15)):
            g.set(x, y, WHITE)
    elif name == "day":         # a drumstick
        g.ellipse(14, 15, 7, 6, rgb("#E8893A"))
        g.ellipse(13, 13, 3, 2, rgb("#FFB86B"))
        g.line(19, 19, 24, 24, WHITE)
        g.line(20, 19, 25, 24, WHITE)
        g.rect(24, 24, 26, 26, WHITE)
    elif name == "dusk":        # berries
        g.ellipse(12, 19, 5, 5, rgb("#9B5DE5"))
        g.ellipse(20, 19, 5, 5, rgb("#7B3FC4"))
        g.ellipse(16, 13, 5, 5, rgb("#B07CFF"))
        g.set(10, 17, WHITE)
        g.set(14, 11, WHITE)
        g.line(16, 8, 18, 5, rgb("#3F8F3F"))
    elif name == "midnight":    # a moon cookie
        g.ellipse(16, 17, 9, 9, rgb("#3B4A8F"))
        g.ellipse(19, 14, 7, 7, None)
        for (x, y) in ((11, 20), (13, 15), (15, 22)):
            g.set(x, y, rgb("#FFF0A0"))
        g.set(23, 9, rgb("#FFF0A0"))
        g.set(25, 14, rgb("#FFF0A0"))
    elif name == "gem":
        g.tri(8, 13, 16, 27, 24, 13, rgb("#3BD6C6"))
        g.rect(11, 9, 20, 12, rgb("#7FF0E4"))
        g.tri(8, 13, 11, 9, 11, 13, rgb("#7FF0E4"))
        g.tri(24, 13, 20, 9, 20, 13, rgb("#2AA898"))
        g.tri(16, 27, 20, 13, 24, 13, rgb("#2AA898"))
        g.set(12, 10, WHITE)
        g.set(13, 10, WHITE)
    elif name == "flame":
        g.tri(9, 24, 16, 5, 23, 24, rgb("#FF7A1A"))
        g.ellipse(16, 22, 7, 5, rgb("#FF7A1A"))
        g.tri(12, 25, 16, 13, 20, 25, rgb("#FFD23F"))
        g.ellipse(16, 23, 4, 3, rgb("#FFD23F"))
    elif name == "chow":        # a generic bowl of chow
        g.ellipse(16, 16, 9, 4, rgb("#C98B4A"))
        g.ellipse(16, 21, 10, 6, rgb("#8E5BE8"))
        g.rect(6, 15, 25, 19, None)
        g.ellipse(16, 17, 9, 3, rgb("#E8A45A"))
        for (x, y) in ((12, 16), (16, 15), (20, 16), (14, 17), (18, 17)):
            g.set(x, y, rgb("#FFD98A"))
        g.ellipse(16, 22, 10, 5, rgb("#8E5BE8"))
        g.rect(6, 17, 25, 19, None)
        g.ellipse(16, 18, 9, 2.5, rgb("#E8A45A"))
        for (x, y) in ((12, 17), (16, 16), (20, 17)):
            g.set(x, y, rgb("#FFD98A"))
    g.outline(INK)
    return g.image()


ICONS = ["dawn", "day", "dusk", "midnight", "gem", "flame", "chow"]


# ---------------------------------------------------------------- output

def sheet(sp):
    im = Image.new("RGBA", (N * len(VARIANTS), N * STAGES), (0, 0, 0, 0))
    for r in range(STAGES):
        for c, mood in enumerate(VARIANTS):
            im.paste(make(sp, r + 1, mood), (c * N, r * N))
    return im


def write_launcher_icon(sp, stage, mood):
    """The adaptive icon foreground: one monster as a vector, pixel for pixel."""
    im = make(sp, stage, mood)
    box = im.getbbox()
    w, h = box[2] - box[0], box[3] - box[1]
    scale = 60 // max(w, h)
    ox = (108 - w * scale) / 2.0
    oy = (108 - h * scale) / 2.0
    by_colour = {}
    for y in range(box[1], box[3]):
        x = box[0]
        while x < box[2]:
            px = im.getpixel((x, y))
            if px[3] == 0:
                x += 1
                continue
            run = 1
            while x + run < box[2] and im.getpixel((x + run, y)) == px:
                run += 1
            by_colour.setdefault(px[:3], []).append((x - box[0], y - box[1], run))
            x += run
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Written by art/generate.py. Do not edit by hand. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for colour, runs in sorted(by_colour.items()):
        d = "".join("M%g,%gh%gv%gh-%gz" % (ox + x * scale, oy + y * scale, n * scale, scale, n * scale) for (x, y, n) in runs)
        out.append('    <path android:fillColor="#%02X%02X%02X" android:pathData="%s" />' % (colour[0], colour[1], colour[2], d))
    out.append('</vector>')
    path = os.path.join(HERE, "..", "android", "app", "src", "main", "res", "drawable", "ic_launcher_fg.xml")
    with open(path, "w") as f:
        f.write("\n".join(out) + "\n")


def main():
    os.makedirs(OUT, exist_ok=True)
    for sp in ORDER:
        sheet(sp).save(os.path.join(OUT, sp + ".png"))
    eggs = Image.new("RGBA", (N * 4, N * 4), (0, 0, 0, 0))
    for r, rarity in enumerate(EGGS):
        for c in range(4):
            eggs.paste(make_egg(rarity, c), (c * N, r * N))
    eggs.save(os.path.join(OUT, "eggs.png"))
    icons = Image.new("RGBA", (N * len(ICONS), N), (0, 0, 0, 0))
    for i, name in enumerate(ICONS):
        icons.paste(icon(name), (i * N, 0))
    icons.save(os.path.join(OUT, "icons.png"))

    write_launcher_icon("mossback", 6, "happy")

    if "--preview" in sys.argv:
        os.makedirs(PREVIEW, exist_ok=True)
        scale = 4
        bg = (251, 246, 234, 255)
        # evolution lines: every species, idle, all 8 stages
        line = Image.new("RGBA", (N * STAGES * scale, N * len(ORDER) * scale), bg)
        for r, sp in enumerate(ORDER):
            for s in range(STAGES):
                cell = make(sp, s + 1, "idle").resize((N * scale, N * scale), Image.NEAREST)
                line.paste(cell, (s * N * scale, r * N * scale), cell)
        line.save(os.path.join(PREVIEW, "evolution.png"))
        # moods at stage 3, 5 and 8
        for st in (3, 5, 8):
            moods = Image.new("RGBA", (N * len(VARIANTS) * scale, N * len(ORDER) * scale), bg)
            for r, sp in enumerate(ORDER):
                for c, mood in enumerate(VARIANTS):
                    cell = make(sp, st, mood).resize((N * scale, N * scale), Image.NEAREST)
                    moods.paste(cell, (c * N * scale, r * N * scale), cell)
            moods.save(os.path.join(PREVIEW, f"moods_stage{st}.png"))
        misc = Image.new("RGBA", (N * 7 * scale, N * 5 * scale), bg)
        e = eggs.resize((eggs.width * scale, eggs.height * scale), Image.NEAREST)
        misc.paste(e, (0, 0), e)
        i2 = icons.resize((icons.width * scale, icons.height * scale), Image.NEAREST)
        misc.paste(i2, (0, N * 4 * scale), i2)
        misc.save(os.path.join(PREVIEW, "eggs_icons.png"))
    print("wrote", len(ORDER), "species sheets, eggs, icons to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
