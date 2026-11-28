#version 150

// A display's light on a block face or water surface in front of it (DisplayLight), added to it as it is.
// The screen is an area light, x 0..PictureSize.x and y 0..PictureSize.y on z = 0, shining towards +z, its colours the
// video's (Sampler1, whose mipmaps are its blur). Everything here is in that space.
// - Diffuse: the screen's exact form factor (Lambert's polygon formula, clipped to the face's horizon), coloured by
//   all of the picture: SPOTS points spread over the screen, each the picture about it, weighing as that part of the
//   screen lights the face (nearer and more squarely, more). So a wall beside a red half is red, the middle of the
//   room both halves' mix, and a shadow the colour of the parts still seen.
// - Specular: where the mirrored view ray meets the screen, the picture there blurred to the face's roughness,
//   weighted by how much of that blur falls on the screen and by Fresnel.
// - Bounce: the screen's light, its average colour, that reaches the face by way of one other face, tinted by that
//   face's block (bounced, display_shadow.fsh's second pass, a face's corners blended over it): what lights a face
//   turned away from the screen, or one in a shadow.
// All of it seen as a camera would in a dark cinema, exposed for the room: what is near the screen saturates softly
// (1 - exp(-x)), and the far seats still show.
// - Shadows: which of those points a pixel sees past what stands about the display (Voxels and Shapes,
//   DisplayShadows.Grid's: a block, or the part of it a door, a slab or a fence fills). What the face's four corners
//   agree on (sure) is so all over it; a point only some of them see (unsure), each pixel looks for. A face no
//   shadow's edge crosses takes its light as its corners found it (shone, display_light.vsh's), between them.
// - Bodies' shadows: a mob or a player in the light is the boxes its model is made of (Parts: for each, the screen's
//   space to its unit cube, three rows; a body's end before its PartEnds), hiding from a pixel the points a line to
//   which passes through one. Bodies: each's bounds, its least corner, then its greatest, BodyCount of them.
// material: roughness, F0 (above 0.5, a metal), display_shadow.vsh's, and 1 for a block's face or 0 for water.
// No ternaries inside expressions: Veil (with Sable) reprints this shader and drops their parentheses.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform usampler3D Voxels;
uniform usampler2D Shapes;

// Not ScreenSize: vanilla sets a uniform of that name to the window's size on every draw.
uniform vec2 PictureSize;
uniform mat4 ToScreen;
uniform vec3 VoxelMin;
uniform vec3 CameraPos;
uniform float Range;
uniform float Strength;
uniform float Specular;
uniform float GameTime;
uniform vec3 Bodies[16];
uniform int BodyCount;
uniform vec4 Parts[192];
uniform int PartEnds[8];

in vec2 texCoord0;
in vec4 material;
in vec3 localPos;
in vec3 localNormal;
flat in int sure;
flat in int unsure;
in vec3 bounced;
in vec4 shone;

out vec4 fragColor;

const float PI = 3.14159265;
// How brightly the room is exposed: the gain on the light before it saturates.
const float EXPOSURE = 12.0;
// How bright the light bounced off one face is: above 1, for the light that bounces on, off a second and a third.
const float BOUNCE = 1.5;
// As display_shadow.fsh's: the screen's points its light and its shadows are found from.
const int SPOTS = 32;

// The picture's mipmap that blurs it to about `footprint` blocks across.
float level(float footprint) {
    vec2 size = vec2(textureSize(Sampler1, 0));
    float texelsPerBlock = max(size.x / PictureSize.x, size.y / PictureSize.y);
    return clamp(log2(max(footprint * texelsPerBlock, 1.0)), 0.0, floor(log2(max(size.x, size.y))));
}

// The picture around `at` (blocks on the screen), blurred as level() says.
vec3 video(vec2 at, float lod) {
    at = clamp(at, vec2(0.0), PictureSize);
    return textureLod(Sampler1, vec2(at.x / PictureSize.x, 1.0 - at.y / PictureSize.y), lod).rgb;
}

