"""Writes the audio-techtech AT-LPA2 turntable's models and texture.

Every model is in the turntable's own space, in sixteenths of its block: x across from its left as one faces its
front, y up, z from its back to its front (its front is the block's south side). TurntableLayout holds the same
numbers for the game. One 256x256 texture holds every material; a face's uv is eight texels per sixteenth, from the
corner of its material. Clear acrylic faces carry tintindex 0, so the renderer draws only them translucent.

Run from the repository root: python tools/at_lpa2/gen_at_lpa2.py
"""
import json
import math
import os
import random

from PIL import Image

ASSETS = "src/main/resources/assets/create_media_systems"
TEXTURE = "create_media_systems:block/turntable/at_lpa2"
MODELS = ASSETS + "/models/block/turntable/at_lpa2"

# Materials: where each one's 16x16 texels start in the texture (in texels).
ACRYL, ACRYL_EDGE, ALU, BLACK = (0, 0), (16, 0), (32, 0), (48, 0)
CARBON, PLATE, BUTTON, HEADSHELL = (0, 16), (16, 16), (32, 16), (48, 16)
LAMP_OFF, LAMP_ON, CART = (0, 32), (16, 32), (32, 32)
GLASS = {ACRYL, ACRYL_EDGE}


S = 4  # texels of the texture per texel of the 64x64 grid the models' uvs are in
RECORD, LABEL = 512, 128  # the record's and its label's own square textures, in texels across


