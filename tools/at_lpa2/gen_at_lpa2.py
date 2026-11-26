"""Writes the audio-techtech AT-LPA2 turntable's textures.

One 512x512 sheet holds every material of the deck. TurntableRenderer builds the deck's shapes and has each
material's place in the sheet under the same name. Nothing in the game shines or refracts, so the light a real deck
shows is painted here: clear acrylic is all but unseen but for a bright line along each of its edges, turned
aluminium has bright and dark bands round it. The record and its label have their own textures.

Run from the repository root: python tools/at_lpa2/gen_at_lpa2.py
"""
import math
import random

from PIL import Image

OUT = "src/main/resources/assets/create_media_systems/textures/block/turntable"
RECORD, LABEL = 512, 128  # the record's and its label's own square textures, in texels across

# Where each material is in the sheet: x, y, width, height (texels). The first row's are drawn with their borders
# kept unstretched (eight texels at each edge), so a line along a border is as wide on a face of any size.
GLOW, EDGE, SATIN, BLACK = (0, 0, 24, 24), (32, 0, 24, 24), (64, 0, 24, 24), (96, 0, 24, 24)
WHITE, IVORY, WIRES, GLINT = (128, 0, 24, 24), (160, 0, 24, 24), (192, 0, 24, 24), (224, 0, 8, 8)
# The sides of round parts, once round them from left to right.
TURNED, KNURL, CARBON, SHINE, RUBBER = (0, 32, 64, 32), (64, 32, 64, 32), (128, 32, 64, 32), (192, 32, 64, 8), (192, 48, 64, 8)
# The ends of round parts, and other faces seen whole.
CAP, CAP_BLACK, KEY, PLATE, LAMP = (0, 64, 64, 64), (64, 64, 64, 64), (128, 64, 64, 64), (192, 64, 64, 64), (256, 64, 16, 16)
# The platter's faces, down from the spindle to the rim.
PLATTER, FRONT, LOGO = (0, 128, 8, 128), (128, 128, 256, 56), (128, 192, 64, 8)

# The control unit's front, 8.1 by 1.8 sixteenths: its keys' middles and radii (texels across and down from its top
# left corner), and what is printed under each. TurntableRenderer has the keys themselves.
UNIT_WIDTH, UNIT_HEIGHT = 8.1, 1.8
KEYS = [(.95, .42, "POWER"), (2.75, .27, "33"), (4.45, .27, "45"), (7.3, .42, "START/STOP")]
LEDS = 3  # the first keys have a lamp over them

FONT = {
    "a": ("...", ".##", "#.#", "#.#", ".##"), "u": ("...", "#.#", "#.#", "#.#", ".##"),
    "d": ("..#", ".##", "#.#", "#.#", ".##"), "i": (".#.", "...", ".#.", ".#.", ".#."),
    "o": ("...", ".#.", "#.#", "#.#", ".#."), "-": ("...", "...", "###", "...", "..."),
    "t": (".#.", "###", ".#.", ".#.", "..#"), "e": ("...", ".#.", "###", "#..", ".##"),
    "c": ("...", ".##", "#..", "#..", ".##"), "h": ("#..", "#..", "##.", "#.#", "#.#"),
    "P": ("##.", "#.#", "##.", "#..", "#.."), "O": (".#.", "#.#", "#.#", "#.#", ".#."),
    "W": ("#.#", "#.#", "#.#", "###", "#.#"), "E": ("###", "#..", "##.", "#..", "###"),
    "R": ("##.", "#.#", "##.", "#.#", "#.#"), "S": (".##", "#..", ".#.", "..#", "##."),
    "T": ("###", ".#.", ".#.", ".#.", ".#."), "A": (".#.", "#.#", "###", "#.#", "#.#"),
    "/": ("..#", "..#", ".#.", "#..", "#.."), "3": ("##.", "..#", ".#.", "..#", "##."),
    "4": ("#.#", "#.#", "###", "..#", "..#"), "5": ("###", "#..", "##.", "..#", "##."),
}
BRAND = "audio-techtech"


def glint(angle, at, width):
    """A highlight round a cylinder: 1 at `at` degrees, fading over `width` degrees either side."""
    d = (angle - at + 180) % 360 - 180
    return math.exp(-(d / width) ** 2)