// The quad L[0..3] (directions from the shaded point, its normal +z) cut to the part above the horizon, in L[0..n-1].
// After the reference code of Heitz et al., "Real-Time Polygonal-Light Shading with Linearly Transformed Cosines".
void clipToHorizon(inout vec3 L[5], out int n) {
    int config = 0;
    if (L[0].z > 0.0) config += 1;
    if (L[1].z > 0.0) config += 2;
    if (L[2].z > 0.0) config += 4;
    if (L[3].z > 0.0) config += 8;
    n = 0;
    if (config == 1) {
        n = 3;
        L[1] = -L[1].z * L[0] + L[0].z * L[1];
        L[2] = -L[3].z * L[0] + L[0].z * L[3];
    } else if (config == 2) {
        n = 3;
        L[0] = -L[0].z * L[1] + L[1].z * L[0];
        L[2] = -L[2].z * L[1] + L[1].z * L[2];
    } else if (config == 3) {
        n = 4;
        L[2] = -L[2].z * L[1] + L[1].z * L[2];
        L[3] = -L[3].z * L[0] + L[0].z * L[3];
    } else if (config == 4) {
        n = 3;
        L[0] = -L[3].z * L[2] + L[2].z * L[3];
        L[1] = -L[1].z * L[2] + L[2].z * L[1];
    } else if (config == 6) {
        n = 4;
        L[0] = -L[0].z * L[1] + L[1].z * L[0];
        L[3] = -L[3].z * L[2] + L[2].z * L[3];
    } else if (config == 7) {
        n = 5;
        L[4] = -L[3].z * L[0] + L[0].z * L[3];
        L[3] = -L[3].z * L[2] + L[2].z * L[3];
    } else if (config == 8) {
        n = 3;
        L[0] = -L[0].z * L[3] + L[3].z * L[0];
        L[1] = -L[2].z * L[3] + L[3].z * L[2];
        L[2] = L[3];
    } else if (config == 9) {
        n = 4;
        L[1] = -L[1].z * L[0] + L[0].z * L[1];
        L[2] = -L[2].z * L[3] + L[3].z * L[2];
    } else if (config == 11) {
        n = 5;
        L[4] = L[3];
        L[3] = -L[2].z * L[3] + L[3].z * L[2];
        L[2] = -L[2].z * L[1] + L[1].z * L[2];
    } else if (config == 12) {
        n = 4;
        L[1] = -L[1].z * L[2] + L[2].z * L[1];
        L[0] = -L[0].z * L[3] + L[3].z * L[0];
    } else if (config == 13) {
        n = 5;
        L[4] = L[3];
        L[3] = L[2];
        L[2] = -L[1].z * L[2] + L[2].z * L[1];
        L[1] = -L[1].z * L[0] + L[0].z * L[1];
    } else if (config == 14) {
        n = 5;
        L[4] = -L[0].z * L[3] + L[3].z * L[0];
        L[0] = -L[0].z * L[1] + L[1].z * L[0];
    } else if (config == 15) {
        n = 4;
    }
    if (n == 3) L[3] = L[0];
    if (n == 4) L[4] = L[0];
}

// The edge's share of the polygon's vector form factor, times 2 pi.
vec3 edge(vec3 a, vec3 b) {
    float theta = acos(clamp(dot(a, b), -0.9999, 0.9999));
    return cross(a, b) * (theta / sin(theta));
}

// How much of all that P, on a face turned N, looks at is the screen.
float formFactor(vec3 P, vec3 N) {
    vec3 up = vec3(0.0, 1.0, 0.0);
    if (abs(N.y) > 0.99) {
        up = vec3(1.0, 0.0, 0.0);
    }
    vec3 t1 = normalize(cross(N, up));
    vec3 t2 = cross(N, t1);
    mat3 frame = mat3(t1, t2, N);
    mat3 toFrame = transpose(frame);
    vec3 L[5];
    L[0] = toFrame * (vec3(0.0, 0.0, 0.0) - P);
    L[1] = toFrame * (vec3(PictureSize.x, 0.0, 0.0) - P);
    L[2] = toFrame * (vec3(PictureSize, 0.0) - P);
    L[3] = toFrame * (vec3(0.0, PictureSize.y, 0.0) - P);
    L[4] = L[3];
    int n;
    clipToHorizon(L, n);
    if (n == 0) {
        return 0.0;
    }
    for (int i = 0; i < 5; i++) {
        L[i] = normalize(L[i]);
    }
    vec3 sum = edge(L[0], L[1]) + edge(L[1], L[2]) + edge(L[2], L[3]);
    if (n >= 4) sum += edge(L[3], L[4]);
    if (n == 5) sum += edge(L[4], L[0]);
    return abs(sum.z) / (2.0 * PI);
}

