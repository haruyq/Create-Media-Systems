#!/usr/bin/env python3
"""
PX-S1100WE (white) digital piano -- Minecraft Java block model generator.

True scale: 1 block = 1 m, 1 model px = 62.5 mm.
Real body: 1322 x 232 x 102 mm  ->  21.152 x 3.712 x 1.632 px.

Piano-local coordinates (mm):
    x: 0 (bass / player's left) .. 1322 (treble)
    y: 0 (bottom of rubber feet) .. 102 (top panel)
    z: 0 (front, player side) .. 232 (back)

Model space (Minecraft JSON, 1/16 block):
    front faces NORTH (-Z).  Bass end is +X (east), treble end is -X.
    The piano is centred on X = 16 (the seam of a 2-block-wide structure whose
    main block is the model origin block and whose second block is at +X), Z = 8.

Usage:  python3 gen_px_s1100.py --namespace <modid> --out <resources dir>
Requires Pillow.
"""
import argparse
import json
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# --------------------------------------------------------------------------- dimensions
S = 1.0 / 62.5                       # model px per mm
W, D, H = 1322.0, 232.0, 102.0       # body
KEY_X0, KEY_X1 = 50.0, 1272.0        # keyboard span (52 white keys)
N_WHITE = 52
WP = (KEY_X1 - KEY_X0) / N_WHITE     # white key pitch 23.5 mm
GAP = 0.6                            # half gap between keys (mm)
KEY_Z0, KEY_ZSPLIT, KEY_Z1 = 8.5, 58.0, 153.0
BLACK_Z0 = 59.0
BLACK_W = 12.0
WHITE_Y0, WHITE_Y1 = 70.0, 88.0
BLACK_Y0, BLACK_Y1 = 76.0, 100.0
PIVOT_Y, PIVOT_Z = 80.0, 156.0
PRESS_DEG = 4.0
PANEL_Z0 = 156.0
STAND_H = 638.0                      # floor -> underside of piano when on the stand
STAND_D = 366.0

CAP = 50.0                           # width of the matte end caps
CH = 16.0                            # plan-view corner chamfer of the end caps
POWER_C = (385.0, 194.0)             # (x, z) centre on top panel
KNOB_C = (430.0, 194.0)
KNOB_R, KNOB_H = 18.0, 11.0
POWER_R, POWER_H = 6.0, 1.6
PLED_C = (471.0, 195.0)              # status LED between the knob and the touch buttons
BTN_PITCH = 28.2
BUTTONS = [                          # id, centre x, label lines
    ("function", 506.0 + 0 * BTN_PITCH, ["FUNCTION"]),
    ("sound_mode", 506.0 + 1 * BTN_PITCH, ["SOUND", "MODE"]),
    ("metronome", 506.0 + 2 * BTN_PITCH, ["METRO", "NOME"]),
    ("play", 506.0 + 3 * BTN_PITCH, None),
    ("rec", 506.0 + 4 * BTN_PITCH, None),
    ("grand_piano", 506.0 + 5 * BTN_PITCH, ["GRAND", "PIANO"]),
    ("elec_piano", 506.0 + 6 * BTN_PITCH, ["ELEC.", "PIANO"]),
]
BTN_Z = 191.0
BTN_HALF_W = BTN_PITCH / 2
BTN_Z0, BTN_Z1 = 178.0, 216.0
LED_Z = 207.0
LOGO_TEXT = "hayacio"
LOGO_C = (344.0, 194.0)              # small gold logo left of the power button
MSTAND_X0, MSTAND_X1 = 396.0, 926.0
MS_HOLES = (410.0, 912.0)            # music-stand sockets on the back face
SPK = ((35.0, 220.0), (W - 220.0, W - 35.0))   # speaker grilles on the back (x ranges)
SPK_Y = (20.0, 92.0)
JACKS = [("DAMPER", 371.0, "q"), ("PEDAL UNIT", 349.0, "din"), ("R", 305.0, "q"),
         ("L/MONO", 284.0, "q"), ("DC 12V", 240.0, "dc")]
JACK_Y = 87.0
JACK_PILL = (226.0, 384.0, 76.0, 97.0)          # x0, x1, y0, y1
USB_B, USB_A, USB_Y = 694.0, 664.0, 27.0
PHONES = ((33.0, 48.0), (33.0, 27.0))           # (x, y) on the front of the bass-end cap

BLACK_OFFSETS = {1: 21.15, 3: 49.35, 6: 90.64, 8: 117.5, 10: 144.36}   # from C left edge
NOTE_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

# --------------------------------------------------------------------------- textures
TEX = {            # key -> (file stem, width, height)
    "shell": ("shell", 2048, 512),
    "panel": ("panel", 2048, 128),
    "lit": ("panel_lit", 2048, 128),
    "keys": ("keys", 64, 64),
}
PPM = 2048.0 / W                     # texture px per mm on panel / back / front
PPB = 1024.0 / W                     # bottom
BACK_V0, FRONT_V0, BOTTOM_V0 = 0.0, 160.0, 320.0
SW = {  # swatches in shell.png (u1, v1, u2, v2)
    "white": (1060, 324, 1084, 348),
    "keybed": (1100, 324, 1124, 348),
    "felt": (1140, 324, 1164, 348),
    "rubber": (1180, 324, 1204, 348),
    "board": (1220, 324, 1244, 348),
    "gold": (1260, 324, 1284, 348),
    "pedal_dark": (1300, 324, 1324, 348),
    "pedal_metal": (1340, 324, 1364, 348),
    "stand_white": (1380, 324, 1404, 348),
    "under": (1420, 324, 1444, 348),
    "wood": (1460, 324, 1484, 348),
    "wood_end": (1500, 324, 1524, 348),
}
KSW = {  # swatches in keys.png
    "w_top": (1, 1, 15, 47),
    "w_front": (17, 1, 31, 15),
    "w_side": (17, 17, 31, 31),
    "b_front": (17, 33, 31, 47),
    "b_top": (33, 1, 43, 47),
    "b_side": (45, 1, 55, 47),
    "power_top": (0, 48, 16, 64),
    "knob_top": (16, 48, 32, 64),
    "knob_side": (33, 49, 47, 63),
    "dark": (49, 49, 63, 63),
}


def m_panel(x, y, z):
    return ("panel", x * PPM, (D - z) * PPM)


def m_lit(x, y, z):
    return ("lit", x * PPM, (D - z) * PPM)


def m_back(x, y, z):
    return ("shell", (W - x) * PPM, BACK_V0 + (H - y) * PPM)


def m_front(x, y, z):
    return ("shell", x * PPM, FRONT_V0 + (92.0 - y) * PPM)


def m_bottom(x, y, z):
    return ("shell", x * PPB, BOTTOM_V0 + z * PPB)


def m_radial(region, cx, cz, r_mm):
    u0, v0, u1, v1 = KSW[region]
    uc, vc, k = (u0 + u1) / 2, (v0 + v1) / 2, 7.0 / r_mm

    def f(x, y, z):
        return ("keys", uc + (x - cx) * k, vc - (z - cz) * k)
    return f


