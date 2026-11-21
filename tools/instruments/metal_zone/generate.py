#!/usr/bin/env python3
"""Build MT-2 meshes and original panel artwork in Minecraft resource-pack format."""
import json
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / "src/main/resources/assets/create_media_systems"
MODELS = ASSETS / "models/block/metal_zone"
TEXTURES = ASSETS / "textures/block/metal_zone"
PREFIX = "create_media_systems:block/metal_zone/"
MODEL_FILES = "create_media_systems:models/block/metal_zone/"
ORANGE = (249, 137, 44)
WHITE = (239, 239, 233)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
Z_KNOB = 2.91
Z_LAMP = 1.48
RNG = random.Random(72059)


def save_json(file, value):
    file.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def noisy(size, base, grain=5):
    image = Image.new("RGB", size)
    for y in range(size[1]):
        for x in range(size[0]):
            value = RNG.randint(-grain, grain)
            image.putpixel((x, y), tuple(max(0, min(255, v + value)) for v in base))
    return image


def label(draw, xy, text, size, color=WHITE, anchor="mt"):
    draw.text(xy, text, font=ImageFont.truetype(FONT, size), fill=color, anchor=anchor)


def wordmark(image, text, center, width, height, color):
    mask = Image.new("L", (512, 90))
    label(ImageDraw.Draw(mask), (0, 2), text, 60, 255, "lt")
    mask = mask.crop(mask.getbbox()).resize((width, height), Image.Resampling.LANCZOS)
    xy = (center[0] - width // 2, center[1] - height // 2)
    image.paste(color, xy, mask)
    return mask, xy


def draw_textures():
    for name, base, grain in (
        ("cast", (53, 56, 60), 7), ("seam", (26, 28, 29), 3), ("cap", (31, 33, 34), 3),
        ("cap_edge", (47, 49, 50), 4), ("black", (11, 12, 13), 2),
        ("steel", (161, 169, 173), 8), ("washer", (92, 98, 101), 5),
        ("white_pointer", (219, 222, 218), 1), ("ring_pointer", (151, 165, 166), 2),
        ("lens_dark_edge", (88, 12, 16), 5), ("lens_dark", (138, 18, 22), 5),
        ("lens_on_edge", (208, 25, 27), 3), ("lens_on", (255, 67, 62), 5),
    ):
        noisy((16, 16), base, grain).save(TEXTURES / (name + ".png"))
    panel = noisy((256, 128), (55, 59, 62), 3)
    ink = ImageDraw.Draw(panel)
    ink.rounded_rectangle((1, 1, 254, 126), radius=6, outline=(76, 79, 81), width=1)
    label(ink, (128, 4), "CHECK", 13)
    label(ink, (26, 26), "LEVEL", 11)
    label(ink, (128, 26), "EQUALIZER", 13)
    label(ink, (231, 26), "DIST", 11)
    for x, left, right in (
        (26, "MIN", "MAX"), (94, "-15", "+15"), (162, "200", "5k"), (230, "MIN", "MAX")
    ):
        for edge, value in ((x - 16, left), (x + 16, right)):
            ink.ellipse((edge - 1, 86, edge + 1, 88), fill=WHITE)
            label(ink, (edge, 93), value, 8)
    label(ink, (93, 104), "HIGH", 10)
    label(ink, (93, 116), "LOW", 10)
    label(ink, (167, 104), "MIDDLE", 10)
    label(ink, (167, 116), "MID FREQ", 10)
    for x, sign in ((62, 1), (207, -1)):
        ink.ellipse((x - 3, 105, x + 3, 111), outline=WHITE, width=1)
        ink.line([(x, 111), (x, 119), (x + sign * 10, 119)], fill=WHITE, width=1)
    panel.save(TEXTURES / "controls.png")

    tread = noisy((256, 256), (51, 54, 58), 6)
    ink = ImageDraw.Draw(tread)
    for x in (17, 230):
        ink.line([(x + 6, 10), (x, 17), (x + 6, 24)], fill=ORANGE, width=3)
        ink.line([(x, 17), (x + 15, 17)], fill=ORANGE, width=3)
    label(ink, (68, 10), "OUTPUT", 15, ORANGE)
    label(ink, (195, 10), "INPUT", 15, ORANGE)
    wordmark(tread, "Metal Zone", (128, 63), 217, 26, ORANGE)
    label(ink, (224, 82), "MT-2", 23, ORANGE, "rt")
    tread.save(TEXTURES / "tread.png")

    pad = noisy((256, 128), (24, 26, 27), 5)
    ink = ImageDraw.Draw(pad)
    ink.rounded_rectangle((0, 0, 255, 127), radius=10, outline=(13, 14, 15), width=5)
    ink.rounded_rectangle((5, 5, 250, 122), radius=7, outline=(44, 46, 47), width=1)
    mask, position = wordmark(pad, "BOSS", (147, 30), 138, 22, (34, 36, 37))
    pad.paste((9, 10, 11), (position[0] + 2, position[1] + 3), mask.filter(ImageFilter.MaxFilter(5)))
    pad.paste((53, 55, 56), (position[0] - 1, position[1] - 1), mask)
    pad.paste((33, 35, 36), position, mask)
    ink.rounded_rectangle((45, 17, 69, 43), radius=3, outline=(47, 49, 51), width=2)
    ink.arc((48, 19, 66, 39), 3, 277, fill=(54, 56, 58), width=2)
    ink.line([(65, 27), (65, 19), (61, 19)], fill=(54, 56, 58), width=2)
    pad.save(TEXTURES / "rubber.png")


class Geometry:
    def __init__(self):
        self.faces = []

    def face(self, material, points, uv=None):
        if uv is None:
            uv = ((.10, .10), (.88, .10), (.88, .88), (.10, .88))[:len(points)]
        self.faces.append((material, tuple(points), tuple(uv)))

    def extend(self, other, dx=0, dz=0, angle=0):
        cosine = math.cos(math.radians(angle))
        sine = math.sin(math.radians(angle))
        for material, points, uv in other.faces:
            transformed = []
            for x, y, z in points:
                xr, zr = x - 8, z - 8
                transformed.append((8 + dx + xr * cosine + zr * sine, y,
                                    8 + dz - xr * sine + zr * cosine))
            self.face(material, transformed, uv)

    def write(self, path):
        vertices, texcoords, faces = {}, {}, []
        last = None
        for material, points, uv in self.faces:
            if material != last:
                faces.append(f"usemtl {material}")
                last = material
            entries = []
            for position, coord in zip(points, uv):
                vertex = tuple(round(v / 16, 6) for v in position)
                texture = tuple(round(v, 6) for v in coord)
                vertex_id = vertices.setdefault(vertex, len(vertices) + 1)
                texture_id = texcoords.setdefault(texture, len(texcoords) + 1)
                entries.append(f"{vertex_id}/{texture_id}")
            faces.append("f " + " ".join(entries))
        lines = ["# Generated by tools/instruments/metal_zone/generate.py", "mtllib metal_zone.mtl", "g pedal"]
        lines.extend("v %.6f %.6f %.6f" % point for point in vertices)
        lines.extend("vt %.6f %.6f" % uv for uv in texcoords)
        path.write_text("\n".join(lines + faces) + "\n", encoding="ascii")


def rounded_rect(x0, x1, z0, z1, radius, inset=0):
    x0, x1, z0, z1 = x0 + inset, x1 - inset, z0 + inset, z1 - inset
    r = max(.035, radius - inset)
    corners = (
        (x1 - r, z0 + r, -90), (x1 - r, z1 - r, 0),
        (x0 + r, z1 - r, 90), (x0 + r, z0 + r, 180)
    )
    return [(x + r * math.cos(math.radians(a + step * 30)),
             z + r * math.sin(math.radians(a + step * 30)))
            for x, z, a in corners for step in range(4)]


def strip(mesh, lower, upper, material, flip=False):
    for i, a in enumerate(lower):
        j = (i + 1) % len(lower)
        points = (a, upper[i], upper[j], lower[j])
        mesh.face(material, points[::-1] if flip else points)


def deck(mesh, box, radius, rings, side, top=None):
    x0, x1, z0, z1 = box
    rows = [tuple((x, height, z) for x, z in rounded_rect(*box, radius, inset)) for height, inset in rings]
    for lower, upper in zip(rows, rows[1:]):
        strip(mesh, lower, upper, side)
    for row, material, up in ((rows[0], side, False), (rows[-1], top or side, True)):
        center = ((x0 + x1) / 2, row[0][1], (z0 + z1) / 2)
        for i, corner in enumerate(row):
            next_corner = row[(i + 1) % len(row)]
            points = (center, next_corner, corner) if up else (center, corner, next_corner)
            coords = [((x - x0) / (x1 - x0), (z - z0) / (z1 - z0)) for x, _, z in points]
            mesh.face(material, points, coords)


def round_part(mesh, center, axis, rings, segments=24, cap=None, ribbed=False):
    cx, cy, cz = center

    def point(height, radius, i):
        angle = 2 * math.pi * i / segments
        valley = .045 if ribbed and i % 2 else 0
        u = (radius - valley) * math.cos(angle)
        v = (radius - valley) * math.sin(angle)
        if axis == "y":
            return cx + u, cy + height, cz + v
        if axis == "z":
            return cx + u, cy - v, cz + height
        if axis == "-z":
            return cx + u, cy + v, cz - height
        return cx + height * axis, cy + u, cz + axis * v

    rows = [[point(h, radius, i) for i in range(segments)] for h, radius, _ in rings]
    inverted = isinstance(axis, int)
    for i, (lower, upper) in enumerate(zip(rows, rows[1:])):
        strip(mesh, lower, upper, rings[i + 1][2], flip=inverted)
    if cap is not None:
        base = point(rings[-1][0], 0, 0)
        for i, a in enumerate(rows[-1]):
            b = rows[-1][(i + 1) % segments]
            mesh.face(cap, (base, a, b) if inverted else (base, b, a))


def body_model():
    mesh = Geometry()
    # 15 model units = 129 mm. The left and right jack rims define the 73 mm width.
    deck(mesh, (4.16, 11.84, .74, 15.05), .37, ((0.0, .14), (.19, 0)), "black")
    deck(mesh, (4.10, 11.90, .57, 15.25), .43,
         ((.16, .24), (.39, .04), (.58, 0), (3.06, 0), (3.31, .13)), "cast")
    deck(mesh, (4.12, 11.88, .61, 5.46), .42,
         ((3.20, .11), (3.42, 0), (4.96, 0), (5.15, .13)), "cast")
    deck(mesh, (4.24, 11.76, .76, 5.39), .24, ((5.125, .01), (5.19, 0)), "seam", "controls")
    deck(mesh, (4.16, 11.84, 5.32, 14.97), .32, ((3.285, 0), (3.40, .11)), "seam")
    for x, sign in ((4.10, -1), (11.90, 1)):
        round_part(mesh, (x, 2.39, 5.81), sign,
                   ((-.02, .58, "washer"), (.09, .61, "washer"), (.19, .56, "steel"),
                    (.30, .49, "steel"), (.344, .40, "steel"), (.344, .30, "steel"),
                    (.205, .30, "washer")), 16, "black")
    round_part(mesh, (8, 2.84, .63), "-z", ((.02, .42, "seam"), (.11, .36, "seam"),
               (.14, .34, "washer"), (.14, .24, "washer"), (.092, .24, "black")), 16, "black")
    # A small insulation ring and pin inside the rear DC jack.
    round_part(mesh, (8, 2.84, .63), "-z", ((.086, .10, "washer"), (.138, .084, "washer")), 12, "washer")
    round_part(mesh, (8, 1.36, 15.01), "z",
               ((0, .44, "seam"), (.16, .43, "black"), (.43, .41, "black"), (.49, .34, "seam")),
               36, "seam", ribbed=True)
    return mesh


def tread_model():
    mesh = Geometry()
    deck(mesh, (4.01, 11.99, 5.37, 15.01), .39,
         ((3.39, .13), (3.51, 0), (3.73, 0), (3.88, .12)), "cast", "tread")
    deck(mesh, (4.21, 11.79, 9.56, 14.63), .26,
         ((3.874, .11), (3.915, 0), (4.014, .07)), "seam", "rubber")
    return mesh


def knob_model(kind):
    mesh = Geometry()
    if kind == "full":
        profile = ((5.19, .66, "black"), (5.34, .77, "cap_edge"), (5.45, .78, "seam"),
                   (6.12, .76, "cap"), (6.52, .66, "cap"), (6.76, .54, "cap"), (6.86, .42, "cap"))
        marker = (6.864, -.394, -.18, .054, "white_pointer")
    elif kind == "outer":
        profile = ((5.19, .68, "black"), (5.29, .78, "cap_edge"), (5.42, .82, "seam"),
                   (5.84, .80, "cap"), (6.00, .72, "cap_edge"))
        marker = (6.005, -.694, -.555, .069, "ring_pointer")
    else:
        profile = ((6.00, .48, "seam"), (6.10, .51, "cap_edge"), (6.49, .50, "cap"),
                   (6.64, .47, "cap"), (6.76, .38, "cap"))
        marker = (6.764, -.354, -.175, .050, "white_pointer")
    round_part(mesh, (8, 0, 8), "y", profile, 40, "cap", ribbed=True)
    y, tip, start, half, material = marker
    mesh.face(material, ((8-half, y, 8+tip), (8-half, y, 8+start), (8+half, y, 8+start), (8+half, y, 8+tip)))
    return mesh


def lens_model(on):
    mesh = Geometry()
    edge, cap = ("lens_on_edge", "lens_on") if on else ("lens_dark_edge", "lens_dark")
    round_part(mesh, (8, 0, Z_LAMP), "y", ((5.19, .26, "seam"), (5.235, .255, "seam"),
               (5.32, .218, edge), (5.47, .20, edge), (5.54, .137, cap)), 20, cap)
    return mesh


def obj_model(name, mesh, parent=None):
    mesh.write(MODELS / (name + ".obj"))
    model = {
        "loader": "neoforge:obj", "model": MODEL_FILES + name + ".obj", "automatic_culling": False,
        "shade_quads": True, "ambientocclusion": False, "textures": {"particle": PREFIX + "cast"}
    }
    if parent:
        model["parent"] = parent
        model["display"] = {
            "gui": {"rotation": [39, 202, 0], "translation": [0, 2.2, 0], "scale": [1.14, 1.14, 1.14]},
            "ground": {"translation": [0, 2, 0], "scale": [.65, .65, .65]},
            "fixed": {"rotation": [90, 0, 0], "translation": [0, 0, -2.5], "scale": [1, 1, 1]},
        }
    return model


def main():
    MODELS.mkdir(parents=True, exist_ok=True)
    TEXTURES.mkdir(parents=True, exist_ok=True)
    draw_textures()
    shell = body_model()
    tread = tread_model()
    knobs = {kind: knob_model(kind) for kind in ("full", "outer", "inner")}
    off = lens_model(False)
    parts = {"body": shell, "tread": tread, "check_on": lens_model(True), "check_off": off}
    parts.update({"knob_" + name: mesh for name, mesh in knobs.items()})
    for name, mesh in parts.items():
        save_json(MODELS / (name + ".json"), obj_model(name, mesh))

    # The item uses the same geometry assembled at the six factory positions.
    inventory = Geometry()
    for mesh in (shell, tread, off):
        inventory.extend(mesh)
    for x, kind, position in (
        (4.91, "full", 55), (6.97, "outer", 50), (6.97, "inner", 50),
        (9.03, "outer", 50), (9.03, "inner", 50), (11.09, "full", 55)
    ):
        inventory.extend(knobs[kind], dx=x - 8, dz=Z_KNOB - 8, angle=135 - 2.7 * position)

    save_json(ASSETS / "models/item/boss_mt_2.json", obj_model("inventory", inventory, "minecraft:block/block"))
    materials = sorted({material for mesh in parts.values() for material, _, _ in mesh.faces})
    (MODELS / "metal_zone.mtl").write_text("\n\n".join(
        f"newmtl {material}\nKd 1.0 1.0 1.0\nmap_Kd {PREFIX + material}" for material in materials
    ) + "\n", encoding="ascii")
    save_json(ASSETS / "blockstates/boss_mt_2.json", {"variants": {
        "facing=" + facing: {"model": PREFIX + "body", "y": rotation}
        for facing, rotation in (("south", 0), ("west", 90), ("north", 180), ("east", 270))
    }})


if __name__ == "__main__":
    main()
