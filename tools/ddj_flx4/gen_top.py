#!/usr/bin/env python3
"""
DDJ-FLX4 -- the picture of its top that blocks/dj/ddjflx4/client/DjRenderer draws from.

Everything printed on the top and each button's and pad's face, with no lamp lit in the upper half of the picture and
with every lamp lit in the lower, at each width DjRenderer.PRINT_WIDTHS names. The controls' places are read from
Control.java, so the picture is made again whenever those change. All of it is drawn here: nothing is taken from a
photograph or from rekordbox, and the lettering is Pillow's own default font (Aileron, no rights reserved).

    python tools/ddj_flx4/gen_top.py [folder for pictures to look at]

Needs Pillow with FreeType.
"""
import math
import os
import re
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

LEFT, RIGHT, BACK, FRONT = 48, 942, 252, 747
W, H = 4096, 2272
KX, KY = W / (RIGHT - LEFT), H / (FRONT - BACK)
K = (KX + KY) / 2

DECK, MIXER, SEAM = (50, 50, 54), (41, 41, 44), (22, 22, 24)
LEGEND, FAINT = (212, 212, 212), (150, 150, 152)
ORANGE, AMBER, DIM = (255, 138, 30), (255, 190, 110), (112, 64, 22)
RUBBER, BLACK, FACE = (27, 27, 29), (12, 12, 13), (46, 46, 50)

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main')
CONTROLS = os.path.join(ROOT, 'java', 'org', 'haruyq', 'createmediasystems', 'blocks', 'dj', 'ddjflx4', 'Control.java')
OUT = os.path.join(ROOT, 'resources', 'assets', 'create_media_systems', 'textures', 'dj')

controls = {}
for m in re.finditer(r'^\s+(\w+)\(Kind\.(\w+), "[^"]*", (\d+), (\d+), (\d+), (\d+), (\d+),', open(CONTROLS, encoding='utf-8').read(), re.M):
    controls[m.group(1)] = (m.group(2),) + tuple(int(m.group(i)) for i in range(3, 8))
assert len(controls) == 46, len(controls)


def places(name):
    kind, x, y, w, d, apart = controls[name]
    return [(x + i * apart, y, w, d) for i in range(2 if apart else 1)]


def px(x, y):
    return (x - LEFT) * KX, (y - BACK) * KY


def box(x0, y0, x1, y1):
    a, b = px(x0, y0), px(x1, y1)
    return [a[0], a[1], b[0], b[1]]


def around(x, y, r):
    return box(x - r, y - r, x + r, y + r)


