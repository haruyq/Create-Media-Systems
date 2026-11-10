"""Build the charger's exterior only; overlapping cuboid faces cause depth fighting.

Run from any directory with Python 3. The later material wins on coincident
exterior faces. Fragment coordinates retain Minecraft's default block UVs.
"""
import json
from pathlib import Path

SHAPES = [
    ([2, 1, 2], [14, 4, 14], "base"),
    ([3, 4, 3], [13, 5.5, 13], "copper"),
    ([5, 5.5, 5], [11, 6.5, 11], "dark"),
    ([6, 0, 6], [10, 1, 10], "metal"),
    ([3, 5.5, 3], [4.5, 12, 4.5], "metal"),
    ([2.8, 8, 2.8], [4.7, 9, 4.7], "copper"),
    ([3, 5.5, 11.5], [4.5, 12, 13], "metal"),
    ([2.8, 8, 11.3], [4.7, 9, 13.2], "copper"),
    ([11.5, 5.5, 3], [13, 12, 4.5], "metal"),
    ([11.3, 8, 2.8], [13.2, 9, 4.7], "copper"),
    ([11.5, 5.5, 11.5], [13, 12, 13], "metal"),
    ([11.3, 8, 11.3], [13.2, 9, 13.2], "copper"),
    ([3, 11.5, 3], [13, 12.5, 4.5], "copper"),
    ([3, 11.5, 11.5], [13, 12.5, 13], "copper"),
    ([2, 2, 1.5], [14, 3, 2], "core"),
    ([2, 2, 14], [14, 3, 14.5], "core"),
]
DIRECTIONS = {"west": (0, -1), "east": (0, 1), "down": (1, -1),
              "up": (1, 1), "north": (2, -1), "south": (2, 1)}


def subtract(a, b):
    x0, y0, x1, y1 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    if x0 >= x1 or y0 >= y1:
        return [a]
    return [r for r in [(a[0], a[1], x0, a[3]), (x1, a[1], a[2], a[3]),
                        (x0, a[1], x1, y0), (x0, y1, x1, a[3])]
            if r[2] - r[0] > 1e-8 and r[3] - r[1] > 1e-8]


def build():
    elements = []
    for i, (lo, hi, material) in enumerate(SHAPES):
        for face, (axis, sign) in DIRECTIONS.items():
            u, v = [a for a in range(3) if a != axis]
            c = hi[axis] if sign > 0 else lo[axis]
            fragments = [(lo[u], lo[v], hi[u], hi[v])]
            for j, (blo, bhi, _) in enumerate(SHAPES):
                if i == j:
                    continue
                interior = blo[axis] < c < bhi[axis]
                touching_inside = c == (blo[axis] if sign > 0 else bhi[axis])
                same_exterior = j > i and c == (bhi[axis] if sign > 0 else blo[axis])
                if interior or touching_inside or same_exterior:
                    rect = (blo[u], blo[v], bhi[u], bhi[v])
                    fragments = [part for fragment in fragments for part in subtract(fragment, rect)]
            for x0, y0, x1, y1 in fragments:
                start, end = [0] * 3, [0] * 3
                start[axis] = end[axis] = c
                start[u], start[v], end[u], end[v] = x0, y0, x1, y1
                elements.append({"from": start, "to": end, "faces": {face: {"texture": "#" + material}}})
    return elements


if __name__ == "__main__":
    path = Path(__file__).resolve().parents[1] / "src/main/resources/assets/create_media_systems/models/block/disc_cell_charger.json"
    model = json.loads(path.read_text(encoding="utf-8"))
    model["elements"] = build()
    path.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")
    print(f"Charger: {len(model['elements'])} exterior faces, no overlapping surfaces")
