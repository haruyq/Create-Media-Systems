#!/usr/bin/env python3
"""Create the Yamaha SBP2F5-DUS resource-pack models from the shared layout, in metres."""
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/create_media_systems"
MESHES = ASSETS / "models/block/stage_custom"
TEXTURES = ASSETS / "textures/block/stage_custom"
ID = "stage_custom_birch_sbp2f5_dus"
NS = "create_media_systems:"
MODELS = NS + "block/stage_custom/"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
LAYOUT = json.loads((MESHES / "kit_layout.json").read_text(encoding="utf-8"))
SPECS = LAYOUT["drums"]
TAU = math.tau
RNG = np.random.default_rng(22171610)


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def font(size):
    return ImageFont.truetype(FONT, size)


def texture(name, pixels):
    Image.fromarray(np.uint8(np.clip(pixels, 0, 255))).save(TEXTURES / f"{name}.png")


def field(width, height):
    return np.meshgrid(np.arange(width) / width, np.arange(height) / height)


def material_textures():
    u, v = field(512, 256)
    phase = 2.6 * np.sin(TAU * (1.21*u + .069)) + 1.2*np.sin(TAU*(.91*u + .43*v))
    wood = (4.4*np.sin(TAU*26*v + phase) + 2.4*np.sin(TAU*65*v + 1.9*phase)
            + 1.55*np.sin(TAU*119*v + .9*phase) + RNG.normal(0, .85, u.shape))
    burst = np.sin(np.pi * v) ** 1.72
    lacquer = 9 * np.exp(-7 * np.sin(np.pi*(u - .13))**2) + 13 * np.exp(-80 * np.sin(np.pi*(u - .68))**2)
    dusk = np.stack((7 + 10*burst, 15 + 51*burst, 29 + 92*burst), axis=2)
    dusk += (wood * (.38 + .62*burst) + lacquer)[..., None] * np.array((.76, .98, 1.26))
    texture("dus", dusk)
    grain = wood * 1.15 + 3.1*np.sin(TAU*(9*v + .14*np.cos(TAU*u)))
    texture("birch", np.array((203, 166, 121)) + grain[..., None])
    wood_ring = np.stack((167 + 24*np.cos(TAU*v), 127 + 19*np.cos(TAU*v), 80 + 15*np.cos(TAU*v)), axis=2)
    texture("plies", wood_ring + (wood*.52 + 6*np.cos(TAU*v*6))[..., None])

    x, y = field(128, 64)
    silver = (122 + 43*np.cos(TAU*x + .22) + 33*np.cos(TAU*2*x - 1.2)
              + 26*np.exp(-((x-.19)/.086)**2) + 23*np.exp(-((x-.72)/.032)**2))
    silver += 4*np.sin(TAU*y*13) + RNG.normal(0, 1.2, x.shape)
    texture("chrome", silver[..., None] + np.array((7, 11, 16)))
    for name, color, variance in (
        ("hardware", (61, 67, 73), 2.6), ("rubber", (26, 28, 31), 2.1), ("wire", (157, 164, 170), 2.3),
        ("maple_stick", (205, 162, 107), 2.1), ("black_hoop", (12, 22, 37), 2.2),
    ):
        texture(name, np.array(color) + RNG.normal(0, variance, (32, 32, 1)))

    for spec in SPECS.values():
        badge = Image.new("RGB", (128, 64), (176, 142, 82))
        draw = ImageDraw.Draw(badge)
        draw.rounded_rectangle((1, 1, 126, 62), radius=11, fill=(196, 168, 108), outline=(102, 75, 43), width=3)
        draw.rounded_rectangle((5, 5, 122, 58), radius=8, outline=(245, 214, 158), width=2)
        for x0 in (12, 115):
            draw.ellipse((x0-2, 29, x0+2, 33), fill=(77, 69, 57), outline=(252, 223, 167))
        draw.text((64, 9), "YAMAHA", anchor="mt", font=font(13), fill=(35, 28, 24))
        draw.text((64, 26), "STAGE CUSTOM", anchor="mt", font=font(11), fill=(39, 33, 25))
        draw.text((64, 44), "BIRCH " + spec["model"], anchor="mt", font=font(8), fill=(57, 45, 33))
        badge.save(TEXTURES / f"badge_{spec['model'].lower()}.png")