class Sheet:
    def __init__(self, image):
        self.image = image
        self.draw = ImageDraw.Draw(image)

    def font(self, size):
        return ImageFont.load_default(max(6, size * K))

    def text(self, x, y, line, size, fill=LEGEND, anchor='mm', bold=0.0, spacing=0.0):
        font = self.font(size)
        at = px(x, y)
        if spacing:
            # tracked out, letter by letter
            widths = [self.draw.textlength(c, font=font) + spacing * K for c in line]
            left = at[0] - sum(widths) / 2
            for c, w in zip(line, widths):
                self.draw.text((left, at[1]), c, font=font, fill=fill, anchor='lm', stroke_width=round(bold * K), stroke_fill=fill)
                left += w
            return
        self.draw.text(at, line, font=font, fill=fill, anchor=anchor, stroke_width=round(bold * K), stroke_fill=fill)

    def endless(self, x, y, size, fill=FAINT):
        """Minus infinity, its right end at x: the font has no such sign."""
        r = size * .2
        for i in (0, 1):
            self.draw.ellipse(around(x - r - i * r * 1.9, y, r), outline=fill, width=max(1, round(.4 * K)))
        self.line(x - r * 5.6, y, x - r * 3.6, y, fill, .45)

    def boxed(self, x, y, line, size, fill=LEGEND, pad=1.6):
        font = self.font(size)
        w = self.draw.textlength(line, font=font) / KX / 2 + pad
        h = size * .62 + pad * .55
        self.draw.rounded_rectangle(box(x - w, y - h, x + w, y + h), radius=.9 * K, outline=fill, width=max(1, round(.45 * K)))
        self.text(x, y, line, size, fill)

    def disc(self, x, y, r, fill=None, outline=None, width=0.0):
        self.draw.ellipse(around(x, y, r), fill=fill, outline=outline, width=max(1, round(width * K)) if outline else 0)

    def rect(self, x0, y0, x1, y1, fill=None, outline=None, width=0.0, radius=0.0):
        self.draw.rounded_rectangle(box(x0, y0, x1, y1), radius=radius * K, fill=fill, outline=outline,
                                    width=max(1, round(width * K)) if outline else 0)

    def line(self, x0, y0, x1, y1, fill=LEGEND, width=.5):
        self.draw.line([px(x0, y0), px(x1, y1)], fill=fill, width=max(1, round(width * K)))

    def arc(self, x, y, r, start, end, fill=LEGEND, width=.5):
        """Degrees clockwise from straight up."""
        self.draw.arc(around(x, y, r), start - 90, end - 90, fill=fill, width=max(1, round(width * K)))

    def spoke(self, x, y, r0, r1, degrees, fill=LEGEND, width=.5):
        a = math.radians(degrees)
        self.line(x + r0 * math.sin(a), y - r0 * math.cos(a), x + r1 * math.sin(a), y - r1 * math.cos(a), fill, width)

    def triangle(self, x, y, r, degrees, fill=LEGEND):
        """Pointing the way of {@code degrees}, clockwise from up."""
        points = []
        for turn in (0, 130, 230):
            a = math.radians(degrees + turn)
            points.append(px(x + r * math.sin(a), y - r * math.cos(a)))
        self.draw.polygon(points, fill=fill)

    def shade(self, shape, blur, strength):
        """Darkens under what {@code shape} draws (on a mask, in white), blurred {@code blur} wide."""
        mask = Image.new('L', self.image.size, 0)
        shape(Sheet(mask))
        mask = mask.filter(ImageFilter.GaussianBlur(blur * K)).point(lambda v: int(v * strength))
        self.image.paste(Image.new('RGB', self.image.size, (0, 0, 0)), (0, 0), mask)
        self.draw = ImageDraw.Draw(self.image)

    def glow(self, shape, blur, color, strength=1.0):
        mask = Image.new('L', self.image.size, 0)
        shape(Sheet(mask))
        mask = mask.filter(ImageFilter.GaussianBlur(blur * K)).point(lambda v: int(v * strength))
        self.image.paste(Image.new('RGB', self.image.size, color), (0, 0), mask)
        self.draw = ImageDraw.Draw(self.image)

    def dome(self, x, y, r, middle, edge):
        """A round face lighter in its middle."""
        steps = 14
        for i in range(steps):
            t = i / (steps - 1)
            self.disc(x, y, r * (1 - t * .9), fill=tuple(round(edge[c] + (middle[c] - edge[c]) * t) for c in range(3)))


def panel(s):
    s.rect(LEFT, BACK, RIGHT, FRONT, fill=DECK)
    s.rect(352, BACK, 638, FRONT, fill=MIXER)
    for x in (352, 638):
        s.line(x, BACK, x, FRONT, SEAM, .9)
        s.line(x + .8, BACK, x + .8, FRONT, (66, 66, 70), .35)


def knob_scale(s, x, y, r, low=None, high=None, middle=True, size=4.2):
    s.arc(x, y, r, -150, 150, FAINT, .45)
    for degrees in (-150, 150):
        s.spoke(x, y, r - 1.2, r + 1.8, degrees, LEGEND, .6)
    if middle:
        s.spoke(x, y, r, r + 2.4, 0, LEGEND, .6)
    if low == 'endless':
        s.endless(x - r * .86 - 1, y + r * .98 + 2.2, size)
    elif low:
        s.text(x - r * .86 - 1, y + r * .98 + 2.2, low, size, FAINT, 'rm')
    if high:
        s.text(x + r * .86 + 1, y + r * .98 + 2.2, high, size, FAINT, 'lm')


