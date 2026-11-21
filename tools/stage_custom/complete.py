#!/usr/bin/env python3
"""Build the stands, pedals, stool and cymbal finishes for the SBP2F5AZM-DUS."""
import json
import math

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from generate import ASSETS, MESHES, MODELS, NS, TEXTURES, TAU, Mesh, add, field, font, model_json, plate, rod, sub, texture, unit, write_json

DATA = json.loads((MESHES / "cymbal_layout.json").read_text(encoding="utf-8"))
ID = "stage_custom_birch_sbp2f5azm_dus"
SHELL_ID = "stage_custom_birch_sbp2f5_dus"
MTL = "complete.mtl"
SCRIPT = "complete.py"
KIT = "SBP2F5AZM-DUS"
RNG = np.random.default_rng(2078014)
RADII = (.034, .094, .16, .208, .253, .31, .41, .54, .68, .81, .92, 1.0)


def finish_textures():
    size = 384
    u, v = field(size, size)
    xx, zz = u*2-1, v*2-1
    r, angle = np.hypot(xx, zz), np.arctan2(zz, xx)
    stride = np.sin(TAU*(98*r+.12*np.sin(17*angle)))
    glancing = 36*np.maximum(0,np.cos(angle+.94))**10 + 22*np.maximum(0,np.cos(angle-2.57))**16
    glancing -= 22*np.maximum(0,np.cos(angle-1.05))**6
    hammer = np.zeros_like(r)
    for _ in range(73):
        a, b = RNG.uniform(-.9,.9,2)
        spread = RNG.uniform(.014,.044)
        hammer += RNG.uniform(-5.8,4.8)*np.exp(-((xx-a)**2+(zz-b)**2)/spread**2)
    light = (glancing*np.minimum(r*2,1) + 8*stride + 4*np.sin(TAU*(57*r+.052*np.sin(12*angle)))
             + 17*np.exp(-((r-.18)/.022)**2) + hammer + RNG.normal(0,1.28,r.shape))
    gold = (204 + light - 9*r, 156 + light*.92 - 7*r, 76 + light*.79 - 6*r)
    atlas = Image.new("RGB",(size*2,size*2))
    labels = (
        ('NEW BEAT HIHAT','14" / 36 CM'),
        ('MEDIUM CRASH','16" / 40 CM'),
        ('MEDIUM RIDE','20" / 51 CM'),
        ('NEW BEAT BOTTOM','14" / 36 CM'),
    )
    script_font = ImageFont.truetype("/usr/share/fonts/truetype/liberation2/LiberationSerif-BoldItalic.ttf",68)
    for index, (title, diameter) in enumerate(labels):
        tone = np.array((0,0,0) if index != 3 else (-17,-14,-5))
        img = Image.fromarray(np.uint8(np.clip(np.stack(gold,axis=2)+tone,0,255)))
        mark = ImageDraw.Draw(img)
        mark.text((size/2,53), "A", font=font(58), anchor="mt", fill=(53,40,24))
        mark.text((size/2,112), title, font=font(14), anchor="mt", fill=(59,43,23))
        mark.text((size/2,130), diameter, font=font(11), anchor="mt", fill=(65,48,26))
        mark.text((size/2,272), "Zildjian", font=script_font, anchor="mt", fill=(53,40,24))
        atlas.paste(img,((index%2)*size,(index//2)*size))
    # Use a power-of-two atlas for Minecraft's block mipmaps.
    atlas.resize((1024,1024), Image.Resampling.LANCZOS).save(TEXTURES/"cymbal_bronze.png")
    u, v = field(128,256)
    alum = 74 + 14*np.exp(-((u-.5)/.38)**2) + 3*np.sin(TAU*v*41) + RNG.normal(0,1.7,u.shape)
    img = Image.fromarray(np.uint8(np.clip(np.dstack((alum,alum+3,alum+7)),0,255)))
    draw = ImageDraw.Draw(img)
    for row in range(25,162,19):
        draw.line((15,row,113,row-5),fill=(122,125,129),width=3)
        draw.line((15,row+4,113,row-1),fill=(49,52,57),width=2)
    draw.text((64,198), "YAMAHA", anchor="mt",font=font(16),fill=(201,204,207))
    img.save(TEXTURES/"footboard.png")
    for name,color,amount in (
        ("felt",(208,196,165),4.0), ("throne",(42,43,45),2.7), ("seat_seam",(65,66,69),2.6)
    ):
        texture(name, np.array(color)+RNG.normal(0,amount,(64,64,1)))


def tripod(mesh, x, z, spread, angle, collar=.22):
    for i in range(3):
        theta = math.radians(angle + i*120)
        foot = (x+spread*math.cos(theta),.019,z+spread*math.sin(theta))
        anchor = (x,collar,z)
        axis = unit(sub(anchor,foot))
        rubber = add(foot,(axis[0]*.036,axis[1]*.036,axis[2]*.036))
        rod(mesh,add(foot,(0,-.009,0)),rubber,.0152,"rubber",.0125,10)
        rod(mesh,rubber,anchor,.0068,"chrome",.0074,10)
        brace = tuple(.62*a+.38*b for a,b in zip(foot,anchor))
        rod(mesh,brace,(x,collar-.085,z),.0038,"chrome",.0042,8)


def collar(mesh, x, y, z, radius):
    rod(mesh,(x,y-.009,z),(x,y+.010,z),radius,"chrome",radius*.93,12)
    rod(mesh,(x+radius-.002,y,z),(x+radius+.018,y,z),.0045,"chrome",count=8)
    rod(mesh,(x+radius+.018,y-.007,z),(x+radius+.018,y+.009,z),.0049,"hardware",count=8)


def feet_and_stands(mesh, clutch):
    for kind, spec in DATA["plates"].items():
        x, z, top = spec["baseX"], spec["baseZ"], spec["mast"]
        tripod(mesh,x,z,spec["spread"],spec["legAngle"],.227)
        rod(mesh,(x,.048,z),(x,.438,z),.0136)
        rod(mesh,(x,.415,z),(x,top-.168,z),.0108)
        rod(mesh,(x,top-.174,z),(x,top,z),.0084)
        for y, radius in ((.227,.021),(.418,.017),(top-.17,.0142)):
            collar(mesh,x,y,z,radius)
        diskx, disky, diskz = spec["x"],spec["y"],spec["z"]
        peak = disky + spec["bowRise"] + spec["bellRise"]
        if kind == "hi_hat":
            rod(mesh,(x,top-.087,z),(x,peak+.080,z),.0045,"chrome",count=10)
            rod(mesh,(x,top-.058,z),(x,top-.039,z),.0164,"hardware",.0152)
            rod(mesh,(x,top-.040,z),(x,top-.034,z),.0202,"rubber")
            # The clutch, felt stack, and upper plate move together on the pull rod.
            rod(clutch,(x,peak+.002,z),(x,peak+.015,z),.0132,"rubber",.0114)
            rod(clutch,(x,peak+.014,z),(x,peak+.048,z),.0112,"chrome",.0096)
            rod(clutch,(x+.009,peak+.035,z),(x+.025,peak+.035,z),.0044,"chrome")
            rod(clutch,(x+.025,peak+.029,z),(x+.025,peak+.041,z),.0062,"chrome",count=8)
        else:
            mount = (x,top,z)
            pivot = (diskx,disky-.060,diskz)
            rear = add(mount,tuple((a-b)*.31 for a,b in zip(mount,pivot)))
            rod(mesh,rear,pivot,.0089,"chrome",.0084)
            rod(mesh,add(mount,(0,-.025,0)),add(mount,(0,.019,0)),.0193,"chrome",.0172)
            collar(mesh,x,top+.004,z,.020)
            rod(mesh,pivot,(diskx,peak+.036,diskz),.0046,"chrome")
            rod(mesh,(diskx,peak-.017,diskz),(diskx,peak-.003,diskz),.0145,"rubber",.0113)
            rod(mesh,(diskx,peak+.003,diskz),(diskx,peak+.013,diskz),.0117,"rubber",.0107)
            rod(mesh,(diskx,peak+.018,diskz),(diskx,peak+.031,diskz),.0076,"chrome",count=8)
            rod(mesh,(diskx-.013,peak+.024,diskz),(diskx+.013,peak+.024,diskz),.0035,"chrome",count=8)


def stool(mesh):
    s = DATA["throne"]
    x,y,z,r = s["x"],s["y"],s["z"],s["radius"]
    tripod(mesh,x,z,s["spread"],90,.215)
    rod(mesh,(x,.085,z),(x,.367,z),.0161)
    rod(mesh,(x,.345,z),(x,y-.057,z),.0125)
    collar(mesh,x,.352,z,.0235)
    rod(mesh,(x,y-.066,z),(x,y-.050,z),r-.009,"rubber",r,40)
    rod(mesh,(x,y-.050,z),(x,y-.013,z),r,"throne",r,40)
    rod(mesh,(x,y-.036,z),(x,y-.032,z),r+.0011,"seat_seam",r+.0011,40)
    rod(mesh,(x,y-.014,z),(x,y,z),r,"throne",r-.019,40)


def footboard(mesh, x, heel_y, heel_z, toe_y, toe_z, heel_half, toe_half):
    top = ((x-heel_half,heel_y,heel_z),(x+heel_half,heel_y,heel_z),
           (x+toe_half,toe_y,toe_z),(x-toe_half,toe_y,toe_z))
    bottom = tuple(add(point,(0,-.008,0)) for point in top)
    mesh.face("footboard",top,((0,1),(1,1),(1,0),(0,0)))
    mesh.face("hardware",bottom,reverse=True)
    for i in range(4):
        j = (i+1)%4
        mesh.face("chrome",(top[j],top[i],bottom[i],bottom[j]))


def pedals(mesh, kick, hat, beater):
    x, y, z = DATA["kickHinge"]
    toe = .267
    footboard(kick,x,y+.009,z,.071,toe,.028,.036)
    plate(mesh,(x-.046,.007,.233),(x+.046,.017,z+.022),"hardware")
    plate(mesh,(x-.048,.012,z-.041),(x+.048,y+.013,z+.017),"chrome")
    for side in (-1,1):
        a = x+side*.039
        rod(mesh,(a,.023,.294),(a,.117,.271),.0075,"chrome")
    ax,ay,az = DATA["beaterAxle"]
    rod(mesh,(ax-.048,ay,az),(ax+.049,ay,az),.0105,"chrome")
    rod(mesh,(ax,.056,.268),(ax,ay,.272),.0031,"hardware")
    for link in range(5):
        cy = .070+link*.010
        rod(mesh,(ax-.0042,cy,.270),(ax+.0042,cy,.270),.0018,"chrome",count=6)
    rod(mesh,(ax+.051,.044,.291),(ax+.051,.120,.272),.0046,"chrome",count=8)
    rod(beater,(ax,ay,az),(ax,.312,.334),.0047,"chrome")
    rod(beater,(ax,.310,.314),(ax,.310,.341),.0164,"felt",.0180,16)
    rod(beater,(ax,.310,.337),(ax,.310,.348),.0132,"hardware",.0113,14)

    x,y,z = DATA["hatHinge"]
    footboard(hat,x,y+.010,z,.078,.427,.0275,.036)
    plate(mesh,(x-.047,.007,.386),(x+.047,.018,z+.015),"hardware")
    plate(mesh,(x-.043,.010,z-.034),(x+.043,y+.012,z+.018),"chrome")
    rod(mesh,(x-.048,.023,.425),(x-.037,.112,.425),.0057)
    rod(mesh,(x+.048,.023,.425),(x+.037,.112,.425),.0057)
    rod(mesh,(x,.049,.425),(x,.135,.425),.0081,"hardware",count=10)
    for link in range(6):
        yy = .058+link*.009
        rod(mesh,(x-.0045,yy,.421),(x+.0045,yy,.421),.0019,"chrome",count=6)


def preview_plate(mesh, disk, tile, offset=0, bowl=1):
    r = disk["diameter"]*.0127
    t = math.radians(disk["tilt"])

    def vertex(radius, segment):
        theta = TAU*segment/56
        x,z = radius*r*math.cos(theta), radius*r*math.sin(theta)
        bell = math.cos(math.pi/2 * min(radius*r/disk["bellRadius"],1))**2
        y = offset + bowl*(disk["bowRise"]*(1-radius**2)+disk["bellRise"]*bell)
        world = (disk["x"]+x,disk["y"]+y*math.cos(t)-z*math.sin(t),disk["z"]+y*math.sin(t)+z*math.cos(t))
        uv = ((tile%2)*.5+.25+x/r*.246,(tile//2)*.5+.25+z/r*.246)
        return world,uv

    for inside,outside in zip(RADII,RADII[1:]):
        for segment in range(56):
            points = [vertex(inside,segment+1),vertex(outside,segment+1),vertex(outside,segment),vertex(inside,segment)]
            coordinates,uv = zip(*points)
            mesh.face("cymbal_bronze",coordinates,uv)
            mesh.face("cymbal_bronze",coordinates,uv,reverse=True)


def main():
    finish_textures()
    hardware, hat, kick, beater, clutch = Mesh(), Mesh(), Mesh(), Mesh(), Mesh()
    feet_and_stands(hardware,clutch)
    stool(hardware)
    pedals(hardware,kick,hat,beater)
    parts = {"complete_hardware":hardware, "kick_beater":beater, "kick_footboard":kick, "hat_footboard":hat, "hat_clutch":clutch}
    inventory = Mesh()
    for name,mesh in parts.items():
        mesh.write(MESHES/(name+".obj"),(.5,0,.5),materials=MTL,source=SCRIPT,name=KIT)
        write_json(MESHES/(name+".json"),model_json(name))
        inventory.extend(mesh)
    for tile,disk in enumerate(DATA["plates"].values()):
        preview_plate(inventory,disk,tile)
        if tile == 0:
            preview_plate(inventory,disk,3,-.00145,-1)
    material = sorted({name for name,_,_ in inventory.faces})
    (MESHES/MTL).write_text("\n\n".join(
        f"newmtl {name}\nKd 1.0 1.0 1.0\nmap_Kd {MODELS+name}" for name in material
    )+"\n",encoding="ascii")
    inventory.write(MESHES/"complete_inventory.obj",(.643,.112,.366),.66,materials=MTL,source=SCRIPT,name=KIT)
    name = ASSETS/"models/item"/(ID+".json")
    write_json(name,{
        "loader":"neoforge:composite",
        "parent":"minecraft:block/block",
        "textures":{"particle":MODELS+"dus"},
        "children":{
            "drums":{"parent":NS+"item/"+SHELL_ID},
            "hardware": model_json("complete_inventory")
        },
        "display":{
            "gui":{"rotation":[23,146,0],"translation":[0,1.2,0],"scale":[.83,.83,.83]},
            "ground":{"translation":[0,2,0],"scale":[.50,.50,.50]},
            "fixed":{"rotation":[5,180,0],"translation":[0,1,0],"scale":[.8,.8,.8]},
            "firstperson_righthand":{"rotation":[0,135,0],"translation":[3,0,0],"scale":[.45,.45,.45]},
            "thirdperson_righthand":{"rotation":[64,0,0],"translation":[0,1.8,-1],"scale":[.36,.36,.36]}
        }
    })
    write_json(ASSETS/"blockstates"/(ID+".json"),{"variants":{"":{"model":MODELS+"empty"}}})


if __name__ == "__main__":
    main()
