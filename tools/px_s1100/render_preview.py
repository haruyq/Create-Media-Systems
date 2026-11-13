#!/usr/bin/env python3
"""Software preview + validator for Minecraft JSON block models (orthographic, z-buffered).
Uses the vanilla face/UV conventions, vanilla directional shading, and element rotations.

  python3 render_preview.py <resources dir> <namespace> <out png> model[,model...] [yaw pitch zoom cx cy cz]
"""
import json
import math
import os
import sys
from collections import Counter
from functools import lru_cache
from pathlib import Path

from PIL import Image

SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}


def corners(face, a, b):
    (x0, y0, z0), (x1, y1, z1) = a, b
    return {
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    }[face]


def rot_matrix(axis, deg):
    import numpy as np
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    if axis == "x":
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def load(res, ns, name):
    p = os.path.join(res, "assets", ns, "models", *name.split("/")) + ".json"
    with open(p) as fp:
        return json.load(fp)


@lru_cache(maxsize=8)
def read_obj(path):
    positions, texcoords, normals, faces = [], [], [], []
    material = None
    for line in path.read_text(encoding="utf-8").splitlines():
        tokens = line.split()
        if not tokens:
            continue
        tag, *values = tokens
        if tag == "mtllib":
            material = path.with_name(values[0])
        elif tag == "v":
            positions.append(tuple(map(float, values)))
        elif tag == "vt":
            texcoords.append(tuple(map(float, values)))
        elif tag == "vn":
            normals.append(tuple(map(float, values)))
        elif tag == "f":
            faces.append([tuple(int(index) - 1 for index in vertex.split("/")) for vertex in values])
    texture = next(line.split()[-1] for line in material.read_text(encoding="utf-8").splitlines()
                   if line.startswith("map_Kd "))
    return positions, texcoords, normals, faces, texture


def mesh(res, model):
    namespace, name = model["model"].split(":", 1)
    return read_obj(Path(res) / "assets" / namespace / name)


def mesh_offset(model):
    return model.get("transform", {}).get("translation", (0, 0, 0))


def validate(m, name, res=None):
    if m.get("loader") == "neoforge:composite":
        return [error for part, child in m["children"].items() for error in validate(child, f"{name}/{part}", res)]
    if m.get("loader") == "neoforge:obj":
        positions, uv, normals, faces, texture = mesh(res, m)
        errors = []
        offset = mesh_offset(m)
        for vertex in positions:
            if not all(-16 <= (value + delta) * 16 <= 32 for value, delta in zip(vertex, offset)):
                errors.append(f"{name}: mesh position {vertex} out of bounds")
        if not all(0 <= value <= 1 for point in uv for value in point):
            errors.append(f"{name}: texture coordinates out of bounds")
        if not all(math.isclose(sum(v * v for v in n), 1, abs_tol=1e-6) for n in normals):
            errors.append(f"{name}: non-unit mesh normal")
        namespace, texture = texture.split(":", 1)
        if not (Path(res) / "assets" / namespace / "textures" / (texture + ".png")).is_file():
            errors.append(f"{name}: missing mesh texture")
        edges = Counter()
        for face in faces:
            indices = [vertex[0] for vertex in face]
            if len(indices) != 4 or len(set(indices)) != 4 or any(
                    any(index < 0 or index >= limit for index, limit in zip(vertex, (len(positions), len(uv), len(normals))))
                    for vertex in face):
                errors.append(f"{name}: invalid mesh quad")
            edges.update(zip(indices, indices[1:] + indices[:1]))
        if not faces or any(count != 1 or edges[b, a] != 1 for (a, b), count in edges.items()):
            errors.append(f"{name}: mesh is not closed with consistent face winding")
        return errors
    errs = []
    for i, e in enumerate(m["elements"]):
        for v in e["from"] + e["to"]:
            if v < -16 or v > 32:
                errs.append(f"{name} el{i}: coord {v} outside [-16,32]")
        for a, b in zip(e["from"], e["to"]):
            if a > b + 1e-9:
                errs.append(f"{name} el{i}: from > to")
        r = e.get("rotation")
        if r and r["angle"] not in (-45, -22.5, 0, 22.5, 45):
            errs.append(f"{name} el{i}: bad angle {r['angle']}")
        for fn, f in e["faces"].items():
            for v in f["uv"]:
                if v < -1e-6 or v > 16 + 1e-6:
                    errs.append(f"{name} el{i} {fn}: uv {v} outside [0,16]")
            if f["texture"][1:] not in m["textures"]:
                errs.append(f"{name} el{i} {fn}: texture {f['texture']} undefined")
    return errs