vec3 specular(vec3 P, vec3 N, float roughness, float f0, vec3 albedo) {
    vec3 V = normalize(CameraPos - P);
    float facing = dot(N, V);
    if (facing <= 0.0) {
        return vec3(0.0);
    }
    vec3 R = reflect(-V, N);
    if (R.z > -1e-4) {
        return vec3(0.0);
    }
    float t = -P.z / R.z;
    vec2 hit = (P + R * t).xy;
    // Half the width, on the screen, of the blur a surface this rough sees there.
    float s = t * max(roughness * roughness * 1.5, 0.004) + 0.01;
    vec2 lo = max(hit - s, vec2(0.0));
    vec2 hi = min(hit + s, PictureSize);
    vec2 cover = clamp(hi - lo, 0.0, 2.0 * s) / (2.0 * s);
    float coverage = cover.x * cover.y;
    if (coverage <= 0.0) {
        return vec3(0.0);
    }
    vec3 light = video((lo + hi) * 0.5, level(2.0 * s));
    vec3 F0 = vec3(f0);
    if (f0 > 0.5) {
        F0 = albedo;
    }
    vec3 fresnel = F0 + (max(vec3(1.0 - roughness), F0) - F0) * pow(1.0 - facing, 5.0);
    return fresnel * light * coverage;
}

float boundary(float from, float d, int cell) {
    if (d > 0.0) return (float(cell + 1) - from) / d;
    if (d < 0.0) return (float(cell) - from) / d;
    return 1e20;
}

// What of a block stands in the light's way (DisplayShadows.Grid): 0 nothing, 255 all of it, else which of Shapes.
int kind(ivec3 cell) {
    ivec3 at = cell - ivec3(VoxelMin);
    if (any(lessThan(at, ivec3(0))) || any(greaterThanEqual(at, textureSize(Voxels, 0)))) return 0;
    return int(texelFetch(Voxels, at, 0).r);
}

// A shape is its block's 4 x 4 x 4 cells, a bit each: x + 4 y + 16 z.
bool filled(uvec2 shape, ivec3 sub) {
    int bit = sub.x + 4 * sub.y + 16 * sub.z;
    if (bit < 32) return (shape.x & (1u << uint(bit))) != 0u;
    return (shape.y & (1u << uint(bit - 32))) != 0u;
}

bool solid(vec3 p) {
    ivec3 cell = ivec3(floor(p));
    int k = kind(cell);
    if (k == 0) return false;
    if (k == 255) return true;
    return filled(texelFetch(Shapes, ivec2(k, 0), 0).rg, ivec3((p - vec3(cell)) * 4.0));
}

// When a ray meets a filled cell of a block's shape, or -1: from p, in the block's cells (0 to 4 a side), within
// `time` of its way d, a block's. face: the side of the cell it comes in by.
float crosses(uvec2 shape, vec3 p, vec3 d, ivec3 step, vec3 delta, float time, inout vec3 face) {
    p = clamp(p, 0.0, 3.999);
    ivec3 sub = ivec3(p);
    vec3 next = vec3(boundary(p.x, d.x, sub.x), boundary(p.y, d.y, sub.y), boundary(p.z, d.z, sub.z)) * 0.25;
    float t = 0.0;
    for (int i = 0; i < 10; i++) {
        if (filled(shape, sub)) return t;
        t = min(next.x, min(next.y, next.z));
        if (t >= time) return -1.0;
        if (next.x < next.y && next.x < next.z) {
            sub.x += step.x;
            next.x += delta.x * 0.25;
            face = vec3(-step.x, 0.0, 0.0);
        } else if (next.y < next.z) {
            sub.y += step.y;
            next.y += delta.y * 0.25;
            face = vec3(0.0, -step.y, 0.0);
        } else {
            sub.z += step.z;
            next.z += delta.z * 0.25;
            face = vec3(0.0, 0.0, -step.z);
        }
        if (any(lessThan(sub, ivec3(0))) || any(greaterThan(sub, ivec3(3)))) return -1.0;
    }
    return -1.0;
}