def tuning_forks(draw, x, y, r, color):
    draw.ellipse((x-r, y-r, x+r, y+r), outline=color, width=2)
    for p in (0, TAU/3, 2*TAU/3):
        w = np.array((math.sin(p), -math.cos(p)))
        v = np.array((-w[1], w[0]))
        c = np.array((x, y))
        def at(a, b):
            return tuple(c + r * (a*w + b*v))
        draw.line((at(-.60, 0), at(.35, 0)), fill=color, width=2)
        draw.line((at(.75, -.14), at(.34, -.14), at(.26, 0), at(.34, .14), at(.75, .14)), fill=color, width=2)


def membrane_textures():
    atlas = Image.new("RGBA", (512, 512))
    u, v = field(256, 256)
    radial = np.hypot(2*u-1, 2*v-1)
    gleam = np.exp(-((v-.36+.18*u)/.102)**2) * np.exp(-((u-.46)/.50)**2)
    grain = RNG.normal(0, 2, u.shape)
    for index, name in enumerate(("clear", "black", "coated", "kick_clear")):
        ring = np.exp(-((radial-.914)/.014)**2)
        if name == "black":
            base = 27 + 7*gleam + grain*.53 + 3*ring
            rgb = np.stack((base, base+1, base+3), axis=2)
            alpha = np.full(u.shape, 255)
        elif name == "coated":
            base = 224 + 10*gleam + grain*1.65 - 13*ring
            rgb = np.stack((base+3, base+2, base-5), axis=2)
            alpha = np.full(u.shape, 255)
        else:
            base = 186 + 33*gleam + grain*.5
            rgb = np.stack((base, base+4, base+7), axis=2)
            alpha = 39 + 39*gleam + 81*ring
            if name == "kick_clear":
                alpha += 75 * np.exp(-((radial-.865)/.078)**6)
        skin = Image.fromarray(np.uint8(np.clip(np.dstack((rgb, alpha)), 0, 255)))
        draw = ImageDraw.Draw(skin)
        if name == "black":
            tuning_forks(draw, 80, 69, 12, (223, 226, 228, 255))
            draw.text((94, 61), "YAMAHA", font=font(23), fill=(223, 226, 228, 255), anchor="lt")
        else:
            draw.text((128, 23), "YAMAHA", font=font(12),
                      fill=(60, 63, 64, 235) if name == "coated" else (229, 233, 235, 185), anchor="mt")
        atlas.paste(skin, (index % 2 * 256, index // 2 * 256))
        offset = np.array((31, 36, 44)) if name == "kick_clear" else np.array((144, 136, 126))
        if name == "clear" or name == "kick_clear":
            view = Image.new("RGBA", skin.size, tuple(offset) + (255,))
            skin = Image.alpha_composite(view, skin)
        skin.convert("RGB").save(TEXTURES / f"item_{name}.png")
    atlas.save(TEXTURES / "heads.png")


def add(a, b):
    return tuple(x+y for x, y in zip(a, b))


def sub(a, b):
    return tuple(x-y for x, y in zip(a, b))


def mul(a, scalar):
    return tuple(x*scalar for x in a)


def length(a):
    return math.sqrt(sum(x*x for x in a))


def cross(a, b):
    return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])


def unit(a):
    return mul(a, 1/length(a))


class Mesh:
    def __init__(self):
        self.faces = []

    def face(self, material, positions, uv=None, reverse=False):
        if uv is None:
            uv = ((.08, .08), (.08, .92), (.92, .92), (.92, .08))[:len(positions)]
        points, tex = tuple(positions), tuple(uv)
        self.faces.append((material, points[::-1] if reverse else points, tex[::-1] if reverse else tex))

    def extend(self, other):
        self.faces.extend(other.faces)

    def write(self, path, origin, scale=1, materials="stage_custom.mtl", source="generate.py", name="SBP2F5-DUS"):
        vertices, uvs, normals, faces = {}, {}, {}, []
        last = None
        for material, points, texture in self.faces:
            a, b, c = points[:3]
            normal = cross(sub(b, a), sub(c, a))
            magnitude = length(normal)
            if magnitude < 1e-11:
                continue
            if material != last:
                faces.append("usemtl " + material)
                last = material
            n = tuple(round(v / magnitude, 5) for v in normal)
            normal_id = normals.setdefault(n, len(normals) + 1)
            corners = []
            for p, uv in zip(points, texture):
                p = tuple(round(v * scale + d, 6) for v, d in zip(p, origin))
                uv = tuple(round(w, 6) for w in uv)
                i = vertices.setdefault(p, len(vertices) + 1)
                j = uvs.setdefault(uv, len(uvs) + 1)
                corners.append(f"{i}/{j}/{normal_id}")
            faces.append("f " + " ".join(corners))
        data = [f"# {name} generated by tools/stage_custom/{source}", f"mtllib {materials}", "g drums"]
        data.extend("v %.6f %.6f %.6f" % v for v in vertices)
        data.extend("vt %.6f %.6f" % uv for uv in uvs)
        data.extend("vn %.5f %.5f %.5f" % vn for vn in normals)
        path.write_text("\n".join(data + faces) + "\n", encoding="ascii")