def printed(s):
    """What is printed on the panel itself, and the shadow each control casts on it."""
    def shadows(m):
        for name, (kind, x, y, w, d, apart) in controls.items():
            for (cx, cy, cw, cd) in places(name):
                if kind == 'JOG':
                    m.disc(cx, cy + 3, cw / 2 + 5, fill=255)
                elif kind in ('KNOB', 'ENCODER'):
                    m.disc(cx + 1, cy + 3.5, cw / 2 + 1.5, fill=255)
                elif kind in ('BUTTON', 'PAD') and cw == cd:
                    m.disc(cx, cy + 1.2, cw / 2 + 1.2, fill=255) if kind == 'BUTTON' else m.rect(cx - cw / 2 - 1, cy - cd / 2, cx + cw / 2 + 1, cy + cd / 2 + 2, fill=255, radius=3)
                elif kind in ('BUTTON', 'PAD'):
                    m.rect(cx - cw / 2 - 1, cy - cd / 2, cx + cw / 2 + 1, cy + cd / 2 + 1.6, fill=255, radius=2)
    s.shade(shadows, 2.6, .5)

    for deck in (0, 588):
        # The well the jog wheel sits in.
        s.disc(200 + deck, 462, 134, fill=(30, 30, 33), outline=(70, 70, 74), width=.6)
        s.disc(200 + deck, 462, 131.5, fill=(18, 18, 20))
        # SEARCH, up to the left of the wheel, along its edge.
        s.text(98 + deck, 352, 'SEARCH', 4.6, FAINT)
        s.triangle(82 + deck, 352, 2.2, -90, FAINT)
        s.triangle(114 + deck, 352, 2.2, 90, FAINT)
        s.text(87 + deck, 280, 'IN', 5.6)
        s.boxed(87 + deck, 323, 'IN ADJ', 3.6, FAINT)
        s.text(137 + deck, 280, 'OUT', 5.6)
        s.boxed(137 + deck, 323, 'OUT ADJ', 3.6, FAINT)
        s.text(183 + deck, 280, '4 BEAT/EXIT', 5.2)
        s.boxed(183 + deck, 321, 'ACTIVE', 3.6, FAINT)
        s.text(250 + deck, 278, 'CUE/LOOP', 5.2)
        s.text(250 + deck, 287, 'CALL', 5.2)
        s.text(232 + deck, 313.5, '1/2X', 3.6, FAINT)
        s.text(268 + deck, 313.5, '2X', 3.6, FAINT)
        s.boxed(250 + deck, 313.5, 'LOOP', 3.4, LEGEND, 1.1)
        s.boxed(232 + deck, 322.5, 'DEL', 3.4, FAINT, 1.1)
        s.boxed(268 + deck, 322.5, 'MEMORY', 3.4, FAINT, 1.1)
        s.line(297 + deck, 284, 301 + deck, 284, LEGEND, .6)
        s.text(304 + deck, 284, 'MASTER', 4.8, LEGEND, 'lm')
        s.boxed(314 + deck, 322, 'TEMPO RANGE', 3.4, FAINT, 1.1)
        s.boxed(87 + deck, 573, 'SHIFT', 4.2, LEGEND, 1.3)
        s.text(90 + deck, 722, 'PLAY/PAUSE', 5)
        for i, name in enumerate(('KEYBOARD', 'PAD FX2', 'BEAT LOOP', 'KEY SHIFT')):
            s.boxed((140, 182, 223, 264)[i] + deck, 630, name, 3.4, FAINT, 1.0)
        # The tempo fader's slot and scale.
        x = 316 + deck
        s.rect(x - 2.2, 588, x + 2.2, 712, fill=BLACK, radius=2)
        s.rect(x - 1.1, 590, x + 1.1, 710, fill=(4, 4, 4), radius=1)
        for i in range(-10, 11):
            y = 650 + i * 5.4
            long = i % 5 == 0
            s.line(x - (13 if long else 10.5), y, x - 6, y, LEGEND if long else FAINT, .55 if long else .4)
            s.line(x + 6, y, x + (13 if long else 10.5), y, LEGEND if long else FAINT, .55 if long else .4)
        s.line(x - 23.5, 596, x - 18.5, 596, LEGEND, .6)
        s.text(x - 21, 650, '0', 5)
        s.text(x - 21, 704, '+', 6)
        s.disc(x - 21, 658, 1.3, fill=(60, 40, 22))
        s.text(x, 722, 'TEMPO', 5)

    # The mixer.
    s.text(435, 271, 'LOAD', 5)
    s.text(555, 271, 'LOAD', 5)
    s.boxed(435, 297, '1', 3.4, FAINT, 2.4)
    s.boxed(555, 297, '2', 3.4, FAINT, 2.4)
    s.boxed(495, 300, 'BACK', 3.4, FAINT, 1.1)
    s.text(389, 311, 'PERFORMANCE', 3.5, FAINT)
    s.text(389, 317, 'DJ CONTROLLER', 3.5, FAINT)
    s.text(389, 328, 'DDJ-FLX4', 6.6, LEGEND, bold=.28)
    for channel in (0, 75):
        x = 457 + channel
        s.text(x, 320 - 21, 'TRIM', 5)
        knob_scale(s, x, 320, 14.5, 'endless', '+9')
        for y, name in ((366, 'HI'), (411, 'MID'), (456, 'LOW')):
            s.text(x, y - 23, name, 5)
            knob_scale(s, x, y, 16.5, '-26', '+6')
        s.text(x, 502 - 23, 'CFX', 5)
        knob_scale(s, x, 502, 16.5, 'LOW', 'HI')
        s.text(x, 572, '12'[channel > 0], 8.5, LEGEND)
        # The channel fader's slot and scale.
        s.rect(x - 2.2, 584, x + 2.2, 672, fill=BLACK, radius=2)
        s.rect(x - 1.1, 586, x + 1.1, 670, fill=(4, 4, 4), radius=1)
        for i in range(11):
            y = 588 + i * 8
            long = i in (0, 5, 10)
            for side in (-1, 1):
                s.line(x + side * 6, y, x + side * (14 if long else 11), y, LEGEND if long else FAINT, .55 if long else .4)
    # The level lamps' windows, between the channels.
    for channel in range(2):
        for step in range(5):
            x, y = 487 + channel * 16, 446 - step * 16
            s.rect(x - 2.6, y - 4.2, x + 2.6, y + 4.2, fill=(16, 18, 16) if step < 2 else (20, 17, 13) if step < 4 else (21, 14, 13), radius=.8)
    s.text(495, 345, 'MIDI', 3.2, FAINT)
    s.rect(491.5, 351, 498.5, 353.4, fill=(26, 30, 22), radius=.8)
    s.text(389, 390, 'MIC LEVEL', 4.6)
    knob_scale(s, 389, 411, 16.5, 'endless', '0', False)
    s.boxed(386, 522, 'FX SELECT', 3.3, FAINT, 1.0)
    for y, name in ((571, 'MIX'), (617, 'LEVEL')):
        # A pair of headphones, drawn small, before the name.
        s.arc(376, y - 22, 3, -100, 100, LEGEND, .6)
        s.rect(372.2, y - 22.5, 373.8, y - 19.2, fill=LEGEND, radius=.4)
        s.rect(378.2, y - 22.5, 379.8, y - 19.2, fill=LEGEND, radius=.4)
        s.text(382, y - 21.5, name, 4.6, LEGEND, 'lm')
    knob_scale(s, 389, 571, 14.5, 'CUE', 'MST')
    knob_scale(s, 389, 617, 14.5, 'endless', '0', False)
    s.text(599, 300, 'MASTER LEVEL', 4.8)
    knob_scale(s, 599, 320, 15.5, 'endless', '0', False)
    s.text(600, 413, 'BEAT FX', 6.2)
    s.text(600, 441, '1    2   1&2', 4.2)
    s.rect(585, 450, 615, 464, fill=BLACK, radius=1.5)
    s.boxed(600, 491, 'FX SELECT', 3.3, FAINT, 1.0)
    s.text(600, 536, 'BEAT', 5)
    s.boxed(586, 562, 'AUTO', 3.3, FAINT, 1.0)
    s.boxed(614, 562, 'TAP', 3.3, FAINT, 1.0)
    s.text(599, 586, 'LEVEL/DEPTH', 4.6)
    knob_scale(s, 599, 607, 15.5, 'MIN', 'MAX', False)
    s.text(599, 640, 'ON/OFF', 4.6)
    s.boxed(599, 682, 'RELEASE FX', 3.3, FAINT, 1.0)
    # The maker's name, in the same plain letters as everything else.
    s.text(495, 688, 'PIONEAR DJ', 8.2, LEGEND, spacing=.7)
    # The crossfader's slot and scale.
    s.rect(451, 709.8, 539, 714.2, fill=BLACK, radius=2)
    s.rect(453, 710.9, 537, 713.1, fill=(4, 4, 4), radius=1)
    for i in range(9):
        x = 455 + i * 10
        long = i in (0, 4, 8)
        s.line(x, 698 if long else 700.5, x, 705, LEGEND if long else FAINT, .55 if long else .4)
        s.line(x, 719, x, 726 if long else 723.5, LEGEND if long else FAINT, .55 if long else .4)