// How far along the way between two points of the display's block space (0 to 1) something stands, or 2 if nothing
// does; the first point is not in anything (solid). cell: the block met, face: the side of it the ray comes in by.
float meets(vec3 from, vec3 to, out ivec3 cell, out vec3 face) {
    vec3 d = to - from;
    cell = ivec3(floor(from));
    face = vec3(0.0);
    ivec3 step = ivec3(sign(d));
    vec3 next = vec3(boundary(from.x, d.x, cell.x), boundary(from.y, d.y, cell.y), boundary(from.z, d.z, cell.z));
    vec3 delta = vec3(1e20);
    if (d.x != 0.0) delta.x = abs(1.0 / d.x);
    if (d.y != 0.0) delta.y = abs(1.0 / d.y);
    if (d.z != 0.0) delta.z = abs(1.0 / d.z);
    float t = 0.0;
    for (int guard = 0; guard < 1024; guard++) {
        float leave = min(min(next.x, min(next.y, next.z)), 1.0);
        int k = kind(cell);
        if (k == 255) return t;
        if (k != 0) {
            float within = crosses(texelFetch(Shapes, ivec2(k, 0), 0).rg, (from + d * t - vec3(cell)) * 4.0, d, step,
                    delta, leave - t, face);
            if (within >= 0.0) return t + within;
        }
        if (leave >= 1.0) return 2.0;
        t = leave;
        if (next.x < next.y && next.x < next.z) {
            cell.x += step.x;
            next.x += delta.x;
            face = vec3(-step.x, 0.0, 0.0);
        } else if (next.y < next.z) {
            cell.y += step.y;
            next.y += delta.y;
            face = vec3(0.0, -step.y, 0.0);
        } else {
            cell.z += step.z;
            next.z += delta.z;
            face = vec3(0.0, 0.0, -step.z);
        }
    }
    return 2.0;
}

bool blocked(vec3 from, vec3 to) {
    ivec3 cell;
    vec3 face;
    return meets(from, to, cell, face) < 1.5;
}

// Where a ray off a face at p, turned n, starts: just off it. A shape's cells are a quarter of a block, so may cover
// the face of what they stand for (a chest's lid is inside its top ones): then a cell further. w: 0 if still covered.
vec4 off(vec3 p, vec3 n) {
    vec3 from = p + n * 0.02;
    if (!solid(from)) return vec4(from, 1.0);
    from += n * 0.25;
    if (!solid(from)) return vec4(from, 1.0);
    return vec4(from, 0.0);
}

// Each body's shadow on the screen's plane, cast from the pixel: about its bounds' corners'. Those of them that fall
// on the screen, and which of Parts each is made of.
vec2 least[8];
vec2 most[8];
int begins[8];
int ends[8];
int bodies;

void shadows(vec3 P) {
    bodies = 0;
    int next = 0;
    for (int b = 0; b < 8; b++) {
        if (b >= BodyCount) break;
        int begin = next;
        next = PartEnds[b];
        vec3 lo = Bodies[2 * b];
        vec3 hi = Bodies[2 * b + 1];
        // Only what of it is nearer the screen than P stands between them.
        hi.z = min(hi.z, P.z - 0.01);
        if (hi.z <= lo.z) continue;
        vec2 from = vec2(1e9);
        vec2 to = vec2(-1e9);
        for (int c = 0; c < 8; c++) {
            vec3 corner = mix(lo, hi, vec3(c & 1, (c >> 1) & 1, c >> 2));
            vec2 on = P.xy + (corner.xy - P.xy) * (P.z / (P.z - corner.z));
            from = min(from, on);
            to = max(to, on);
        }
        if (any(greaterThan(from, PictureSize)) || any(lessThan(to, vec2(0.0)))) continue;
        least[bodies] = from;
        most[bodies] = to;
        begins[bodies] = begin;
        ends[bodies] = next;
        bodies += 1;
    }
}

// Whether the line from P on by d, to its end, passes through one of the boxes of the body shadows() kept as b.
bool hides(int b, vec3 P, vec3 d) {
    for (int p = begins[b]; p < ends[b]; p++) {
        // In the box's own space, where it is the unit cube.
        vec3 o = vec3(dot(Parts[3 * p], vec4(P, 1.0)), dot(Parts[3 * p + 1], vec4(P, 1.0)),
                dot(Parts[3 * p + 2], vec4(P, 1.0)));
        vec3 along = vec3(dot(Parts[3 * p].xyz, d), dot(Parts[3 * p + 1].xyz, d), dot(Parts[3 * p + 2].xyz, d));
        along += vec3(lessThan(abs(along), vec3(1e-6))) * 1e-6;
        vec3 one = -o / along;
        vec3 two = (1.0 - o) / along;
        vec3 enters = min(one, two);
        vec3 leaves = max(one, two);
        float enter = max(max(enters.x, enters.y), enters.z);
        float leave = min(min(leaves.x, leaves.y), leaves.z);
        if (enter < leave && leave > 0.0 && enter < 1.0) return true;
    }
    return false;
}