class Drum:
    def __init__(self, key):
        spec = SPECS[key]
        self.name = key
        self.model = spec["model"].lower()
        self.origin = spec["x"], spec["y"], spec["z"]
        self.radius = spec["diameter"]*.0127
        self.depth = spec["depth"]*.0254
        self.lugs = spec["lugs"]
        tilt = math.radians(spec["tilt"])
        self.axis = 0, math.cos(tilt), math.sin(tilt)
        self.across = 0, -math.sin(tilt), math.cos(tilt)

    def point(self, u, h, v):
        return add(add(self.origin, (u, 0, 0)), add(mul(self.axis, h), mul(self.across, v)))

    def polar(self, theta, h, radius):
        return self.point(math.cos(theta)*radius, h, math.sin(theta)*radius)


def pipe(mesh, drum, profile, count=64, reverse=False):
    depth = drum.depth
    for i in range(count):
        a, b = TAU*i/count, TAU*(i+1)/count
        for (h0, r0, _), (h1, r1, material) in zip(profile, profile[1:]):
            if material in ("dus", "birch", "plies"):
                v0, v1 = 1-np.clip(1+h0/depth, 0, 1), 1-np.clip(1+h1/depth, 0, 1)
                if abs(v0-v1) < .00001:
                    v0, v1 = .11, .89
            else:
                v0, v1 = .05, .95
            uv = ((i/count, v0), (i/count, v1), ((i+1)/count, v1), ((i+1)/count, v0))
            mesh.face(material, (drum.polar(a,h0,r0), drum.polar(a,h1,r1), drum.polar(b,h1,r1),
                                 drum.polar(b,h0,r0)), uv, reverse)


def film(mesh, drum, h, material, up=True):
    for i in range(64):
        a, b = TAU*i/64, TAU*(i+1)/64
        points = [drum.point(0, h, 0), drum.polar(b, h, drum.radius-.003), drum.polar(a, h, drum.radius-.003)]
        mirror = -1 if material == "item_black" else 1
        tex = ((.5,.5),(.5+mirror*.498*math.cos(b),.5+.498*math.sin(b)),(.5+mirror*.498*math.cos(a),.5+.498*math.sin(a)))
        mesh.face(material, points, tex, not up)


def rod(mesh, start, end, radius, material="chrome", end_radius=None, count=12):
    axis = unit(sub(end, start))
    d = (1, 0, 0) if abs(axis[0]) < .8 else (0, 1, 0)
    x = unit(cross(axis, d))
    z = cross(x, axis)
    r = radius if end_radius is None else end_radius

    for i in range(count):
        a, b = TAU*i/count, TAU*(i+1)/count
        da = add(mul(x, math.cos(a)), mul(z, math.sin(a)))
        db = add(mul(x, math.cos(b)), mul(z, math.sin(b)))
        p0, p1 = add(start, mul(da,radius)), add(start, mul(db,radius))
        q0, q1 = add(end, mul(da,r)), add(end, mul(db,r))
        mesh.face(material, (p0, q0, q1, p1), ((i/count,.92),(i/count,.08),((i+1)/count,.08),((i+1)/count,.92)))
        mesh.face(material, (end, q1, q0))
        mesh.face(material, (start, p0, p1))