def sw(name):
    return ("swatch", "shell", SW[name])


def ksw(name):
    return ("swatch", "keys", KSW[name])


# --------------------------------------------------------------------------- geometry helpers
class Frame:
    """Maps piano mm -> model px. origin_mm lands on model_off."""

    def __init__(self, origin_mm=(W / 2, 0.0, D / 2), model_off=(16.0, 0.0, 8.0)):
        self.o = origin_mm
        self.mo = model_off

    def to_model(self, x, y, z):
        return (self.mo[0] - (x - self.o[0]) * S,
                self.mo[1] + (y - self.o[1]) * S,
                self.mo[2] + (z - self.o[2]) * S)

    def to_mm(self, X, Y, Z):
        return (self.o[0] - (X - self.mo[0]) / S,
                self.o[1] + (Y - self.mo[1]) / S,
                self.o[2] + (Z - self.mo[2]) / S)

    def raised(self, dy_mm):
        return Frame(self.o, (self.mo[0], self.mo[1] + dy_mm * S, self.mo[2]))


PF = {"front": "north", "back": "south", "left": "east", "right": "west", "top": "up", "bottom": "down"}


def face_corners(mf, a, b):
    """Top-left and bottom-right corner (model space) of a face seen from outside."""
    (x0, y0, z0), (x1, y1, z1) = a, b
    return {
        "north": ((x1, y1, z0), (x0, y0, z0)),
        "south": ((x0, y1, z1), (x1, y0, z1)),
        "east": ((x1, y1, z1), (x1, y0, z0)),
        "west": ((x0, y1, z0), (x0, y0, z1)),
        "up": ((x0, y1, z0), (x1, y1, z1)),
        "down": ((x0, y0, z1), (x1, y0, z0)),
    }[mf]


def r4(v):
    return round(v + 0.0, 4)


def box(frame, x0, x1, y0, y1, z0, z1, faces, rot=None, emissive=False):
    """Axis-aligned box given in piano mm. faces: piano-face -> mapper | swatch.
    rot: (axis, angle_deg, (x, y, z) origin in mm)."""
    a = frame.to_model(x1, y0, z0)
    b = frame.to_model(x0, y1, z1)
    el = {"from": [r4(c) for c in a], "to": [r4(c) for c in b], "faces": {}}
    if rot:
        axis, ang, org = rot
        el["rotation"] = {"origin": [r4(c) for c in frame.to_model(*org)], "axis": axis, "angle": ang}
    for pf, spec in faces.items():
        mf = PF[pf]
        if isinstance(spec, tuple) and spec[0] == "swatch":
            tex, (u0, v0, u1, v1) = spec[1], spec[2]
        else:
            tl, br = face_corners(mf, a, b)
            tex, u0, v0 = spec(*frame.to_mm(*tl))
            _, u1, v1 = spec(*frame.to_mm(*br))
        _, tw, th = TEX[tex]
        face = {"uv": [r4(u0 / tw * 16), r4(v0 / th * 16), r4(u1 / tw * 16), r4(v1 / th * 16)],
                "texture": "#" + tex}
        if emissive:
            face["neoforge_data"] = {"block_light": 15, "sky_light": 15, "ambient_occlusion": False}
        el["faces"][mf] = face
    return el


def octagon(frame, cx, cz, r, y0, y1, top, side, bottom=None):
    """Octagonal prism from 4 boxes (two of them rotated 45 deg about Y)."""
    h = r * math.tan(math.radians(22.5))
    out = []
    org = (cx, y0, cz)
    for (hx, hz) in ((r, h), (h, r)):
        for ang in (0, 45):
            faces = {"top": top}
            if bottom:
                faces["bottom"] = bottom
            if hx > hz:
                faces["left"] = side
                faces["right"] = side
            else:
                faces["front"] = side
                faces["back"] = side
            out.append(box(frame, cx - hx, cx + hx, y0, y1, cz - hz, cz + hz, faces,
                           rot=("y", ang, org) if ang else None))
    return out


# --------------------------------------------------------------------------- keys
def build_keys():
    keys = []
    wi = 0
    for midi in range(21, 109):
        pc = midi % 12
        octave = midi // 12 - 1
        black = pc in BLACK_OFFSETS
        c_left = KEY_X0 + (7 * octave - 5) * WP
        k = {"midi": midi, "name": NOTE_NAMES[pc] + str(octave), "black": black}
        if black:
            k["cx"] = c_left + BLACK_OFFSETS[pc]
        else:
            k["x0"] = KEY_X0 + wi * WP
            k["x1"] = k["x0"] + WP
            k["cx"] = (k["x0"] + k["x1"]) / 2
            wi += 1
        keys.append(k)
    blacks = [k for k in keys if k["black"]]
    for k in keys:
        if k["black"]:
            k["shape"] = "key_black"
            continue
        rl, rr = k["x0"] + GAP, k["x1"] - GAP
        for b in blacks:
            bl, br = b["cx"] - BLACK_W / 2 - GAP, b["cx"] + BLACK_W / 2 + GAP
            if bl < k["x1"] and br > k["x0"]:
                if b["cx"] < k["cx"]:
                    rl = max(rl, br)
                else:
                    rr = min(rr, bl)
        k["rl"], k["rr"] = rl, rr
        if k["midi"] == 21:
            k["shape"] = "key_white_a0"
        elif k["midi"] == 108:
            k["shape"] = "key_white_c8"
        else:
            k["shape"] = "key_white_" + NOTE_NAMES[k["midi"] % 12].lower()
    return keys


def white_key_elements(frame, k):
    side = ksw("w_side")
    return [
        box(frame, k["x0"] + GAP, k["x1"] - GAP, WHITE_Y0, WHITE_Y1, KEY_Z0, KEY_ZSPLIT,
            {"front": ksw("w_front"), "top": ("swatch", "keys", (1, 31, 15, 47)),
             "left": side, "right": side, "back": side}),
        box(frame, k["rl"], k["rr"], WHITE_Y0, WHITE_Y1, KEY_ZSPLIT, KEY_Z1,
            {"top": ("swatch", "keys", (1, 1, 15, 31)), "left": side, "right": side}),
    ]