def jog_top(s, x, y, r):
    """The wheel's top as the renderer's disc samples it: a turned plate, a bright ring, a dark middle with a scale."""
    top = r * .885
    s.disc(x, y, top, fill=(42, 42, 46))
    for i in range(54):
        ring = top - 1.2 - i * (top * .56 / 54)
        tone = 45 + (6 if i % 2 else -5) + (4 if i % 6 == 0 else 0)
        s.disc(x, y, ring, outline=(tone, tone, tone + 3), width=.5)
    # A sheen across it, as a turned plate has.
    sheen = Image.new('L', s.image.size, 0)
    d = ImageDraw.Draw(sheen)
    for degrees in (-52, 128):
        d.pieslice(around(x, y, top - 1), degrees - 15, degrees + 15, fill=150)
    sheen = sheen.filter(ImageFilter.GaussianBlur(8 * K))
    hole = Image.new('L', s.image.size, 0)
    ImageDraw.Draw(hole).ellipse(around(x, y, top - 1), fill=255)
    ImageDraw.Draw(hole).ellipse(around(x, y, r * .39), fill=0)
    s.image.paste(Image.new('RGB', s.image.size, (128, 128, 134)), (0, 0), ImageChops.multiply(sheen, hole).point(lambda v: v // 3))
    s.draw = ImageDraw.Draw(s.image)
    s.disc(x, y, r * .39, fill=(16, 16, 18))
    for i in range(10):
        t = i / 9
        tone = round(96 + 84 * math.sin(t * math.pi))
        s.disc(x, y, r * (.37 - t * .055), outline=(tone, tone, tone + 6), width=r * .009)
    s.disc(x, y, r * .31, fill=(19, 19, 22))
    for i in range(60):
        s.spoke(x, y, r * .245, r * (.29 if i % 5 == 0 else .275), i * 6, (70, 70, 75) if i % 5 == 0 else (48, 48, 52), .45)
    s.disc(x, y, r * .225, fill=(24, 24, 27), outline=(40, 40, 44), width=.5)
    s.disc(x, y, r * .1, outline=(34, 34, 37), width=.5)


def face(s, name, x, y, w, d, lit):
    """One control's face, unlit or lit."""
    kind = controls[name][0]
    on, ring = (ORANGE, AMBER) if lit else (DIM, DIM)
    words = AMBER if lit else (176, 176, 176)
    if kind == 'JOG':
        jog_top(s, x, y, w / 2)
    elif kind == 'PAD':
        s.rect(x - w / 2, y - d / 2, x + w / 2, y + d / 2, fill=(18, 18, 20), radius=3.4)
        if lit:
            s.glow(lambda m: m.rect(x - w / 2 + 1, y - d / 2 + 1, x + w / 2 - 1, y + d / 2 - 1, outline=255, width=3.4, radius=3), 2.2, ORANGE, .9)
        s.rect(x - w / 2 + 1.1, y - d / 2 + 1.1, x + w / 2 - 1.1, y + d / 2 - 1.1, outline=AMBER if lit else DIM, width=1.25, radius=2.8)
        s.rect(x - w / 2 + 3.6, y - d / 2 + 3.6, x + w / 2 - 3.6, y + d / 2 - 3.6, fill=(232, 122, 26) if lit else RUBBER, radius=1.6)
        if not lit:
            s.rect(x - w / 2 + 4.6, y - d / 2 + 4.6, x + w / 2 - 4.6, y - d / 2 + 5.4, fill=(36, 36, 39), radius=.4)
    elif name in ('LOOP_IN', 'LOOP_OUT', 'FX_ON'):
        s.disc(x, y, w / 2, fill=(30, 30, 32))
        if lit:
            s.dome(x, y, w / 2 - 1.2, (255, 176, 84), (236, 112, 16))
        else:
            s.dome(x, y, w / 2 - 1.2, (146, 106, 72), (98, 64, 38))
        s.disc(x - w * .12, y - w * .14, w * .09, fill=(255, 214, 160) if lit else (164, 126, 92))
    elif name in ('CUE', 'PLAY'):
        s.disc(x, y, w / 2, fill=(22, 22, 24))
        if lit:
            s.glow(lambda m: m.disc(x, y, w / 2 - 2.2, outline=255, width=2.6), 1.6, ORANGE, .85)
        s.disc(x, y, w / 2 - 2.2, outline=ring if lit else (86, 54, 24), width=1.5)
        s.disc(x, y, w / 2 - 4.6, fill=(14, 14, 15), outline=(40, 40, 43), width=.5)
        if name == 'CUE':
            s.text(x, y, 'CUE', 7.2, AMBER if lit else (150, 124, 96), bold=.18)
        else:
            s.triangle(x - 4.6, y, 4.3, 90, AMBER if lit else (150, 124, 96))
            s.line(x + 1.2, y - 4, x - .8, y + 4, AMBER if lit else (150, 124, 96), .7)
            s.rect(x + 3.2, y - 3.6, x + 4.8, y + 3.6, fill=AMBER if lit else (150, 124, 96))
            s.rect(x + 6.4, y - 3.6, x + 8, y + 3.6, fill=AMBER if lit else (150, 124, 96))
    elif name in ('SYNC', 'SMART_CFX', 'SMART_FADER'):
        s.disc(x, y, w / 2, fill=(26, 26, 28))
        if lit:
            s.glow(lambda m: m.disc(x, y, w / 2 - 1.4, outline=255, width=1.8), 1.3, ORANGE, .8)
        s.disc(x, y, w / 2 - 1.4, outline=ring if lit else (96, 58, 24), width=1.0)
        top, bottom = {'SYNC': ('BEAT', 'SYNC'), 'SMART_CFX': ('SMART', 'CFX'), 'SMART_FADER': ('SMART', 'FADER')}[name]
        s.text(x, y - 2.1, top, 3.5, words)
        s.text(x, y + 2.3, bottom, 3.5, words)
    elif name in ('BEAT_4', 'CALL_LEFT', 'CALL_RIGHT'):
        s.disc(x, y, w / 2, fill=(24, 24, 26))
        s.dome(x, y, w / 2 - 1, (74, 74, 78) if lit else (50, 50, 54), (40, 40, 44) if lit else (30, 30, 33))
        if name != 'BEAT_4':
            s.triangle(x, y, 3.1, -90 if name == 'CALL_LEFT' else 90, (236, 236, 236) if lit else (170, 170, 172))
    elif name.startswith('MODE_') or name in ('MASTER_CUE', 'CHANNEL_CUE'):
        s.rect(x - w / 2, y - d / 2, x + w / 2, y + d / 2, fill=(22, 22, 24), radius=1.4)
        if lit:
            s.glow(lambda m: m.rect(x - w / 2 + .8, y - d / 2 + .8, x + w / 2 - .8, y + d / 2 - .8, outline=255, width=1.6, radius=1.2), 1.1, ORANGE, .8)
        s.rect(x - w / 2 + .8, y - d / 2 + .8, x + w / 2 - .8, y + d / 2 - .8, fill=(238, 128, 28) if lit else (26, 24, 23), outline=ring if lit else (120, 70, 26), width=.8, radius=1.2)
        label = {'MODE_HOT_CUE': 'HOT CUE', 'MODE_PAD_FX': 'PAD FX1', 'MODE_BEAT_JUMP': 'BEAT JUMP', 'MODE_SAMPLER': 'SAMPLER'}.get(name, 'CUE')
        s.text(x, y + .1, label, 4.4 if len(label) > 3 else 5, (36, 20, 6) if lit else (206, 206, 206), bold=.06)
    else:
        # LOAD, SHIFT, the beat FX's small keys.
        s.rect(x - w / 2, y - d / 2, x + w / 2, y + d / 2, fill=(22, 22, 24), radius=1.4)
        s.rect(x - w / 2 + .7, y - d / 2 + .7, x + w / 2 - .7, y + d / 2 - .7, fill=(86, 86, 92) if lit else (58, 58, 63), radius=1.1)
        s.rect(x - w / 2 + 1.6, y - d / 2 + 1.3, x + w / 2 - 1.6, y - d / 2 + 2.1, fill=(112, 112, 118) if lit else (76, 76, 82), radius=.4)
        mark = (240, 240, 240) if lit else (188, 188, 190)
        if name == 'FX_SELECT':
            s.triangle(x, y, 3, 180, mark)
        elif name == 'FX_BEAT_LEFT':
            s.triangle(x, y, 3, -90, mark)
        elif name == 'FX_BEAT_RIGHT':
            s.triangle(x, y, 3, 90, mark)


def faces(s, lit):
    for name, (kind, x, y, w, d, apart) in controls.items():
        if kind in ('BUTTON', 'PAD') or kind == 'JOG' and not lit:
            for (cx, cy, cw, cd) in places(name):
                face(s, name, cx, cy, cw, cd, lit)


base = Sheet(Image.new('RGB', (W, H)))
panel(base)
printed(base)
faces(base, False)
lit = Sheet(base.image.copy())
faces(lit, True)

whole = Image.new('RGB', (W, H * 2))
whole.paste(base.image, (0, 0))
whole.paste(lit.image, (0, H))
for level, width in enumerate((2048, 1024, 512, 256, 128)):
    small = whole.resize((width, H * 2 * width // W), Image.LANCZOS)
    small.save(os.path.join(OUT, 'ddj_flx4_top_%d.png' % level), optimize=True)
    print(level, small.size)
if len(sys.argv) > 1:
    # For looking at: the unlit half whole, and a piece of each half at full size.
    base.image.resize((1800, 1800 * H // W), Image.LANCZOS).save(sys.argv[1] + '/art-whole.png')
    base.image.crop((0, H // 3, W // 2, H)).resize((1800, 1800 * (H - H // 3) // (W // 2)), Image.LANCZOS).save(sys.argv[1] + '/art-deck.png')
    lit.image.crop((W // 3, 0, W * 2 // 3 + 100, H)).save(sys.argv[1] + '/art-mixer-lit.png')