def sheet():
    rnd = random.Random(7)
    img = Image.new("RGBA", (512, 512), (255, 255, 255, 0))
    px = img.load()

    def paint(tile, colour):
        for y in range(tile[3]):
            for x in range(tile[2]):
                px[tile[0] + x, tile[1] + y] = tuple(int(max(0, min(255, c))) for c in colour(x, y))

    def grey(v, blue=4):
        return (v, v + 1, v + blue, 255)

    def inset(tile, x, y):
        """How many texels in from the tile's nearest edge."""
        return min(x, y, tile[2] - 1 - x, tile[3] - 1 - y)

    def text(x, y, string, colour):
        for i, letter in enumerate(string):
            for row, line in enumerate(FONT[letter]):
                for col, dot in enumerate(line):
                    if dot == "#":
                        px[x + 4 * i + col, y + row] = colour

    def ring(cx, cy, r0, r1, colour):
        for y in range(int(cy - r1 - 1), int(cy + r1 + 2)):
            for x in range(int(cx - r1 - 1), int(cx + r1 + 2)):
                if r0 <= math.hypot(x + .5 - cx, y + .5 - cy) < r1:
                    px[x, y] = colour

    # Clear acrylic's faces: a white line along each edge, fading inwards to a faint haze. The game drops what is
    # less than a tenth opaque (26 of 255), so the haze stays above that.
    paint(GLOW, lambda x, y: (246, 250, 252, (215, 120, 70, 46, 36)[inset(GLOW, x, y)] if inset(GLOW, x, y) < 5 else 30))

    # Its cut edges, polished: brighter, a white line along the top and the bottom and at each end, and the dark
    # line and the light one of what is seen inside it.
    def edge(x, y):
        if y in (0, 23) or x in (0, 23):
            return (255, 255, 255, 232)
        if y in (1, 22):
            return (250, 252, 253, 156)
        if y in (8, 9):
            return (168, 180, 187, 124)
        if y == 10:
            return (255, 255, 255, 150)
        return (232, 240, 243, 92)
    paint(EDGE, edge)
    paint(GLINT, lambda x, y: (255, 255, 255, 228))

    # Blasted aluminium: even and fine-grained, lighter above, each face's edges catching the light.
    def satin(x, y):
        d = inset(SATIN, x, y)
        return grey(232 if d == 0 else 196 - (y - 12) * .6 + rnd.randint(-2, 2) + (8 if d == 1 else 0))
    paint(SATIN, satin)
    paint(BLACK, lambda x, y: grey((60, 38)[inset(BLACK, x, y)] if inset(BLACK, x, y) < 2 else 26 + rnd.randint(0, 3), 3))
    paint(WHITE, lambda x, y: grey((246, 214)[inset(WHITE, x, y)] if inset(WHITE, x, y) < 2 else 228, 1))
    paint(IVORY, lambda x, y: (244, 238, 224, 255) if inset(IVORY, x, y) == 0 else (228, 219, 200, 255))
    # The cartridge's four leads, side by side.
    paint(WIRES, lambda x, y: ((200, 40, 40), (236, 236, 236), (40, 150, 70), (40, 80, 200))[x // 6] + (255,))

    # Turned aluminium, round a part: a broad highlight to the front left, a fainter one opposite, dark between,
    # and the lathe's fine lines.
    lathe = [rnd.randint(-3, 3) for _ in range(32)]

    def turned(x, y):
        a = (x + .5) / 64 * 360
        return 138 + 105 * glint(a, 125, 24) + 50 * glint(a, 300, 34) - 48 * glint(a, 215, 40) - 30 * glint(a, 30, 40)
    paint(TURNED, lambda x, y: grey(turned(x, y) + lathe[y]))
    # A foot's knurled side: the same light on upright ridges, between two smooth rims.
    paint(KNURL, lambda x, y: grey(turned(x, y) + 14 if y < 3 or y > 28 else turned(x, y) * .8 + (15 if x % 2 else -15)))
    # The carbon tube: a twill of tows, glossy along its top.
    def carbon(x, y):
        a = (x + .5) / 64 * 360
        tow = ((x // 8) + (y // 2)) % 4 < 2
        return grey((38 if tow else 22) + 120 * glint(a, 90, 13) + 24 * glint(a, 230, 30), 3)
    paint(CARBON, carbon)
    # A bevel's bright line round a metal part, and a black part's side.
    paint(SHINE, lambda x, y: grey(200 + 55 * glint((x + .5) / 64 * 360, 125, 30) + 30 * glint((x + .5) / 64 * 360, 300, 40)
                                   - 40 * glint((x + .5) / 64 * 360, 215, 40)))
    paint(RUBBER, lambda x, y: grey(24 + 46 * glint((x + .5) / 64 * 360, 125, 22) + 16 * glint((x + .5) / 64 * 360, 300, 30), 3))

    # A round part's end, turned: bright and dark quarters about its middle, fine rings, darker towards its rim.
    def spun(base, swing, groove=None):
        def colour(x, y):
            r = math.hypot(x + .5 - 32, y + .5 - 32) / 30
            a = math.atan2(y + .5 - 32, x + .5 - 32)
            v = base + swing * math.cos(2 * (a - math.radians(35))) + swing * .09 * math.sin(r * 51)
            if groove and groove[0] < r < groove[1]:
                v -= 70
            return grey(v - (22 if r > .88 else 0) * swing / 56)
        return colour
    paint(CAP, spun(176, 56))
    paint(CAP_BLACK, spun(34, 12))
    paint(KEY, spun(208, 24, (.62, .7)))

    # The top of the sensor's housing under the spindle: its white rim, the perforated board in it, and the
    # bearing's collar in the middle.
    def plate(x, y):
        d = inset(PLATE, x, y)
        if d < 7:
            return grey(246 if d == 0 else 190 if d == 6 else 228, 1)
        r = math.hypot(x + .5 - 32, y + .5 - 32)
        if r < 10:
            return grey(112 if r > 8.5 else 200 + 36 * math.cos(2 * (math.atan2(y - 31.5, x - 31.5) - .6)))
        hole = (x - 10) % 6 < 2 and (y - 10) % 6 < 2
        return grey(60 if hole else 150 + lathe[y % 32], 7)
    paint(PLATE, plate)

    # A lit lamp.
    def lamp(x, y):
        r = math.hypot(x + .5 - 8, y + .5 - 8) / 7
        return (255, 255, 255, 0) if r > 1 else (235 - 175 * r, 250 - 90 * r, 255, 255)
    paint(LAMP, lamp)

    # The platter's faces, from the spindle (the top) out to the rim (the bottom): clear, the faint ring of the
    # step a label lies over, a haze that grows towards the rim, and the rim's bright line.
    def platter(x, y):
        r = (y + .5) / PLATTER[3]
        if r >= .975:
            alpha = 225
        elif .335 < r < .352:
            alpha = 84
        else:
            t = max(0.0, min(1.0, (r - .88) / .09))
            alpha = 28 + 62 * t * t * (3 - 2 * t)
        return (246, 250, 252, alpha)
    paint(PLATTER, platter)

    # The control unit's front: blasted aluminium, a ring let in round each key, a lamp's dark lens over the
    # first three, each key's name under it and the maker's over the last.
    sx, sy = FRONT[2] / UNIT_WIDTH, FRONT[3] / UNIT_HEIGHT
    paint(FRONT, lambda x, y: grey(240 if y == 0 else 160 if y == FRONT[3] - 1 else 214 - 26 * y / FRONT[3] + rnd.randint(-2, 2)))
    for i, (at, r, name) in enumerate(KEYS):
        cx, cy = FRONT[0] + at * sx, FRONT[1] + FRONT[3] / 2
        ring(cx, cy, r * sx - .5, r * sx + 2.5, grey(118))
        text(int(cx - (4 * len(name) - 1) / 2), FRONT[1] + FRONT[3] - 9, name, grey(86))
        if i < LEDS:
            ring(cx, FRONT[1] + 9.5, 0, 3.6, grey(150))
            ring(cx, FRONT[1] + 9.5, 0, 2.6, (36, 46, 60, 255))
    brand(px, FRONT[0] + FRONT[2] - 70, FRONT[1] + 3, grey(86), ring, text)
    # The maker's name printed white on the plinth.
    brand(px, LOGO[0], LOGO[1] + 1, (255, 255, 255, 236), ring, text)
    img.save(OUT + "/at_lpa2.png")


def brand(px, x, y, colour, ring, text):
    """The maker's mark, a ring, and name: 64 texels across, 6 down."""
    ring(x + 3, y + 3, 2, 3.2, colour)
    text(x + 9, y, BRAND, colour)


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
    img.save(OUT + "/record.png")

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
    img.save(OUT + "/record_label.png")


if __name__ == "__main__":
    sheet()
    record()