def black_key_faces(frame, cx):
    """A flat front ramp and tapered sides, like the concert grand's black keys."""
    ramp_length, taper = 12.0, 1.2
    half = BLACK_W / 2
    height = BLACK_Y1 - BLACK_Y0
    pivot_x, pivot_y, pivot_z = (r4(value) for value in frame.to_model(cx, PIVOT_Y, PIVOT_Z))
    profile = [(BLACK_Z0, WHITE_Y1 + 1.0), (BLACK_Z0 + ramp_length, BLACK_Y1), (KEY_Z1, BLACK_Y1)]

    def ring(top):
        top_half = half - taper * (top - BLACK_Y0) / height
        return [(-half, BLACK_Y0), (half, BLACK_Y0), (top_half, top), (-top_half, top)]

    def vertex(section, corner, swatch, normal):
        z, top = profile[section]
        x, y = ring(top)[corner]
        point = ((pivot_x + x * S) / 16, (pivot_y + (y - PIVOT_Y) * S) / 16,
                 (pivot_z + (z - PIVOT_Z) * S) / 16)
        u = (x + half) / BLACK_W
        v = (BLACK_Y1 - y) / height
        depth = (z - BLACK_Z0) / (KEY_Z1 - BLACK_Z0)
        if swatch == "b_top":
            u = min(1.0, max(0.0, (x + half - taper) / (BLACK_W - 2 * taper)))
            v = depth
        elif swatch == "b_side":
            u = depth
        u0, v0, u1, v1 = KSW[swatch]
        uv = ((u0 + (u1 - u0) * u) / TEX["keys"][1], (v0 + (v1 - v0) * v) / TEX["keys"][2])
        length = math.sqrt(sum(value * value for value in normal))
        return point, uv, tuple(value / length for value in normal)

    for section in range(len(profile) - 1):
        z0, y0 = profile[section]
        z1, y1 = profile[section + 1]
        edges = [(0, -1, 0), (height, taper, 0), (0, z1 - z0, y0 - y1), (-height, taper, 0)]
        for edge, normal in enumerate(edges):
            next_edge = (edge + 1) % 4
            corners = [(section, edge), (section, next_edge), (section + 1, next_edge), (section + 1, edge)]
            swatch = "b_top" if edge == 2 else "b_side"
            yield [vertex(row, corner, swatch, normal) for row, corner in corners]

    yield [vertex(0, corner, "b_front", (0, 0, -1)) for corner in reversed(range(4))]
    yield [vertex(len(profile) - 1, corner, "b_side", (0, 0, 1)) for corner in range(4)]


def write_black_obj(path, frame, keys):
    positions, texcoords, normals = {}, {}, {}

    def index(values, point):
        point = tuple(round(value, 8) + 0.0 for value in point)
        return str(values.setdefault(point, len(values) + 1))

    faces = []
    for key in keys:
        for face in black_key_faces(frame, key["cx"]):
            faces.append("f " + " ".join("/".join((index(positions, pos), index(texcoords, uv), index(normals, normal)))
                                        for pos, uv, normal in face))
    with open(path, "w", newline="\n") as file:
        file.write("# Generated PX-S1100WE black keys\nmtllib black_keys.mtl\n")
        for prefix, values in [("v", positions), ("vt", texcoords), ("vn", normals)]:
            for point in values:
                file.write(prefix + " " + " ".join(format(value, ".8g") for value in point) + "\n")
        file.write("usemtl black_keys\n")
        file.write("\n".join(faces) + "\n")
    return len(faces)


def black_obj_model(ns, name, offset=(0, 0, 0)):
    obj = {"parent": "minecraft:block/block", "ambientocclusion": False,
           "textures": {"particle": ns + ":block/px_s1100/keys"},
           "loader": "neoforge:obj", "model": f"{ns}:models/block/px_s1100/{name}.obj",
           "automatic_culling": False, "emissive_ambient": False}
    if any(offset):
        obj["transform"] = {"translation": [round(value / 16, 8) for value in offset]}
    return obj


def with_black_keys(ns, solid, offset=(0, 0, 0)):
    result = {key: value for key, value in solid.items() if key != "elements"}
    result["loader"] = "neoforge:composite"
    result["children"] = {"solid": {key: value for key, value in solid.items() if key != "display"},
                          "black_keys": black_obj_model(ns, "black_keys", offset)}
    return result


# --------------------------------------------------------------------------- body parts
def diamond(f, cx, cz, y0, y1, side, top, bottom=None):
    """Square of half-diagonal CH centred on (cx, cz), turned 45 deg: one edge becomes a corner chamfer."""
    h = CH / math.sqrt(2)
    faces = {"front": side, "back": side, "left": side, "right": side, "top": top}
    if bottom:
        faces["bottom"] = bottom
    return box(f, cx - h, cx + h, y0, y1, cz - h, cz + h, faces, rot=("y", 45, (cx, y0, cz)))


def body_elements(f):
    white, under = sw("white"), sw("under")
    Y0 = 12.0
    els = [
        # recessed plinth (gives the shadow line under the case)
        box(f, 8, W - 8, 4, Y0, 8, D - 8, {"front": under, "back": under, "left": under, "right": under,
                                           "bottom": m_bottom}),
        # lower case under the keyboard
        box(f, CAP, W - CAP, Y0, 58, 0, PANEL_Z0, {"front": m_front, "bottom": under, "top": white}),
        # rear housing carrying the glossy touch panel
        box(f, CAP, W - CAP, Y0, H, PANEL_Z0, D, {"back": m_back, "top": m_panel, "bottom": under, "front": white}),
        # key slip
        box(f, KEY_X0, KEY_X1, 58, 78, 0, 8, {"front": m_front, "top": white, "back": sw("keybed")}),
        # key bed (dark, seen through the gaps)
        box(f, KEY_X0, KEY_X1, 58, 60, 8, PANEL_Z0, {"top": sw("keybed")}),
        # dark inner wall below the felt (what you see through the key gaps)
        box(f, KEY_X0, KEY_X1, 60, 84, 153.2, PANEL_Z0, {"front": sw("keybed")}),
        # red felt strip behind the keys
        box(f, KEY_X0, KEY_X1, 84, 89.5, 153.2, PANEL_Z0, {"top": sw("felt"), "front": sw("felt")}),
    ]
    # matte end caps: lower in front of the panel line, full height behind it, corners chamfered
    for left in (True, False):
        xo, xi = (0.0, CAP) if left else (W, W - CAP)          # outer / inner edge
        xc = CH if left else W - CH                            # chamfer line
        lo, hi = min(xc, xi), max(xc, xi)
        o0, o1 = min(xo, xc), max(xo, xc)
        outer = "left" if left else "right"
        inner = "right" if left else "left"
        els += [
            box(f, lo, hi, Y0, 92, 0, PANEL_Z0, {"front": m_front, "top": white, inner: white, "bottom": under}),
            box(f, o0, o1, Y0, 92, CH, PANEL_Z0, {outer: white, "top": white, "bottom": under}),
            diamond(f, xc, CH, Y0, 92, white, white, under),
            box(f, lo, hi, Y0, H, PANEL_Z0, D, {"back": m_back, "top": white, "front": white, "bottom": under}),
            box(f, o0, o1, Y0, H, PANEL_Z0, D - CH, {outer: white, "top": white, "front": white, "bottom": under}),
            diamond(f, xc, D - CH, Y0, H, white, white, under),
        ]
    rub = sw("rubber")
    for fx in (60, W - 90):
        for fz in (22, D - 52):
            els.append(box(f, fx, fx + 30, 0, 4, fz, fz + 30,
                           {"front": rub, "back": rub, "left": rub, "right": rub, "bottom": rub}))
    return els


def knob_elements(f):
    cx, cz = KNOB_C
    els = octagon(f, cx, cz, KNOB_R, H, H + KNOB_H, m_radial("knob_top", cx, cz, KNOB_R), ksw("knob_side"))
    els.append(box(f, cx - 1.3, cx + 1.3, H + KNOB_H, H + KNOB_H + 0.6, cz + 4.5, cz + 15.0,
                   {"top": ksw("dark"), "back": ksw("dark"), "left": ksw("dark"), "right": ksw("dark")}))
    return els


