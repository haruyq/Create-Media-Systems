"""Generate the block model and material atlas for the Hayacio power adapter."""

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ASSETS = Path(__file__).resolve().parents[2] / "src/main/resources/assets/create_media_systems"
MODEL_DIR = ASSETS / "models/block/piano/pxs1100we"
TEXTURE = "create_media_systems:block/piano/pxs1100we/power_adapter"


def font(size, bold=False):
    name = "arialbd.ttf" if bold else "arial.ttf"
    for path in (Path("C:/Windows/Fonts") / name, Path("/usr/share/fonts/truetype/dejavu") / (
            "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf")):
        if path.is_file():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default(size=size)


def material():
    image = Image.new("RGBA", (256, 256), "#18191c")
    draw = ImageDraw.Draw(image)
    for y in range(124):
        for x in range(124):
            grain = (x * 73856093 ^ y * 19349663) % 7 - 3
            shade = 29 + grain + (124 - y) // 31
            image.putpixel((x, y), (shade, shade + 1, shade + 3, 255))
    draw.rounded_rectangle((12, 14, 108, 101), radius=5, fill="#1b1c1f", outline="#50535a", width=1)
    draw.line((17, 48, 103, 48), fill="#494c51")
    draw.text((18, 20), "hayacio", font=font(17, True), fill="#e1e2e3")
    draw.text((18, 40), "AC ADAPTER", font=font(8, True), fill="#afb3ba")
    draw.text((18, 53), "MODEL  HA-12", font=font(7), fill="#adb0b5")
    draw.text((18, 63), "DC OUT   12 V", font=font(8, True), fill="#d0d1d4")
    draw.text((18, 77), "INDOOR USE", font=font(7), fill="#92969d")
    draw.rectangle((87, 76, 100, 89), outline="#a7aab0", width=1)
    draw.rectangle((90, 79, 97, 86), outline="#a7aab0", width=1)
    draw.line((21, 94, 64, 94), fill="#555962")
    for x in range(22, 64, 4):
        draw.line((x, 90, x, 94), fill="#555962")
    draw.rectangle((128, 0, 255, 127), fill="#2a2c30")
    for y in range(128):
        shade = 42 - y // 8
        draw.line((128, y, 255, y), fill=(shade, shade + 1, shade + 3))
    draw.rectangle((0, 128, 127, 255), fill="#202226")
    for x in range(0, 128, 9):
        draw.line((x, 128, x, 255), fill="#2e3136")
    for x, color in ((128, "#101114"), (144, "#393c42"), (160, "#555962"),
                     (176, "#998365"), (192, "#303f45"), (208, "#0a0c0d"), (224, "#69757a"), (240, "#232529")):
        draw.rectangle((x, 128, x + 15, 255), fill=color)
    output = ASSETS / "textures/block/piano/pxs1100we/power_adapter.png"
    image.save(output)


PLASTIC = [8.1, .15, 15.9, 7.85]
RIBBED = [.1, 8.1, 7.9, 15.9]
BLACK = [8.1, 8.1, 8.9, 8.9]
EDGE = [9.1, 8.1, 9.9, 8.9]
GRAY = [10.1, 8.1, 10.9, 8.9]
BRASS = [11.1, 8.1, 11.9, 8.9]
GREEN_OFF = [12.1, 8.1, 12.9, 8.9]
SOCKET = [13.1, 8.1, 13.9, 8.9]
STEEL = [14.1, 8.1, 14.9, 8.9]
RUBBER = [15.1, 8.1, 15.9, 8.9]


def element(start, end, uv=PLASTIC, *, faces=None, label=False, lit=False):
    result = {"from": start, "to": end, "faces": {}}
    for side in (faces or ("north", "south", "east", "west", "up", "down")):
        face = {"uv": [.06, .06, 7.7, 7.7] if side == "up" and label else uv,
                "texture": "#case"}
        if lit:
            face = {"uv": [0, 0, 16, 16], "texture": "#led", "neoforge_data": {"block_light": 15, "sky_light": 15}}
        result["faces"][side] = face
    return result


def models():
    parts = [
        element([2.9, 0, 2.7], [13.1, .65, 13.3], BLACK),
        element([3.85, .65, 2.95], [12.15, 1.35, 13.05], EDGE, faces=("north", "south", "west", "east")),
        element([4, 1.35, 2.55], [12, 5.35, 13.45], faces=("north", "south", "west", "east")),
        element([3.6, 1.55, 3.1], [4, 5.03, 12.9], RIBBED, faces=("north", "south", "west", "up")),
        element([12, 1.55, 3.1], [12.4, 5.03, 12.9], RIBBED, faces=("north", "south", "east", "up")),
        element([4, 5.35, 2.85], [12, 5.65, 13.15], label=True, faces=("north", "south", "west", "east", "up")),
        element([4.25, 3.0, 2.05], [5.6, 4.07, 2.55], GRAY),
        element([6.78, 2.5, 1.8], [9.22, 3.95, 2.55], BLACK),
        element([7.18, 2.74, 1.38], [8.82, 3.67, 1.8], RUBBER),
        element([7.42, 2.8, 1.22], [8.58, 3.52, 1.38], EDGE),
        element([7.51, 2.88, 1.13], [8.49, 3.41, 1.22], BLACK),
        element([4.63, 2.22, 13.46], [9.64, 4.36, 13.74], GRAY),
        element([4.88, 2.45, 13.75], [9.38, 4.15, 13.91], SOCKET),
        element([5.29, 2.86, 13.92], [6.01, 3.57, 14.03], BRASS),
        element([6.78, 2.86, 13.92], [7.5, 3.57, 14.03], BRASS),
        element([8.24, 2.86, 13.92], [8.96, 3.57, 14.03], BRASS),
        element([10.14, 2.44, 13.46], [11.31, 3.51, 13.67], BLACK),
        element([10.34, 2.64, 13.68], [11.11, 3.31, 13.75], GREEN_OFF),
        element([6, .03, 6], [10, 1.15, 10], STEEL),
    ]
    for x in (4.5, 11.5):
        for z in (3.45, 12.55):
            parts.append(element([x - .46, .07, z - .48], [x + .46, .37, z + .48], RUBBER))
    for z in (4, 4.7, 5.4, 6.1, 6.8, 9.2, 9.9, 10.6, 11.3, 12):
        parts.append(element([3.47, 3.5, z - .14], [3.58, 4.45, z + .14], BLACK))
        parts.append(element([12.42, 3.5, z - .14], [12.53, 4.45, z + .14], BLACK))
    led = Image.new("RGBA", (16, 16), "#82cfa3")
    d = ImageDraw.Draw(led)
    d.rectangle((2, 2, 13, 6), fill="#b6e7c8")
    led.save(ASSETS / "textures/block/piano/pxs1100we/power_adapter_led.png")
    header = {"ambientocclusion": False, "textures": {"case": TEXTURE, "particle": TEXTURE, "led": TEXTURE + "_led"}}
    for lit in (False, True):
        if lit:
            parts.append(element([10.34, 2.64, 13.77], [11.11, 3.31, 13.81], lit=True, faces=("south",)))
        suffix = "_on" if lit else ""
        lines = json.dumps(header, indent=2).splitlines()[:-1]
        lines[-1] += ","
        rows = ["    " + json.dumps(part, separators=(",", ":")) for part in parts]
        lines.extend(["  \"elements\": [", ",\n".join(rows), "  ]", "}"])
        (MODEL_DIR / f"power_adapter{suffix}.json").write_text(
            "\n".join(lines) + "\n", encoding="utf-8", newline="\n"
        )


if __name__ == "__main__":
    material()
    models()