def plate(mesh, bottom, top, material="chrome"):
    x, y, z = bottom
    X, Y, Z = top
    for q in (((x,y,z),(x,Y,z),(X,Y,z),(X,y,z)), ((x,y,Z),(X,y,Z),(X,Y,Z),(x,Y,Z)),
              ((x,y,z),(x,y,Z),(x,Y,Z),(x,Y,z)), ((X,y,z),(X,Y,z),(X,Y,Z),(X,y,Z)),
              ((x,Y,z),(x,Y,Z),(X,Y,Z),(X,Y,z)), ((x,y,z),(X,y,z),(X,y,Z),(x,y,Z))):
        mesh.face(material, q)


def badge(mesh, drum, angle, h, end_width=0.023):
    r = drum.radius+.001
    w = end_width / r
    for i in range(8):
        a, b = angle+w*(i/4-1), angle+w*((i+1)/4-1)
        model = "badge_" + drum.model
        tex = ((1-i/8,.89),(1-i/8,.11),(1-(i+1)/8,.11),(1-(i+1)/8,.89))
        mesh.face(model, (drum.polar(a,h-.013,r),drum.polar(a,h+.013,r),
                          drum.polar(b,h+.013,r),drum.polar(b,h-.013,r)), tex)


def shells(mesh, drums):
    for drum in drums.values():
        r, d = drum.radius, drum.depth
        pipe(mesh, drum, ((-d,r-.0024,"dus"),(-d+.004,r,"dus"),(-.004,r,"dus"),(0,r-.0024,"dus")))
        pipe(mesh, drum, ((-d,r-.0024,"birch"),(-d+.004,r-.0072,"plies"),(-.004,r-.0072,"birch"),
                          (0,r-.0024,"plies")), reverse=True)
        for h, high in ((0,1),(-d,-1)):
            def hoop(profile):
                pipe(mesh, drum, [(h+high*y, r+radial, material) for y,radial,material in
                                  (profile if high > 0 else profile[::-1])])
            if drum.name == "bass":
                hoop(((-.021,.004,"black_hoop"),(-.014,.012,"black_hoop"),(.017,.012,"black_hoop"),
                      (.024,.007,"plies"),(.024,-.003,"plies"),(.013,-.003,"birch"),(-.021,.001,"birch")))
            else:
                hoop(((-.016,.001,"chrome"),(-.013,.007,"chrome"),(-.004,.007,"chrome"),
                      (.005,.011,"chrome"),(.009,.008,"chrome"),(.009,.003,"chrome"),
                      (.004,.002,"chrome"),(-.011,0,"chrome")))
            for i in range(drum.lugs):
                a = TAU*(i+.5)/drum.lugs
                wide = .011 if drum.name == "bass" else .008
                reach = .071 if drum.name == "bass" else .053
                mount = drum.polar(a,h-high*reach,r+.004)
                slot = drum.polar(a,h-high*(reach-.030),r+.008)
                lug_base = drum.polar(a,h-high*(reach-.043),r+.004)
                rod(mesh,mount,slot,wide*.63,"chrome",wide,10)
                rod(mesh,slot,lug_base,wide,"chrome",wide*.62,10)
                tension = drum.polar(a,h+high*(.028 if drum.name=="bass" else .012),r+.010)
                rod(mesh,slot,tension,.00255)
                rod(mesh,tension,add(tension,mul(drum.axis,high*.005)),.0062,"chrome",.0051,8)
                if drum.name == "bass":
                    rod(mesh,drum.polar(a,h+high*.015,r+.014),drum.polar(a,h+high*.021,r-.002),
                        .0097,"chrome",.0078,10)
        if drum.name == "snare":
            for i in range(drum.lugs):
                a = TAU*(i+.5)/drum.lugs
                rod(mesh,drum.polar(a,-d+.058,r+.004),drum.polar(a,-.058,r+.004),.006,"chrome",.006,8)
        badge(mesh, drum, -TAU/4-.15, -d*.48, .027 if drum.name == "bass" else .023)
        air = drum.polar(-.40,-d*.57,r)
        vent = drum.polar(-.40,-d*.57,r+.003)
        rod(mesh,air,vent,.0061,"chrome")
        rod(mesh,vent,drum.polar(-.40,-d*.57,r+.0036),.0029,"hardware")


def foot_point(index):
    a = LAYOUT["feet"][index]["from"]
    b = LAYOUT["feet"][index]["to"]
    return (a[0]+b[0])/2, .004, (a[2]+b[2])/2