def power_elements(f):
    cx, cz = POWER_C
    return octagon(f, cx, cz, POWER_R, H, H + POWER_H, m_radial("power_top", cx, cz, POWER_R), ksw("knob_side"))


def led_rect(bid):
    if bid == "power":
        return (PLED_C[0] - 8, PLED_C[0] + 8, PLED_C[1] - 8, PLED_C[1] + 8)
    cx = [b[1] for b in BUTTONS if b[0] == bid][0]
    return (cx - BTN_HALF_W, cx + BTN_HALF_W, BTN_Z0, BTN_Z1)


def led_elements(f, bid):
    x0, x1, z0, z1 = led_rect(bid)
    return [box(f, x0, x1, H + 0.5, H + 0.5, z0, z1, {"top": m_lit}, emissive=True)]


def music_stand_elements(f):
    wb = sw("board")
    allf = {"front": wb, "back": wb, "left": wb, "right": wb, "top": wb, "bottom": wb}
    org = (W / 2, H + 6, D + 5)
    els = []
    for hx in MS_HOLES:                                            # legs + plugs into the back sockets
        els.append(box(f, hx - 5, hx + 5, 78, H + 10, D + 1, D + 9, allf))
        els.append(box(f, hx - 4, hx + 4, 80, 88, D - 6, D + 1, {"left": wb, "right": wb, "top": wb, "bottom": wb}))
    els += [
        box(f, MSTAND_X0, MSTAND_X1, H + 6, H + 214, D + 2, D + 6, allf, rot=("x", 22.5, org)),
        box(f, MSTAND_X0, MSTAND_X1, H + 6, H + 12, D - 22, D + 2,
            {"front": wb, "left": wb, "right": wb, "top": wb, "bottom": wb}, rot=("x", 22.5, org)),
        box(f, MSTAND_X0, MSTAND_X1, H + 12, H + 22, D - 22, D - 19,
            {"front": wb, "back": wb, "left": wb, "right": wb, "top": wb}, rot=("x", 22.5, org)),
    ]
    return els


def stand_elements(f):
    """Original stand (not a copy of any product): white post frame, pale wood top board and stretcher.
    Floor frame; the piano sits STAND_H above the floor."""
    w, wood, wend = sw("stand_white"), sw("wood"), sw("wood_end")
    allw = {"front": w, "back": w, "left": w, "right": w, "top": w, "bottom": w}
    els = [
        # top board the piano rests on
        box(f, -6, W + 6, STAND_H - 20, STAND_H, 4, D - 4,
            {"front": wood, "back": wood, "left": wend, "right": wend, "top": wood, "bottom": wood}),
        # rear stretcher (wood) and rear upper rail (white)
        box(f, 58, W - 58, 150, 196, 184, 204, {"front": wood, "back": wood, "top": wood, "bottom": wood}),
        box(f, 58, W - 58, STAND_H - 96, STAND_H - 20, 186, 202, {"front": w, "back": w, "bottom": w}),
    ]
    for x0 in (22.0, W - 58.0):
        els += [
            box(f, x0, x0 + 36, 14, STAND_H - 20, 22, 58, {"front": w, "back": w, "left": w, "right": w}),     # front post
            box(f, x0, x0 + 36, 14, STAND_H - 20, 174, 210, {"front": w, "back": w, "left": w, "right": w}),   # rear post
            box(f, x0 + 4, x0 + 32, 70, 100, 58, 174, {"left": w, "right": w, "top": w, "bottom": w}),         # side rail
            box(f, x0 + 4, x0 + 32, STAND_H - 60, STAND_H - 20, 58, 174, {"left": w, "right": w, "bottom": w}),
            box(f, x0 - 6, x0 + 42, 0, 14, -24, D + 24, allw),                                                 # floor foot
        ]
    return els


def pedal_elements(f):
    """Simple square sustain pedal. Local origin = centre of its footprint on the floor (x=0,z=0)."""
    d, m = sw("pedal_dark"), sw("pedal_metal")
    return [
        box(f, -40, 40, 0, 22, -50, 50, {"front": d, "back": d, "left": d, "right": d, "top": d, "bottom": d}),
        box(f, -36, 36, 22, 30, -50, 28, {"front": m, "back": m, "left": m, "right": m, "top": m}),
    ]


# --------------------------------------------------------------------------- texture painting
def font(path_candidates, size):
    for p in path_candidates:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


F_LABEL = ["/usr/share/fonts/opentype/inter/Inter-SemiBold.otf",
           "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
           "C:/Windows/Fonts/arialbd.ttf"]
F_LOGO = ["/usr/share/fonts/truetype/google-fonts/Lora-Italic-Variable.ttf",
          "/usr/share/fonts/truetype/dejavu/DejaVuSerif-BoldItalic.ttf",
          "C:/Windows/Fonts/georgiaz.ttf"]
SS = 4


def text_c(d, xy, s, fnt, fill):
    d.text(xy, s, font=fnt, fill=fill, anchor="mm")


