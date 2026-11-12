#!/usr/bin/env python3
"""Software preview + validator for Minecraft JSON block models (orthographic, z-buffered).
Uses the vanilla face/UV conventions, vanilla directional shading, and element rotations.

  python3 render_preview.py <resources dir> <namespace> <out png> model[,model...] [yaw pitch zoom cx cy cz]
"""
import json
import math
import os
import sys

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


def validate(m, name):
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
        m = load(res, ns, name)
        for e in m["elements"]:
            a, b = e["from"], e["to"]
            R, org = None, None
            if "rotation" in e:
                R = rot_matrix(e["rotation"]["axis"], e["rotation"]["angle"])
                org = np.array(e["rotation"]["origin"], float)
            for fn, f in e["faces"].items():
                tref = m["textures"][f["texture"][1:]]
                if tref not in texcache:
                    tns, tp = tref.split(":")
                    texcache[tref] = np.asarray(Image.open(os.path.join(res, "assets", tns, "textures", tp + ".png")).convert("RGBA"), np.float32)
                tex = texcache[tref]
                th, tw = tex.shape[:2]
                P = np.array(corners(fn, a, b), float)
                if R is not None:
                    P = (P - org) @ R.T + org
                if rx:
                    P = P @ rot_matrix("x", rx).T
                P = P + np.array(off, float)
                u0, v0, u1, v1 = f["uv"]
                UV = np.array([(u0, v0), (u1, v0), (u1, v1), (u0, v1)], float)
                rel = P - C
                sx = rel @ right * zoom + Wd / 2
                syy = -(rel @ up) * zoom + Hd / 2
                dz = -(rel @ fwd)
                emissive = "neoforge_data" in f
                shade = 1.0 if emissive else SHADE[fn]
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