// The screen's light on P, on a face turned N, of what it would be were the screen white and nothing in its way: its
// colour (rgb), the picture's at each of the screen's points P sees past what stands between, and how much of it
// comes (a). If no point is in front of the face, `average`, all of it. After shadows(). A point stands for the screen
// `around` it.
vec4 shines(vec3 P, vec3 N, vec3 average, float around) {
    // ToScreen's rows are the screen's right, up and out: turned back, the screen's space to the display's blocks'.
    mat3 toBlock = transpose(mat3(ToScreen));
    vec3 origin = ToScreen[3].xyz;
    vec4 near = vec4(0.0);
    if (unsure != 0) {
        near = off(toBlock * (P - origin), toBlock * N);
    }
    float share = PictureSize.x * PictureSize.y / float(SPOTS);
    float lod = level(2.0 * around);
    float whole = 0.0;
    vec4 lit = vec4(0.0);
    for (int i = 0; i < SPOTS; i++) {
        int bit = 1 << i;
        // Each point on a column and a row of its own.
        int row = ((i & 1) << 4) | ((i & 2) << 2) | (i & 4) | ((i & 8) >> 2) | ((i & 16) >> 4);
        vec3 spot = vec3((vec2(i, row) + 0.5) / float(SPOTS) * PictureSize, 0.05);
        vec3 d = spot - P;
        float facing = dot(N, d);
        if (facing <= 0.0) continue;
        // As a patch of the screen lights a face: by the cosines at the face and at the screen, over the distance
        // squared. Of these, how far out of the screen P is is the same for every point, so left out. No nearer than
        // the patch is wide, which a point is not.
        float r2 = dot(d, d) + share;
        float weight = facing / (r2 * r2);
        whole += weight;
        float through = 0.0;
        if ((sure & bit) != 0) {
            through = 1.0;
        } else if ((unsure & bit) != 0 && near.w > 0.5 && !blocked(near.xyz, toBlock * (spot - origin))) {
            through = 1.0;
        }
        for (int b = 0; b < bodies; b++) {
            if (through <= 0.0) break;
            if (any(lessThan(spot.xy, least[b])) || any(greaterThan(spot.xy, most[b]))) continue;
            if (hides(b, P, d)) {
                through = 0.0;
            }
        }
        if (through > 0.0) {
            lit += weight * through * vec4(video(spot.xy, lod), 1.0);
        }
    }
    if (whole <= 0.0) return vec4(average, 1.0);
    return lit / whole;
}

void main() {
    vec3 P = localPos;
    if (P.z <= 0.0) {
        discard;
    }
    float distanceToScreen = distance(P, vec3(clamp(P.xy, vec2(0.0), PictureSize), 0.0));
    if (distanceToScreen >= Range) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    bool water = material.a < 0.5;
    vec3 N = normalize(localNormal);
    vec3 albedo = vec3(0.0);
    if (water) {
        // Ripples: the surface's normal swaying a little.
        float t = GameTime * 1200.0;
        vec2 w = P.xz;
        N = normalize(vec3(0.03 * (sin(w.x * 1.7 + t * 1.3) + 0.6 * sin(w.y * 2.3 - t * 1.1 + w.x * 0.7)), 1.0,
                0.03 * (cos(w.y * 1.9 + t * 1.5) + 0.6 * cos(w.x * 2.1 + w.y * 0.9 + t * 0.9))));
    } else {
        vec4 texel = texture(Sampler0, texCoord0);
        if (texel.a < 0.1) {
            discard;
        }
        albedo = texel.rgb;
    }
    vec2 size = vec2(textureSize(Sampler1, 0));
    vec3 average = textureLod(Sampler1, vec2(0.5), floor(log2(max(size.x, size.y)))).rgb;
    // A point stands for the screen about it: a square of half again its share of the screen, so that no part of
    // the picture falls between two points.
    float around = 0.75 * sqrt(PictureSize.x * PictureSize.y / float(SPOTS));
    shadows(P);
    vec4 lit = shone;
    if (unsure != 0 || bodies > 0) {
        // From the face as it is, not as its ripples turn it.
        lit = shines(P, normalize(localNormal), average, around);
    }
    vec3 light = vec3(0.0);
    if (!water) {
        light = albedo * (formFactor(P, N) * lit.rgb + BOUNCE * average * bounced);
    }
    float roughness = material.r;
    if (Specular > 0.5 && roughness < 0.75 && lit.a > 0.0) {
        light += specular(P, N, roughness, material.g, albedo) * lit.a;
    }
    // The light falls off as the screen's does, by how much of what a face looks at is screen. Range only ends the
    // faces drawn, where little of it is left: no edge there, fading out over the last of it.
    float reach = 1.0 - smoothstep(0.6, 1.0, distanceToScreen / Range);
    fragColor = vec4((vec3(1.0) - exp(-EXPOSURE * Strength * light)) * reach, 1.0);
}