def logo_layer(size_px, color_top=(222, 188, 104), color_bot=(168, 128, 52)):
    """Gold logo, returned as RGBA at SS scale with tight bbox."""
    f = font(F_LOGO, size_px * SS)
    try:
        f.set_variation_by_axes([700])
    except Exception:
        pass
    tmp = Image.new("L", (size_px * SS * 8, size_px * SS * 3), 0)
    dd = ImageDraw.Draw(tmp)
    dd.text((tmp.width // 2, tmp.height // 2), LOGO_TEXT, font=f, fill=255, anchor="mm")
    bb = tmp.getbbox()
    mask = tmp.crop(bb)
    grad = Image.new("RGBA", mask.size)
    gd = ImageDraw.Draw(grad)
    for y in range(mask.height):
        t = y / max(1, mask.height - 1)
        gd.line([(0, y), (mask.width, y)],
                fill=tuple(int(a + (b - a) * t) for a, b in zip(color_top, color_bot)) + (255,))
    grad.putalpha(mask)
    return grad


def paint_panel():
    w, h = 2048 * SS, 128 * SS
    img = Image.new("RGBA", (w, h), (247, 247, 245, 255))
    P = PPM * SS

    def X(x):
        return x * P

    def V(z):
        return (D - z) * P

    # glossy sheet: one broad diagonal reflection plus faint streaks
    gl = Image.new("L", (w, h), 0)
    gd = ImageDraw.Draw(gl)
    gd.polygon([(X(40), h), (X(520), 0), (X(40), 0)], fill=40)
    for x in range(600, 2100, 420):
        gd.polygon([(x * SS, h), ((x + 150) * SS, 0), ((x + 230) * SS, 0), ((x + 80) * SS, h)], fill=22)
    gl = gl.filter(ImageFilter.GaussianBlur(5 * SS))
    img = Image.composite(Image.new("RGBA", (w, h), (255, 255, 255, 255)), img, gl)
    shade = Image.new("L", (w, h), 0)
    ImageDraw.Draw(shade).polygon([(X(40), h), (X(520), 0), (w, 0), (w, h)], fill=10)
    img = Image.composite(Image.new("RGBA", (w, h), (226, 228, 232, 255)), img, shade.filter(ImageFilter.GaussianBlur(3 * SS)))
    d = ImageDraw.Draw(img)
    d.rectangle([0, V(PANEL_Z0) - 1.2 * SS, w, V(PANEL_Z0)], fill=(208, 208, 206, 255))
    d.rectangle([0, 0, w, 1.0 * SS], fill=(228, 228, 226, 255))
    grey = (160, 160, 162, 255)
    off = (188, 188, 190, 255)
    fl = font(F_LABEL, int(5.4 * SS))
    fs = font(F_LABEL, int(5.6 * SS))
    # power button ring + icon legend, volume ticks
    px, pz = POWER_C
    d.ellipse([X(px - 8), V(pz + 8), X(px + 8), V(pz - 8)], outline=(216, 216, 214, 255), width=SS)
    kx, kz = KNOB_C
    for i in range(11):
        a = math.radians(-150 + i * 30)
        r0, r1 = 21.0, (24.5 if i % 5 == 0 else 23.2)
        d.line([X(kx + r0 * math.sin(a)), V(kz + r0 * math.cos(a)), X(kx + r1 * math.sin(a)), V(kz + r1 * math.cos(a))],
               fill=grey, width=max(1, int(0.8 * SS)))
    lx, lz = PLED_C
    d.ellipse([X(lx - 1.6), V(lz + 1.6), X(lx + 1.6), V(lz - 1.6)], fill=off)
    draw_buttons(d, X, V, fl, off, lit=False)
    # small prints at both ends of the sheet
    text_c(d, (X(78.0), V(194.0)), "hayacio", fs, grey)
    text_c(d, (X(1234.0), V(194.0)), "88-KEY DIGITAL PIANO", font(F_LABEL, int(4.6 * SS)), grey)
    # octave markers above every C (and A0)
    for k in build_keys():
        if not k["black"] and (k["midi"] % 12 == 0 or k["midi"] == 21):
            d.line([X(k["cx"]), V(157.6), X(k["cx"]), V(160.0)], fill=grey, width=max(1, int(0.8 * SS)))
            text_c(d, (X(k["cx"]), V(164.2)), k["name"], fs, (176, 176, 178, 255))
    # gold logo
    lg = logo_layer(13)
    img.alpha_composite(lg, (int(X(LOGO_C[0]) - lg.width / 2), int(V(LOGO_C[1]) - lg.height / 2)))
    return img.resize((2048, 128), Image.LANCZOS)


def draw_buttons(d, X, V, fl, col, lit):
    for bid, cx, lines in BUTTONS:
        if lines:
            if len(lines) == 1:
                text_c(d, (X(cx), V(BTN_Z)), lines[0], fl, col)
            else:
                text_c(d, (X(cx), V(BTN_Z + 2.4)), lines[0], fl, col)
                text_c(d, (X(cx), V(BTN_Z - 2.4)), lines[1], fl, col)
        elif bid == "play":
            d.polygon([(X(cx - 8), V(BTN_Z + 4)), (X(cx - 8), V(BTN_Z - 4)), (X(cx - 1.5), V(BTN_Z))], fill=col)
            d.rectangle([X(cx + 1.5), V(BTN_Z + 3.4), X(cx + 8), V(BTN_Z - 3.4)], fill=col)
        elif bid == "rec":
            c = (236, 56, 48, 255) if lit else col
            d.ellipse([X(cx - 4), V(BTN_Z + 4), X(cx + 4), V(BTN_Z - 4)], fill=c)
        # indicator dot
        dc = col if not lit else ((255, 96, 84, 255) if bid == "rec" else (255, 255, 255, 255))
        d.ellipse([X(cx - 1.3), V(LED_Z + 1.3), X(cx + 1.3), V(LED_Z - 1.3)], fill=dc)


def paint_panel_lit():
    w, h = 2048 * SS, 128 * SS
    P = PPM * SS

    def X(x):
        return x * P

    def V(z):
        return (D - z) * P

    fl = font(F_LABEL, int(5.4 * SS))
    core = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    dc = ImageDraw.Draw(core)
    draw_buttons(dc, X, V, fl, (255, 255, 255, 255), lit=True)
    lx, lz = PLED_C
    dc.ellipse([X(lx - 1.8), V(lz + 1.8), X(lx + 1.8), V(lz - 1.8)], fill=(190, 222, 255, 255))
    alpha = core.getchannel("A")
    # cool glow + thin dark rim so the lit legend stays readable on a white panel in daylight
    glow_a = alpha.filter(ImageFilter.GaussianBlur(1.5 * SS)).point(lambda v: min(255, int(v * 2.2)))
    glow = Image.new("RGBA", (w, h), (64, 132, 236, 0))
    glow.putalpha(glow_a.point(lambda v: int(v * 0.55)))
    rim_a = alpha.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.GaussianBlur(0.25 * SS))
    rim = Image.new("RGBA", (w, h), (26, 44, 92, 0))
    rim.putalpha(rim_a.point(lambda v: int(v * 0.8)))
    out = Image.alpha_composite(Image.alpha_composite(glow, rim), core)
    out = out.resize((2048, 128), Image.LANCZOS)
    # hard-ish alpha so it also works on a cutout layer
    a = out.getchannel("A").point(lambda v: 0 if v < 40 else v)
    out.putalpha(a)
    return out


def paint_shell():
    w, h = 2048 * SS, 512 * SS
    body = (240, 240, 236, 255)
    img = Image.new("RGBA", (w, h), body)
    d = ImageDraw.Draw(img)
    P = PPM * SS
    fs = font(F_LABEL, int(4.6 * SS))
    lab = (150, 150, 152, 255)

    # ---- back (seen from behind): u = (W - x), v = (H - y)
    def BX(x):
        return (W - x) * P

    def BY(y):
        return (BACK_V0 + (H - y) * PPM) * SS

    d.rectangle([0, BY(H), w, BY(4)], fill=(239, 239, 235, 255))
    d.rectangle([0, BY(H), w, BY(H - 1.6)], fill=(250, 250, 248, 255))
    d.rectangle([0, BY(15), w, BY(12)], fill=(222, 222, 218, 255))
    # speaker grilles: stadium-shaped cloth mesh
    mesh = Image.new("RGBA", (w, int(160 * SS)), (0, 0, 0, 0))
    md = ImageDraw.Draw(mesh)
    for (gx0, gx1) in SPK:
        y0, y1 = BY(SPK_Y[1]), BY(SPK_Y[0])
        rad = (y1 - y0) / 2
        md.rounded_rectangle([BX(gx1) - 1.2 * P, y0 - 1.2 * P, BX(gx0) + 1.2 * P, y1 + 1.2 * P], radius=rad + 1.2 * P, fill=(224, 224, 220, 255))
        md.rounded_rectangle([BX(gx1), y0, BX(gx0), y1], radius=rad, fill=(204, 204, 201, 255))
    dots = Image.new("L", (w, int(160 * SS)), 0)
    dd = ImageDraw.Draw(dots)
    step = 2.6 * P
    yy, row = 0.0, 0
    while yy < dots.height:
        xx = (step / 2 if row % 2 else 0.0)
        while xx < w:
            dd.ellipse([xx - 0.62 * P, yy - 0.62 * P, xx + 0.62 * P, yy + 0.62 * P], fill=255)
            xx += step
        yy += step * 0.86
        row += 1
    grille_mask = mesh.getchannel("A").point(lambda v: 255 if v > 0 else 0)
    inner = Image.new("L", mesh.size, 0)
    idr = ImageDraw.Draw(inner)
    for (gx0, gx1) in SPK:
        y0, y1 = BY(SPK_Y[1]), BY(SPK_Y[0])
        idr.rounded_rectangle([BX(gx1), y0, BX(gx0), y1], radius=(y1 - y0) / 2, fill=255)
    from PIL import ImageChops
    dark = Image.new("RGBA", mesh.size, (176, 176, 174, 255))
    mesh = Image.composite(dark, mesh, ImageChops.multiply(dots, inner))
    img.alpha_composite(mesh, (0, 0))
    d = ImageDraw.Draw(img)
    # recessed jack strip (upper area next to the bass-end grille)
    jx0, jx1, jy0, jy1 = JACK_PILL
    d.rounded_rectangle([BX(jx1), BY(jy1), BX(jx0), BY(jy0)], radius=(BY(jy0) - BY(jy1)) / 2, fill=(226, 226, 222, 255),
                        outline=(204, 204, 200, 255), width=SS)
    for name, x, kind in JACKS:
        cx, cy = BX(x), BY(JACK_Y)
        if kind == "q":
            d.ellipse([cx - 6 * P, cy - 6 * P, cx + 6 * P, cy + 6 * P], fill=(26, 26, 28, 255))
            d.ellipse([cx - 3.4 * P, cy - 3.4 * P, cx + 3.4 * P, cy + 3.4 * P], fill=(6, 6, 8, 255))
        elif kind == "dc":
            d.ellipse([cx - 5 * P, cy - 5 * P, cx + 5 * P, cy + 5 * P], fill=(24, 24, 26, 255))
            d.ellipse([cx - 1.3 * P, cy - 1.3 * P, cx + 1.3 * P, cy + 1.3 * P], fill=(196, 196, 198, 255))
        elif kind == "din":
            d.ellipse([cx - 6.6 * P, cy - 6.6 * P, cx + 6.6 * P, cy + 6.6 * P], fill=(182, 184, 188, 255))
            d.ellipse([cx - 4.8 * P, cy - 4.8 * P, cx + 4.8 * P, cy + 4.8 * P], fill=(18, 18, 20, 255))
            for k in range(6):
                ang = math.radians(30 + k * 60)
                d.ellipse([cx + 2.8 * P * math.cos(ang) - 0.6 * P, cy + 2.8 * P * math.sin(ang) - 0.6 * P,
                           cx + 2.8 * P * math.cos(ang) + 0.6 * P, cy + 2.8 * P * math.sin(ang) + 0.6 * P], fill=(170, 150, 90, 255))
        text_c(d, (cx, BY(jy0 - 5.0)), name, fs, lab)
    d.line([BX(308), BY(jy0 - 9.2), BX(281), BY(jy0 - 9.2)], fill=lab, width=max(1, int(0.5 * SS)))
    text_c(d, (BX(294.5), BY(jy0 - 12.6)), "LINE OUT", fs, lab)
    # music-stand sockets
    for hx in MS_HOLES:
        d.rounded_rectangle([BX(hx + 4.5), BY(89), BX(hx - 4.5), BY(79)], radius=2 * SS, fill=(40, 40, 42, 255))
    # USB recess (lower centre)
    d.rounded_rectangle([BX(706), BY(37), BX(608), BY(17)], radius=(BY(17) - BY(37)) / 2, fill=(228, 228, 224, 255),
                        outline=(206, 206, 202, 255), width=SS)
    cx, cy = BX(USB_B), BY(USB_Y)
    d.rounded_rectangle([cx - 5 * P, cy - 5 * P, cx + 5 * P, cy + 5 * P], radius=1.2 * SS, fill=(20, 20, 22, 255))
    d.rectangle([cx - 2.4 * P, cy - 1.5 * P, cx + 2.4 * P, cy + 1.5 * P], fill=(206, 206, 210, 255))
    cx = BX(USB_A)
    d.rectangle([cx - 7 * P, cy - 3.2 * P, cx + 7 * P, cy + 3.2 * P], fill=(20, 20, 22, 255))
    d.rectangle([cx - 5.6 * P, cy - 0.2 * P, cx + 5.6 * P, cy + 1.7 * P], fill=(206, 206, 210, 255))
    text_c(d, (BX(636), BY(USB_Y)), "USB", fs, lab)
    # gold logo, centred
    lg = logo_layer(26)
    img.alpha_composite(lg, (int(BX(W / 2) - lg.width / 2), int(BY(62) - lg.height / 2)))
    d = ImageDraw.Draw(img)

    # ---- front: u = x, v = FRONT_V0 + (92 - y)
    def FY(y):
        return (FRONT_V0 + (92.0 - y) * PPM) * SS

    d.rectangle([0, FY(92), w, FY(4)], fill=body)
    d.rectangle([CAP * P, FY(59), (W - CAP) * P, FY(57.4)], fill=(224, 224, 220, 255))   # seam under the key slip
    d.rectangle([0, FY(15), w, FY(12)], fill=(222, 222, 218, 255))
    for (jx, jy) in PHONES:                                               # 2 x PHONES, stacked on the bass-end cap
        cx, cy = jx * P, FY(jy)
        d.ellipse([cx - 5.4 * P, cy - 5.4 * P, cx + 5.4 * P, cy + 5.4 * P], fill=(210, 210, 206, 255))
        d.ellipse([cx - 4.0 * P, cy - 4.0 * P, cx + 4.0 * P, cy + 4.0 * P], fill=(30, 30, 32, 255))
        d.ellipse([cx - 2.0 * P, cy - 2.0 * P, cx + 2.0 * P, cy + 2.0 * P], fill=(6, 6, 8, 255))
    text_c(d, (33.0 * P, FY(37.5)), "PHONES", font(F_LABEL, int(3.8 * SS)), lab)

    # ---- bottom: u = x * PPB, v = BOTTOM_V0 + z * PPB
    B = PPB * SS

    def BV(z):
        return BOTTOM_V0 * SS + z * B

    d.rectangle([0, BV(0), W * B, BV(D)], fill=(224, 224, 220, 255))
    d.rounded_rectangle([900 * B, BV(56), 1150 * B, BV(176)], radius=4 * SS, outline=(176, 176, 172, 255), width=SS)
    text_c(d, (1025 * B, BV(116)), "AA x 6", font(F_LABEL, int(9 * SS)), (160, 160, 158, 255))
    d.rectangle([520 * B, BV(80), 800 * B, BV(150)], fill=(206, 206, 202, 255))
    for sx in (120, 330, 661, 992, 1202):
        for sz in (30, 202):
            d.ellipse([sx * B - 3 * SS, BV(sz) - 3 * SS, sx * B + 3 * SS, BV(sz) + 3 * SS], fill=(120, 120, 118, 255))

    # ---- swatches
    cols = {"white": (240, 240, 236), "keybed": (34, 34, 36), "felt": (146, 22, 30), "rubber": (28, 28, 28),
            "board": (244, 244, 241), "gold": (201, 162, 74), "pedal_dark": (44, 44, 48),
            "pedal_metal": (172, 174, 178), "stand_white": (236, 236, 232), "under": (206, 206, 202),
            "wood": (214, 184, 140), "wood_end": (190, 158, 114)}
    for n, (u0, v0, u1, v1) in SW.items():
        d.rectangle([(u0 - 4) * SS, (v0 - 4) * SS, (u1 + 4) * SS, (v1 + 4) * SS], fill=cols[n] + (255,))
    # light grain on the wood swatch
    u0, v0, u1, v1 = SW["wood"]
    for i, yy in enumerate(range(v0 - 3, v1 + 4, 5)):
        d.line([(u0 - 4) * SS, yy * SS, (u1 + 4) * SS, (yy + (i % 2)) * SS], fill=(202, 170, 126, 255), width=SS)
    return img.resize((2048, 512), Image.LANCZOS)


def paint_keys():
    s = 8
    img = Image.new("RGBA", (64 * s, 64 * s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    def R(x0, y0, x1, y1, c):
        d.rectangle([x0 * s, y0 * s, x1 * s - 1, y1 * s - 1], fill=c + (255,))

    # white key top (rear part v 0..32, front part v 32..48)
    for y in range(48 * s):
        t = y / (48 * s)
        c = int(238 + 10 * t)
        d.line([(0, y), (16 * s, y)], fill=(c, c, c - 4, 255))
    R(0, 46.5, 16, 48, (252, 252, 250))
    R(16, 0, 32, 16, (232, 231, 226))
    R(16, 12, 32, 16, (214, 213, 208))
    R(16, 16, 32, 32, (198, 197, 193))
    R(16, 32, 32, 48, (30, 30, 32))            # black key front
    R(32, 0, 44, 48, (30, 30, 33))             # black key top
    R(33.5, 0, 34.5, 48, (52, 52, 56))
    R(44, 0, 56, 48, (17, 17, 19))             # black key side
    # power button top
    R(0, 48, 16, 64, (214, 214, 212))
    d.ellipse([1.2 * s, 49.2 * s, 14.8 * s, 62.8 * s], fill=(236, 236, 234, 255))
    d.ellipse([4.0 * s, 52.0 * s, 12.0 * s, 60.0 * s], outline=(150, 150, 154, 255), width=int(0.9 * s))
    # knob top / side / dark
    R(16, 48, 32, 64, (208, 208, 206))
    d.ellipse([17.0 * s, 49.0 * s, 31.0 * s, 63.0 * s], fill=(230, 230, 228, 255))
    d.ellipse([18.8 * s, 50.8 * s, 29.2 * s, 61.2 * s], outline=(204, 204, 202, 255), width=int(0.6 * s))
    R(32, 48, 48, 64, (204, 204, 202))
    for i in range(8):
        R(32.6 + i * 2, 48, 33.4 + i * 2, 64, (182, 182, 180))
    R(48, 48, 64, 64, (58, 58, 62))
    return img.resize((64, 64), Image.LANCZOS)


# --------------------------------------------------------------------------- output
def model(ns, elements, extra=None, tex_keys=("shell", "panel", "lit", "keys"), particle="keys"):
    used = set()
    for e in elements:
        for fc in e["faces"].values():
            used.add(fc["texture"][1:])
    textures = {k: f"{ns}:block/px_s1100/{TEX[k][0]}" for k in tex_keys if k in used}
    textures["particle"] = f"{ns}:block/px_s1100/{TEX[particle][0]}"
    m = {"credit": "PX-S1100WE true-scale model (generated by tools/gen_px_s1100.py)",
         "parent": "minecraft:block/block", "ambientocclusion": False,
         "textures": textures, "elements": elements}
    if extra:
        m.update(extra)
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--namespace", default="hayacio")
    ap.add_argument("--out", default="out")
    a = ap.parse_args()
    ns = a.namespace
    mdir = os.path.join(a.out, "assets", ns, "models", "block", "px_s1100")
    idir = os.path.join(a.out, "assets", ns, "models", "item")
    tdir = os.path.join(a.out, "assets", ns, "textures", "block", "px_s1100")
    for p in (mdir, idir, tdir):
        os.makedirs(p, exist_ok=True)

    paint_panel().save(os.path.join(tdir, "panel.png"))
    paint_panel_lit().save(os.path.join(tdir, "panel_lit.png"))
    paint_shell().save(os.path.join(tdir, "shell.png"))
    paint_keys().save(os.path.join(tdir, "keys.png"))

    keys = build_keys()
    black_keys = [key for key in keys if key["black"]]
    white_keys = [key for key in keys if not key["black"]]
    table = Frame()                       # piano resting on y = 0
    base = table.raised(STAND_H)          # default: piano on its stand
    cut = {"render_type": "minecraft:cutout"}
    black_faces = write_black_obj(os.path.join(mdir, "black_keys.obj"), base, black_keys)
    with open(os.path.join(mdir, "black_keys.mtl"), "w", newline="\n") as material:
        material.write(f"newmtl black_keys\nKd 1 1 1\nmap_Kd {ns}:block/px_s1100/keys\n")

    def dump(name, m, d=mdir):
        with open(os.path.join(d, name + ".json"), "w", newline="\n") as fp:
            json.dump(m, fp, separators=(",", ":"))

    def assembled(fr, with_stand, with_music_stand=True):
        els = body_elements(fr)
        for k in white_keys:
            els += white_key_elements(fr, k)
        els += knob_elements(fr) + power_elements(fr)
        if with_music_stand:
            els += music_stand_elements(fr)
        if with_stand:
            els += stand_elements(table)
        return els

    # static parts in the default (on-stand) frame
    dump("body", model(ns, body_elements(base)))
    dump("stand", model(ns, stand_elements(table), particle="shell"))
    dump("music_stand", model(ns, music_stand_elements(base)))
    dump("keys_static", with_black_keys(ns, model(ns, [e for k in white_keys for e in white_key_elements(base, k)])))
    dump("full", with_black_keys(ns, model(ns, assembled(base, True))))
    # table-top variants (no stand, piano on y = 0)
    dump("body_tabletop", model(ns, body_elements(table)))
    dump("full_tabletop", with_black_keys(ns, model(ns, assembled(table, False)), (0, -STAND_H * S, 0)))

    # animated partials, authored around their pivot (model origin = pivot)
    shapes = {}
    for k in keys:
        if k["shape"] not in shapes:
            shapes[k["shape"]] = k
            fr = Frame((k["cx"], PIVOT_Y, PIVOT_Z), (0.0, 0.0, 0.0))
            if k["black"]:
                write_black_obj(os.path.join(mdir, "key_black.obj"), fr, [k])
                dump(k["shape"], black_obj_model(ns, "key_black"))
            else:
                dump(k["shape"], model(ns, white_key_elements(fr, k)))
    dump("volume_knob", model(ns, knob_elements(Frame((KNOB_C[0], H, KNOB_C[1]), (0, 0, 0)))))
    dump("power_button", model(ns, power_elements(Frame((POWER_C[0], H, POWER_C[1]), (0, 0, 0)))))
    dump("pedal", model(ns, pedal_elements(Frame((0, 0, 0), (0, 0, 0))), particle="shell"))
    leds = ["power"] + [b[0] for b in BUTTONS]
    for bid in leds:
        dump("led_" + bid, model(ns, led_elements(base, bid), extra=cut, particle="lit"))

    # item model: stand + piano, re-centred on the block centre
    item_frame = Frame(model_off=(8.0, STAND_H * S + 1.5, 8.0))
    item_table = Frame(model_off=(8.0, 1.5, 8.0))
    iels = body_elements(item_frame)
    for k in white_keys:
        iels += white_key_elements(item_frame, k)
    iels += knob_elements(item_frame) + power_elements(item_frame) + stand_elements(item_table)
    item = model(ns, iels)
    item["display"] = {
        "gui": {"rotation": [24, 215, 0], "translation": [0, 0, 0], "scale": [0.68, 0.68, 0.68]},
        "ground": {"translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
        "fixed": {"rotation": [0, 180, 0], "scale": [0.7, 0.7, 0.7]},
        "thirdperson_righthand": {"rotation": [60, 0, 0], "translation": [0, 1, 1], "scale": [0.4, 0.4, 0.4]},
        "thirdperson_lefthand": {"rotation": [60, 0, 0], "translation": [0, 1, 1], "scale": [0.4, 0.4, 0.4]},
        "firstperson_righthand": {"rotation": [0, 200, 0], "translation": [2, 1, 0], "scale": [0.4, 0.4, 0.4]},
        "firstperson_lefthand": {"rotation": [0, 200, 0], "translation": [2, 1, 0], "scale": [0.4, 0.4, 0.4]},
        "head": {"translation": [0, 4, 0], "scale": [1, 1, 1]},
    }
    dump("px_s1100", with_black_keys(ns, item, tuple(a - b for a, b in zip(item_frame.mo, base.mo))), idir)

    # ----- layout data for the implementation side
    def mp(x, y, z):
        return [r4(c) for c in base.to_model(x, y, z)]

    def rect(x0, x1, z0, z1, y):
        a_, b_ = base.to_model(x1, y, z0), base.to_model(x0, y, z1)
        return {"x": [r4(a_[0]), r4(b_[0])], "z": [r4(a_[2]), r4(b_[2])], "y": r4(a_[1])}

    lk = []
    for k in keys:
        e = {"midi": k["midi"], "name": k["name"], "black": k["black"], "model": k["shape"],
             "pivot": mp(k["cx"], PIVOT_Y, PIVOT_Z)}
        if k["black"]:
            e["hit"] = [rect(k["cx"] - BLACK_W / 2, k["cx"] + BLACK_W / 2, BLACK_Z0, KEY_Z1, BLACK_Y1)]
        else:
            e["hit"] = [rect(k["x0"], k["x1"], KEY_Z0, KEY_ZSPLIT, WHITE_Y1),
                        rect(k["rl"], k["rr"], KEY_ZSPLIT, KEY_Z1, WHITE_Y1)]
        lk.append(e)
    ctrl = {"power_button": {"model": "power_button", "pivot": mp(POWER_C[0], H, POWER_C[1]),
                             "press_dy": r4(-1.5 * S),
                             "hit": rect(POWER_C[0] - 9, POWER_C[0] + 9, POWER_C[1] - 9, POWER_C[1] + 9, H + POWER_H)},
            "volume_knob": {"model": "volume_knob", "pivot": mp(KNOB_C[0], H, KNOB_C[1]),
                            "yaw_deg_at_min": 150.0, "yaw_deg_at_max": -150.0,
                            "hit": rect(KNOB_C[0] - KNOB_R, KNOB_C[0] + KNOB_R, KNOB_C[1] - KNOB_R,
                                        KNOB_C[1] + KNOB_R, H + KNOB_H)}}
    touch = {}
    for bid in leds:
        x0, x1, z0, z1 = led_rect(bid)
        touch[bid] = {"led_model": "led_" + bid, "hit": rect(x0, x1, z0, z1, H)}
    a_, b_ = base.to_model(W, 0, 0), base.to_model(0, H, D)
    layout = {
        "about": "PX-S1100WE model layout. Units: model px (1/16 block) in the default frame (piano ON its stand). "
                 "Front faces NORTH; bass is +X. Frame origin = main block corner; second block is at +X. "
                 "For the table-top variant add tabletop_dy_px to every y.",
        "tabletop_dy_px": r4(-STAND_H * S),
        "namespace": ns,
        "scale_mm_per_px": 62.5,
        "real_size_mm": [W, D, H],
        "body_aabb": {"from": [r4(c) for c in a_], "to": [r4(c) for c in b_]},
        "stand": {"model": "stand", "height_px": r4(STAND_H * S),
                  "aabb": {"from": [r4(c) for c in table.to_model(W + 6, 0, -24)],
                           "to": [r4(c) for c in table.to_model(-6, STAND_H, D + 24)]}},
        "music_stand": {"model": "music_stand"},
        "pedal": {"model": "pedal", "note": "local origin = footprint centre on the floor; place freely"},
        "key": {"press_axis": "x", "press_deg": -PRESS_DEG,
                "note": "translate(pivot/16) -> rotate X by press_deg*t -> render key model (models are authored around the pivot)"},
        "keys": lk,
        "controls": ctrl,
        "touch_buttons": touch,
        "jacks": {"phones": [mp(PHONES[0][0], PHONES[0][1], 0), mp(PHONES[1][0], PHONES[1][1], 0)],
                  "dc_in": mp(240, JACK_Y, D), "pedal_unit": mp(349, JACK_Y, D), "damper": mp(371, JACK_Y, D),
                  "line_out_r": mp(305, JACK_Y, D), "line_out_l": mp(284, JACK_Y, D),
                  "usb_b": mp(USB_B, USB_Y, D), "usb_a": mp(USB_A, USB_Y, D)},
        "speakers": [mp(sum(SPK[0]) / 2, 49, D), mp(sum(SPK[1]) / 2, 49, D)],
        "battery_door": rect(900, 1150, 56, 176, 4),
    }
    layout_dir = os.path.join(a.out, "assets", ns, "px_s1100")
    os.makedirs(layout_dir, exist_ok=True)
    with open(os.path.join(layout_dir, "layout.json"), "w", newline="\n") as fp:
        json.dump(layout, fp, indent=1)

    n = sum(len(white_key_elements(base, k)) for k in white_keys)
    print("keys:", len(keys), "white-key elements:", n, "black-key mesh faces:", black_faces)
    print("body aabb:", layout["body_aabb"])


if __name__ == "__main__":
    main()