def quads(res, model):
    import numpy as np
    if model.get("loader") == "neoforge:composite":
        for child in model["children"].values():
            yield from quads(res, child)
        return
    if model.get("loader") == "neoforge:obj":
        positions, texcoords, normals, faces, texture = mesh(res, model)
        offset = np.asarray(mesh_offset(model))
        for face in faces:
            vertices = (np.asarray([positions[p] for p, _, _ in face]) + offset) * 16
            uv = np.asarray([texcoords[t] for _, t, _ in face]) * 16
            nx, ny, _ = np.mean([normals[n] for _, _, n in face], axis=0)
            shade = .8 + .2 * max(ny, 0) + .3 * min(ny, 0) - .2 * abs(nx)
            yield vertices, uv, texture, shade
        return
    for element in model["elements"]:
        rotation = element.get("rotation")
        for direction, face in element["faces"].items():
            vertices = np.asarray(corners(direction, element["from"], element["to"]), dtype=float)
            if rotation:
                origin = np.asarray(rotation["origin"])
                vertices = (vertices - origin) @ rot_matrix(rotation["axis"], rotation["angle"]).T + origin
            u0, v0, u1, v1 = face["uv"]
            uv = np.asarray([(u0, v0), (u1, v0), (u1, v1), (u0, v1)])
            texture = model["textures"][face["texture"][1:]]
            shade = 1.0 if "neoforge_data" in face else SHADE[direction]
            yield vertices, uv, texture, shade


def render(res, ns, models, out, yaw=35, pitch=28, zoom=90, centre=(16, 1, 8), size=(2400, 1100), bg=(92, 104, 118)):
    import numpy as np
    Wd, Hd = size
    col = np.zeros((Hd, Wd, 3), np.float32)
    col[:] = bg
    zb = np.full((Hd, Wd), -1e9, np.float32)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    # camera looks toward +Z (south) when yaw=0, i.e. stands at the north / player side
    right = np.array([-cy, 0, sy])
    fwd = np.array([sy * cp, -sp, cy * cp])
    up = np.cross(fwd, right)
    up = up / np.linalg.norm(up)
    if up[1] < 0:
        up = -up
    C = np.array(centre, float)
    texcache = {}
    for item in models:
        rx = 0.0
        if isinstance(item, tuple):
            name, off = item[0], item[1]
            if len(item) > 2:
                rx = item[2]
        else:
            name, off = item, (0, 0, 0)
        for P, UV, tref, shade in quads(res, load(res, ns, name)):
            if tref not in texcache:
                tns, tp = tref.split(":")
                texcache[tref] = np.asarray(Image.open(os.path.join(res, "assets", tns, "textures", tp + ".png")).convert("RGBA"), np.float32)
            tex = texcache[tref]
            th, tw = tex.shape[:2]
            if rx:
                P = P @ rot_matrix("x", rx).T
            P = P + np.array(off, float)
            rel = P - C
            sx = rel @ right * zoom + Wd / 2
            syy = -(rel @ up) * zoom + Hd / 2
            dz = -(rel @ fwd)
            for tri in ((0, 1, 2), (0, 2, 3)):
                xs, ys, zs, uv = sx[list(tri)], syy[list(tri)], dz[list(tri)], UV[list(tri)]
                x_min, x_max = int(max(0, math.floor(xs.min()))), int(min(Wd - 1, math.ceil(xs.max())))
                y_min, y_max = int(max(0, math.floor(ys.min()))), int(min(Hd - 1, math.ceil(ys.max())))
                if x_min > x_max or y_min > y_max:
                    continue
                den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
                if abs(den) < 1e-9:
                    continue
                gx, gy = np.meshgrid(np.arange(x_min, x_max + 1) + 0.5, np.arange(y_min, y_max + 1) + 0.5)
                w0 = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / den
                w1 = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / den
                w2 = 1 - w0 - w1
                inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
                z = w0 * zs[0] + w1 * zs[1] + w2 * zs[2]
                uu = (w0 * uv[0, 0] + w1 * uv[1, 0] + w2 * uv[2, 0]) / 16 * tw
                vv = (w0 * uv[0, 1] + w1 * uv[1, 1] + w2 * uv[2, 1]) / 16 * th
                ui = np.clip(uu.astype(int), 0, tw - 1)
                vi = np.clip(vv.astype(int), 0, th - 1)
                texel = tex[vi, ui]
                sub_z = zb[y_min:y_max + 1, x_min:x_max + 1]
                sub_c = col[y_min:y_max + 1, x_min:x_max + 1]
                ok = inside & (z > sub_z + 1e-7) & (texel[..., 3] > 25)
                al = (texel[..., 3:4] / 255.0)
                newc = texel[..., :3] * shade * al + sub_c * (1 - al)
                sub_c[ok] = newc[ok]
                sub_z[ok] = z[ok]
    Image.fromarray(np.clip(col, 0, 255).astype(np.uint8)).save(out)


if __name__ == "__main__":
    res, ns, out, names = sys.argv[1:5]
    extra = [float(v) for v in sys.argv[5:]]
    kw = {}
    if len(extra) >= 3:
        kw.update(yaw=extra[0], pitch=extra[1], zoom=extra[2])
    if len(extra) >= 6:
        kw["centre"] = tuple(extra[3:6])
    render(res, ns, names.split(","), out, **kw)