def foot(mesh, at, target, radius=.015):
    rail = unit(sub(target, at))
    heel = add(at, mul(rail,.036))
    rod(mesh,at,heel,radius,"rubber",radius*.76)
    rod(mesh,heel,target,.0055)


def integrated_hardware(mesh, drums):
    bass = drums["bass"]
    for i, sign in enumerate((-1,1)):
        base = foot_point(i)
        mount = (bass.origin[0]+sign*.240, .173, -.143)
        foot(mesh,base,mount,.0158)
        rod(mesh,add(base,mul(sub(mount,base),.49)),mount,.008)
        rod(mesh,add(mount,(-.020,0,0)),add(mount,(.020,0,0)),.019,"chrome",.015,12)
        rod(mesh,add(mount,(sign*.016,.008,0)),add(mount,(sign*.031,.017,0)),.0057)
    floor = drums["floor"]
    for i in range(2,5):
        base = foot_point(i)
        dx, dz = base[0]-floor.origin[0], base[2]-floor.origin[2]
        angle = math.atan2(dz,dx)
        clamp = floor.polar(angle,-.254,floor.radius+.006)
        bar = floor.polar(angle,-.148,floor.radius+.014)
        foot(mesh,base,bar)
        rod(mesh,add(clamp,(0,-.014,0)),add(clamp,(0,.020,0)),.014,"chrome",.012,12)
        rod(mesh,clamp,floor.polar(angle+.055,-.246,floor.radius+.032),.0061)
    base = (-.292,.589,-.095)
    rod(mesh,(base[0],.578,base[2]),(base[0],.604,base[2]),.040,"hardware",.034,16)
    rod(mesh,base,(base[0],.715,base[2]),.0111)
    rod(mesh,(base[0],.664,base[2]),(base[0],.682,base[2]),.0185)
    plate(mesh,(-.352,.688,-.133),(-.232,.718,-.060),"chrome")
    rod(mesh,(-.292,.722,-.129),(-.292,.734,-.129),.0165,"chrome")
    rod(mesh,(-.292,.734,-.129),(-.292,.735,-.129),.0096,"hardware")
    for key, x in (("tom_10",-.362),("tom_12",-.222)):
        tom = drums[key]
        ball = (x,.722,-.095)
        rod(mesh,(x,.677,-.095),(x,.727,-.095),.0145)
        rod(mesh,add(ball,(0,-.013,0)),add(ball,(0,.017,0)),.022,"hardware",.019,16)
        target = tom.polar(-TAU/4,-tom.depth*.62,tom.radius+.010)
        joint = (target[0],target[1],target[2]-.030)
        rod(mesh,ball,joint,.0084)
        rod(mesh,joint,target,.0084)
        a = tom.polar(-TAU/4-.17,-tom.depth*.73,tom.radius+.005)
        b = tom.polar(-TAU/4+.17,-tom.depth*.55,tom.radius+.005)
        rod(mesh,a,target,.011,"chrome",.015)
        rod(mesh,target,b,.015,"chrome",.010)
        for anchor in (a,b):
            rod(mesh,add(anchor,(0,0,.006)),add(anchor,(0,0,-.005)),.007,"hardware")
    snare = drums["snare"]
    for sign in (-1,1):
        a = snare.point(sign*(snare.radius-.004), -snare.depth+.041, 0)
        b = snare.point(sign*(snare.radius+.012), -snare.depth+.042, .016)
        rod(mesh,a,b,.014,"chrome",.011)
        if sign < 0:
            rod(mesh,b,add(b,(-.012,.031,.008)),.0045,"chrome",.006)
            rod(mesh,add(b,(-.012,.031,.008)),add(b,(-.009,.041,.013)),.006,"hardware")


def snare_stand(mesh, snare):
    x, _, z = snare.origin
    axle = (x,.471,z)
    rod(mesh,(x,.142,z),axle,.0115)
    rod(mesh,(x,.232,z),(x,.258,z),.0175,"chrome")
    rod(mesh,(x,.457,z),(x,.492,z),.022,"chrome",.016)
    for i in range(5,8):
        base = foot_point(i)
        foot(mesh,base,(x,.253,z),.014)
        mid = add(base,mul(sub((x,.253,z),base),.40))
        rod(mesh,mid,(x,.162,z),.0038,"chrome")
        a = math.atan2(base[2]-z,base[0]-x)
        end = snare.polar(a,-snare.depth-.004,snare.radius-.006)
        rod(mesh,axle,end,.0047)
        rod(mesh,end,add(end,(0,.014,0)),.010,"rubber")
    rod(mesh,(x-.012,.467,z),(x+.030,.473,z),.0069)


