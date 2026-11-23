#!/usr/bin/env python3
"""
DDJ-FLX4 -- the pictures of letters that blocks/dj/ddjflx4/client/DjDisplay writes its display with.

Barlow Condensed SemiBold (assets/create_media_systems/font, SIL Open Font License) drawn out letter by letter, Latin
with its accented letters, into a grid of cells: 16 to a row, a cell CELL_WIDE by CELL_TALL ems, a letter's pen at
PEN_X, BASELINE in its cell. One picture at each size DjDisplay.EMS names, each made from the same large drawing, so
that a letter keeps its shape as it is come nearer to. The game's own font is not smoothed when it is drawn at another
size than its picture's; these are.

    python tools/ddj_flx4/gen_letters.py

It prints how far each letter moves the pen, in thousandths of an em: paste that over DjDisplay.ADVANCES whenever the
typeface changes. Needs Pillow with FreeType.
"""
import os

from PIL import Image, ImageDraw, ImageFont

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'resources', 'assets',
                      'create_media_systems')
TTF = os.path.join(ASSETS, 'font', 'barlow_condensed_semibold.ttf')
OUT = os.path.join(ASSETS, 'textures', 'dj')

# As DjDisplay has them.
EMS = (64, 32, 16, 8)
CELL_WIDE, CELL_TALL, PEN_X, BASELINE = 1.25, 1.5, .2, 1.15
LETTERS = list(range(0x20, 0x7F)) + [None] + list(range(0xA0, 0x100))

DRAWN = 128
font = ImageFont.truetype(TTF, DRAWN)
wide, tall = round(CELL_WIDE * DRAWN), round(CELL_TALL * DRAWN)
sheet = Image.new('L', (16 * wide, len(LETTERS) // 16 * tall), 0)
draw = ImageDraw.Draw(sheet)
# A picture is smoothed where it is drawn from, so a letter keeps a pixel of the smallest picture clear of its
# neighbours on every side.
clear = DRAWN // min(EMS)
for cell, letter in enumerate(LETTERS):
    if letter is not None:
        x, y = cell % 16 * wide + round(PEN_X * DRAWN), cell // 16 * tall + round(BASELINE * DRAWN)
        left, top, right, bottom = font.getbbox(chr(letter), anchor='ls')
        assert x + left >= cell % 16 * wide + clear and x + right <= (cell % 16 + 1) * wide - clear, hex(letter)
        assert y + top >= cell // 16 * tall + clear and y + bottom <= (cell // 16 + 1) * tall - clear, hex(letter)
        draw.text((x, y), chr(letter), font=font, fill=255, anchor='ls')

os.makedirs(OUT, exist_ok=True)
for em in EMS:
    small = sheet.reduce(DRAWN // em)
    Image.merge('LA', (Image.new('L', small.size, 255), small)).save(os.path.join(OUT, 'display_letters_%d.png' % em),
                                                                     optimize=True)

unit = ImageFont.truetype(TTF, 1000)
advances = [0 if letter is None else round(unit.getlength(chr(letter))) for letter in LETTERS]
for row in range(0, len(advances), 16):
    print('            ' + ' '.join('%d,' % advance for advance in advances[row:row + 16]))