def texture():
    rnd = random.Random(7)
    n = 16 * S
    img = Image.new("RGBA", (64 * S, 64 * S), (0, 0, 0, 0))
    px = img.load()

    def fill(at, colour):
        for y in range(n):
            for x in range(n):
                px[at[0] * S + x, at[1] * S + y] = tuple(int(max(0, min(255, c))) for c in colour(x, y))

    def disc(x, y, cx, cy, r):
        """How far inside a circle (0 outside, 1 well inside), smoothed over a texel."""
        return max(0.0, min(1.0, r - math.hypot(x + .5 - cx, y + .5 - cy) + .5))

    # Clear acrylic: faint, a little blue, with soft diagonal streaks; its cut edges show green-white and thick,
    # in the fine layers of a polished edge.
    fill(ACRYL, lambda x, y: (226, 239, 246, 48 + 14 * (math.sin((x + y) * .21) > .92)))
    fill(ACRYL_EDGE, lambda x, y: (204, 232, 226, 150 + 24 * (y % 6 == 0) - 10 * (y % 6 == 3)))
    # Brushed aluminium: light grey with fine horizontal lines.
    rows = [rnd.randint(-9, 9) for _ in range(n)]
    fill(ALU, lambda x, y: (201 + rows[y] + rnd.randint(-2, 2), 204 + rows[y], 209 + rows[y], 255))
    fill(BLACK, lambda x, y: (21 + rnd.randint(0, 5), 21 + rnd.randint(0, 5), 24 + rnd.randint(0, 5), 255))

    # Carbon fibre twill: 2x2 weave of 4-texel tows, each lit along its length.
    def carbon(x, y):
        cell = ((x // 4) + (y // 4)) % 4 < 2
        along = (y % 4) if cell else (x % 4)
        shade = 30 + (40 if cell else 18) * math.sin(math.pi * (along + .5) / 4)
        return (shade, shade, shade + 4, 255)
    fill(CARBON, carbon)

    # The square plate under the platter: aluminium with round holes, a dark boss in its middle.
    def plate(x, y):
        hole = max(disc(x % 20, y % 20, 10, 10, 2.6), disc(x, y, 32, 32, 7))
        base = 186 + rows[y] // 2
        return tuple(base * (1 - hole) + 58 * hole for _ in range(3)) + (255,)
    fill(PLATE, plate)

    # A key: a light face bevelled into a darker ring.
    def button(x, y):
        edge = min(x, y, n - 1 - x, n - 1 - y)
        shade = 150 if edge < 4 else 200 + min(edge, 14) * 2 - (y - 32) * .3
        return (shade, shade + 2, shade + 6, 255)
    fill(BUTTON, button)
    fill(HEADSHELL, lambda x, y: (226 + 18 * math.sin(y * .18), 228 + 18 * math.sin(y * .18), 234 + 16 * math.sin(y * .18), 255))
    fill(LAMP_OFF, lambda x, y: (42 + 14 * disc(x, y, 32, 32, 22), 46 + 14 * disc(x, y, 32, 32, 22), 52 + 16 * disc(x, y, 32, 32, 22), 255))
    fill(LAMP_ON, lambda x, y: (70 + 120 * disc(x, y, 32, 32, 20), 150 + 90 * disc(x, y, 32, 32, 20), 230 + 25 * disc(x, y, 32, 32, 20), 255))
    fill(CART, lambda x, y: (36, 36, 40, 255) if y < 48 else (190, 160, 70, 255))
    os.makedirs(ASSETS + "/textures/block/turntable", exist_ok=True)
    img.save(ASSETS + "/textures/block/turntable/at_lpa2.png")
    record()


def record():
    """A record's vinyl, seen from above, filling its square: grooves from its lead-in to its run-out with the gaps
    between tracks, a glossy rim and run-out; and its label's paper, white, which the renderer tints the colour of the
    disc's label. Radii here are fractions of the record's (TurntableLayout.RECORD_RADIUS)."""
    rnd = random.Random(11)
    lead_in, run_out, label = 5.2 / 5.45, 2.2 / 5.45, 1.82 / 5.45
    gaps = [run_out + (lead_in - run_out) * f for f in (.18, .41, .6, .83)]
    rings = [rnd.uniform(-1, 1) for _ in range(RECORD)]
    img = Image.new("RGBA", (RECORD, RECORD))
    px = img.load()
    half = RECORD / 2
    for y in range(RECORD):
        for x in range(RECORD):
            r = math.hypot(x + .5 - half, y + .5 - half) / half
            shade = 17.0
            if run_out < r < lead_in:
                shade = 25 + 5 * rings[int(r * half * 2) % RECORD] + 4 * math.sin(r * half * 2.6)
                if any(abs(r - g) < 1.6 / half for g in gaps):
                    shade = 15
            elif r >= lead_in:
                shade = 30 if r < .995 else 45
            elif r > label:
                shade = 21 + 3 * math.sin(r * half * .9)
            px[x, y] = (int(shade), int(shade), int(shade + 2), 255)
    img.save(ASSETS + "/textures/block/turntable/record.png")

    img = Image.new("RGBA", (LABEL, LABEL))
    px = img.load()
    half = LABEL / 2
    for y in range(LABEL):
        for x in range(LABEL):
            r = math.hypot(x + .5 - half, y + .5 - half) / half
            a = math.atan2(y + .5 - half, x + .5 - half)
            shade = 238
            if abs(r - .9) < .025 or abs(r - .3) < .02:
                shade = 175
            elif .5 < r < .78 and -2.4 < a < -.7 and int(r * 40) % 3 == 0:
                shade = 150  # print on the label: a few lines of text
            elif .45 < r < .58 and .5 < a < 2.6:
                shade = 120  # and a brand mark under the spindle
            px[x, y] = (shade, shade, shade, 255)
    img.save(ASSETS + "/textures/block/turntable/record_label.png")


def box(lo, hi, material, rotate=None, faces=None):
    """One element from lo to hi; rotate is (angle about y, origin) for round parts."""
    size = [hi[i] - lo[i] for i in range(3)]
    dims = {"north": (size[0], size[1]), "south": (size[0], size[1]), "east": (size[2], size[1]),
            "west": (size[2], size[1]), "up": (size[0], size[2]), "down": (size[0], size[2])}
    out = {}
    for face, (w, h) in dims.items():
        if faces is not None and face not in faces:
            continue
        mat = material if not (material == ACRYL and face not in ("up", "down")) else ACRYL_EDGE
        u0, v0 = mat[0] / 4, mat[1] / 4
        uw, vh = min(w * 2, 16) / 4, min(h * 2, 16) / 4
        f = {"uv": [u0, v0, u0 + max(uw, .25), v0 + max(vh, .25)], "texture": "#0"}
        if mat in GLASS:
            f["tintindex"] = 0
        out[face] = f
    element = {"from": [round(v, 4) for v in lo], "to": [round(v, 4) for v in hi], "faces": out}
    if rotate:
        element["rotation"] = {"angle": rotate[0], "axis": "y", "origin": rotate[1]}
    return element


def round_post(cx, cz, r, y0, y1, material):
    """A short round post: a square turned 0, 22.5, 45 and -22.5 degrees, sixteen sides."""
    lo, hi = [cx - r, y0, cz - r], [cx + r, y1, cz + r]
    return [box(lo, hi, material)] + [box(lo, hi, material, (a, [cx, y0, cz])) for a in (22.5, 45, -22.5)]


def model(name, elements):
    os.makedirs(MODELS, exist_ok=True)
    data = {"textures": {"0": TEXTURE, "particle": TEXTURE}, "elements": elements}
    with open(f"{MODELS}/{name}.json", "w", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


# The round posts: x, z, radius, bottom, top, material. The renderer draws them smooth (TurntableRenderer.POSTS has
# the same numbers); the item's model has them as boxes. They are the four feet with their rubber rings, the
# platter's bearing (inside the clear plinth), the motor and its pulley, the tonearm's pillar, and the spindle.
POSTS = [(x, z, .9, 0, 1.25, ALU) for x, z in ((2, 2), (14, 2), (2, 11), (14, 11))] \
    + [(x, z, .95, .9, 1.05, BLACK) for x, z in ((2, 2), (14, 2), (2, 11), (14, 11))] \
    + [(6.5, 6.5, .8, 1.3, 2.5, ALU), (1.9, 11.1, .7, 1.3, 2.5, ALU), (1.9, 11.1, .45, 2.5, 3.45, ALU),
       (13.2, 3.7, .9, 2.5, 3.0, ALU), (13.2, 3.7, .4, 3.0, 3.95, ALU), (6.5, 6.5, .25, 2.72, 4.3, ALU)]


def posts():
    return [b for x, z, r, y0, y1, mat in POSTS for b in round_post(x, z, r, y0, y1, mat)]


def base():
    e = [box([0.5, 1.25, 0.5], [15.5, 2.5, 12.5], ACRYL)]
    # Inside the clear plinth: the rotation sensor under the platter's bearing.
    e.append(box([7.5, 1.35, 6.1], [8.7, 1.55, 6.9], CART))
    # The belt from the motor's pulley to the platter's rim (at 45 degrees).
    for side in (-1, 1):
        ox, oz = 1.9 + .55 + side * .3, 11.1 - .55 + side * .3
        e.append(box([ox - .06, 3.1, oz - .5], [ox + .06, 3.4, oz + .5], BLACK, (-45, [ox, 3.1, oz])))
    # The tonearm's rest, and the lift lever's post.
    e.append(box([12.95, 2.5, 9.0], [13.45, 4.4, 9.5], ALU))
    e.append(box([12.75, 4.4, 8.9], [13.65, 4.5, 9.6], BLACK))
    e.append(box([14.75, 2.5, 4.95], [15.25, 3.55, 5.45], ALU))
    # The dust cover's hinge seats on the back of the plinth.
    for x in (2.5, 12.0):
        e.append(box([x, 2.5, 0.5], [x + 1.5, 3.3, 1.2], BLACK))
    # The control unit before the plinth: four keys on its top (power, 33, 45, start/stop) and three lamps.
    e.append(box([9.4, 0, 13.0], [15.6, 2.0, 15.8], ALU))
    e.append(box([9.6, 2.0, 13.2], [15.4, 2.05, 15.6], BLACK, faces=["up"]))
    for i in range(4):
        cx = 9.4 + 1.55 * i + .775
        e.append(box([cx - .45, 2.0, 14.3], [cx + .45, 2.3, 15.2], BUTTON))
        if i < 3:
            e.append(box([cx - .25, 2.0, 13.5], [cx + .25, 2.12, 14.0], LAMP_OFF))
    return e


def hub():
    return [box([4.3, 2.72, 4.3], [8.7, 2.88, 8.7], PLATE)]


def arm():
    e = [box([12.75, 3.9, 3.25], [13.65, 4.7, 4.15], ALU),
         box([13.0, 4.15, 4.15], [13.4, 4.55, 10.7], CARBON),
         box([13.05, 4.2, 1.9], [13.35, 4.5, 3.25], ALU),
         box([12.6, 3.95, 1.2], [13.8, 4.75, 2.3], ALU),
         box([12.55, 3.9, 1.9], [13.85, 4.8, 2.1], BLACK),
         box([12.85, 3.95, 10.6], [13.55, 4.1, 12.0], HEADSHELL),
         box([13.55, 4.0, 11.2], [14.15, 4.06, 11.4], HEADSHELL),
         box([12.95, 3.72, 11.0], [13.45, 3.95, 11.9], CART),
         box([13.15, 3.66, 11.6], [13.25, 3.72, 11.8], ALU)]
    return e


def cue():
    return [box([14.85, 3.45, 5.2], [15.15, 3.65, 7.0], ALU), box([14.8, 3.4, 6.7], [15.2, 3.7, 7.1], BLACK)]


def cover():
    e = [box([0.5, 5.75, 0.5], [15.5, 6.0, 12.5], ACRYL),
         box([0.5, 2.5, 12.25], [15.5, 5.75, 12.5], ACRYL_EDGE),
         box([0.5, 2.5, 0.5], [15.5, 5.75, 0.75], ACRYL_EDGE),
         box([0.5, 2.5, 0.75], [0.75, 5.75, 12.25], ACRYL_EDGE),
         box([15.25, 2.5, 0.75], [15.5, 5.75, 12.25], ACRYL_EDGE)]
    for x in (2.5, 12.0):
        e.append(box([x, 3.3, 0.25], [x + 1.5, 4.8, 0.75], BLACK))
    return e


def lamp():
    return [box([-.25, 2.12, -.25], [.25, 2.14, .25], LAMP_ON, faces=["up"])]


def platter_item():
    """The platter as the item shows it: the renderer draws the game's round one."""
    e = []
    for hx, hz in ((5.4, 1.2), (4.8, 2.6), (3.8, 3.8), (2.6, 4.8), (1.2, 5.4)):
        e.append(box([6.5 - hx, 2.9, 6.5 - hz], [6.5 + hx, 3.6, 6.5 + hz], ACRYL))
    return e


def item():
    children = {name: {"parent": f"create_media_systems:block/turntable/at_lpa2/{name}"}
                for name in ("base", "posts", "hub", "arm", "cue", "cover", "platter_item")}
    data = {"parent": "minecraft:block/block", "loader": "neoforge:composite",
            "render_type": "minecraft:translucent", "textures": {"particle": TEXTURE}, "children": children}
    with open(ASSETS + "/models/item/at_lpa2.json", "w", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def main():
    texture()
    for name, build in (("base", base), ("posts", posts), ("hub", hub), ("arm", arm), ("cue", cue), ("cover", cover),
                        ("lamp", lamp), ("platter_item", platter_item)):
        model(name, build())
    with open(f"{MODELS}/particle.json", "w", newline="\n") as f:
        json.dump({"textures": {"particle": TEXTURE}}, f, indent=2)
        f.write("\n")
    item()


if __name__ == "__main__":
    main()