def wire_mesh(mesh, snare):
    height = -snare.depth-.0038
    for i in range(20):
        z = (i-9.5)*.00258
        for j in range(12):
            x0 = -.124 + .248*j/12
            x1 = -.124 + .248*(j+1)/12
            def at(x, edge):
                return snare.point(x,height-.00062*math.cos(x*1260 + i*.7),z+edge)
            mesh.face("wire",(at(x0,-.00058),at(x0,.00058),at(x1,.00058),at(x1,-.00058)))
    for x in (-.132,.132):
        rod(mesh,snare.point(x,height,-.027),snare.point(x,height,.027),.0028,"chrome")


def model_json(name):
    return {"loader": "neoforge:obj", "model": NS+"models/block/stage_custom/"+name+".obj",
            "automatic_culling": False, "shade_quads": True, "ambientocclusion": False,
            "textures": {"particle": MODELS+"dus"}}


def main():
    TEXTURES.mkdir(parents=True, exist_ok=True)
    material_textures()
    membrane_textures()
    drums = {key: Drum(key) for key in SPECS}
    retail, wires, stick, stand = Mesh(),Mesh(),Mesh(),Mesh()
    shells(retail, drums)
    integrated_hardware(retail, drums)
    wire_mesh(wires, drums["snare"])
    snare_stand(stand, drums["snare"])
    rod(stick,(0,0,0),(0,.010,0),.0048,"maple_stick",.0058,12)
    rod(stick,(0,.010,0),(0,.048,0),.0049,"maple_stick",.0034,12)
    rod(stick,(0,.048,0),(0,.128,0),.0034,"maple_stick",.0071,12)
    rod(stick,(0,.128,0),(0,.391,0),.0071,"maple_stick",.0069,12)
    rod(stick,(0,.391,0),(0,.398,0),.0069,"maple_stick",.003,12)

    kit, inventory = Mesh(),Mesh()
    for dest in (kit, inventory):
        dest.extend(retail)
    kit.extend(stand)
    inventory.extend(wires)
    for key, drum in drums.items():
        material = {"bass":"kick_clear","snare":"coated"}.get(key,"clear")
        film(inventory,drum,0,"item_"+material)
        film(inventory,drum,-drum.depth,"item_black" if key=="bass" else "item_clear",up=False)
    for name,mesh,origin,scale in (
        ("kit",kit,(.5,0,.5),1),("snare_wires",wires,(.5,0,.5),1),("stick",stick,(0,0,0),1),
        ("inventory",inventory,(.643,.112,.366),.66)
    ):
        mesh.write(MESHES/(name+".obj"),origin,scale)
        if name != "inventory":
            write_json(MESHES/(name+".json"),model_json(name))
    material = sorted({m for geom in (kit, inventory, wires, stick) for m, _, _ in geom.faces})
    (MESHES/"stage_custom.mtl").write_text("\n\n".join(
        f"newmtl {m}\nKd 1.0 1.0 1.0\nmap_Kd {MODELS+m}" for m in material
    )+"\n",encoding="ascii")
    b = model_json("inventory")
    b["parent"] = "minecraft:block/block"
    b["display"] = {
        "gui": {"rotation":[23,143,0],"translation":[0,1.55,0],"scale":[1.0,1.0,1.0]},
        "ground": {"translation":[0,2,0],"scale":[.52,.52,.52]},
        "fixed": {"rotation":[5,180,0],"translation":[0,1.25,0],"scale":[1,1,1]},
        "firstperson_righthand": {"rotation":[0,135,0],"translation":[3,0,-1],"scale":[.50,.50,.50]},
        "thirdperson_righthand": {"rotation":[64,0,0],"translation":[0,1.8,-1],"scale":[.40,.40,.40]}
    }
    write_json(ASSETS/"models/item"/(ID+".json"),b)
    write_json(MESHES/"empty.json",{"textures":{"particle":MODELS+"dus"}})
    write_json(ASSETS/"blockstates"/(ID+".json"),{"variants":{"":{"model":MODELS+"empty"}}})


if __name__ == "__main__":
    main()
