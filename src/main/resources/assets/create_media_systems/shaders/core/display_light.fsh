#version 150

// A display's light on a block face or water surface in front of it (DisplayLight), added to it as it is.
// The screen is an area light, x 0..PictureSize.x and y 0..PictureSize.y on z = 0, shining towards +z, its colours the
// video's (Sampler1, whose mipmaps are its blur). Everything here is in that space.
// - Diffuse: the screen's exact form factor (Lambert's polygon formula, clipped to the face's horizon), coloured by
//   the part of the picture the face mostly sees, blurred as widely as it sees it (after Heitz et al. 2016's
//   textured area lights).
// - Specular: where the mirrored view ray meets the screen, the picture there blurred to the face's roughness,
//   weighted by how much of that blur falls on the screen and by Fresnel.
// - Bounce: some of the screen's light, its average colour, reflected about the room onto every face within reach,
//   turned towards it or not, as much as it is in the screen's room (room: none behind a wall). It does not fall
//   off with distance, as a lit room's walls and seats keep it in; the light's reach fades it at the end, and so does
//   the screen's plane, beside it.
// All of it seen as a camera would in a dark cinema, exposed for the room: what is near the screen saturates softly
// (1 - exp(-x)), and the far seats still show.
// - Shadows, of the screen's light and of its room's alike: how many of SPOTS points spread over the screen a pixel
//   sees past what stands about the display (Voxels and Shapes, DisplayShadows.Grid's: a block, or the part of it a
//   door, a slab or a fence fills). What the face's four corners agree on
//   (sure) is so all over it; a point only some of them see (unsure), each pixel looks for.
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

in vec2 texCoord0;
in vec4 material;
in vec3 localPos;
in vec3 localNormal;
flat in ivec2 sure;
flat in ivec2 unsure;

out vec4 fragColor;

const float PI = 3.14159265;
// How brightly the room is exposed: the gain on the light before it saturates.
const float EXPOSURE = 3.0;
// How bright the light bounced about the room is, against the screen's average colour.
const float BOUNCE = 0.3;
// As display_shadow.fsh's, both: the screen's points a shadow is found from, and how far out of its face a point looks
// from for whether it shares the screen's room.
const int SPOTS = 16;
const float ROOM_STEP = 0.6;

// The picture around `at` (blocks on the screen), blurred to about `footprint` blocks across.
vec3 video(vec2 at, float footprint) {
    vec2 size = vec2(textureSize(Sampler1, 0));
    float texelsPerBlock = max(size.x / PictureSize.x, size.y / PictureSize.y);
    float lod = clamp(log2(max(footprint * texelsPerBlock, 1.0)), 0.0, floor(log2(max(size.x, size.y))));
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

vec3 diffuse(vec3 P, vec3 N) {
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
        return vec3(0.0);
    }
    for (int i = 0; i < 5; i++) {
        L[i] = normalize(L[i]);
    }
    vec3 sum = edge(L[0], L[1]) + edge(L[1], L[2]) + edge(L[2], L[3]);
    if (n >= 4) sum += edge(L[3], L[4]);
    if (n == 5) sum += edge(L[4], L[0]);
    float formFactor = abs(sum.z) / (2.0 * PI);
    // The part of the picture lighting it most: where the form factor's direction meets the screen.
    vec3 lobe = sum * sign(sum.z);
    vec3 towards = frame * lobe;
    vec2 at = P.xy;
    if (towards.z < -1e-4) {
        at = (P + towards * (-P.z / towards.z)).xy;
    }
    return video(at, 0.5 + distance(P, vec3(clamp(at, vec2(0.0), PictureSize), 0.0))) * formFactor;
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
    vec3 light = video((lo + hi) * 0.5, 2.0 * s);
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

// Whether a ray meets a filled cell of a block's shape: from p, in the block's cells (0 to 4 a side), for `time` of
// its way d, a block's.
bool crosses(uvec2 shape, vec3 p, vec3 d, ivec3 step, vec3 delta, float time) {
    p = clamp(p, 0.0, 3.999);
    ivec3 sub = ivec3(p);
    vec3 next = vec3(boundary(p.x, d.x, sub.x), boundary(p.y, d.y, sub.y), boundary(p.z, d.z, sub.z)) * 0.25;
    for (int i = 0; i < 10; i++) {
        if (filled(shape, sub)) return true;
        if (min(next.x, min(next.y, next.z)) >= time) return false;
        if (next.x < next.y && next.x < next.z) {
            sub.x += step.x;
            next.x += delta.x * 0.25;
        } else if (next.y < next.z) {
            sub.y += step.y;
            next.y += delta.y * 0.25;
        } else {
            sub.z += step.z;
            next.z += delta.z * 0.25;
        }
        if (any(lessThan(sub, ivec3(0))) || any(greaterThan(sub, ivec3(3)))) return false;
    }
    return false;
}

// Whether something stands between two points of the display's block space, the first not in it (solid).
bool blocked(vec3 from, vec3 to) {
    vec3 d = to - from;
    ivec3 cell = ivec3(floor(from));
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
        if (k == 255) return true;
        if (k != 0 && crosses(texelFetch(Shapes, ivec2(k, 0), 0).rg, (from + d * t - vec3(cell)) * 4.0, d, step, delta,
                leave - t)) return true;
        if (leave >= 1.0) return false;
        t = leave;
        if (next.x < next.y && next.x < next.z) {
            cell.x += step.x;
            next.x += delta.x;
        } else if (next.y < next.z) {
            cell.y += step.y;
            next.y += delta.y;
        } else {
            cell.z += step.z;
            next.z += delta.z;
        }
    }
    return false;
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

// How much of the screen P, on a face turned N, sees past what stands between (x: of what is in front of the face,
// from the face, for the screen's light), and how much of it shares P's room (y: of it all, from ROOM_STEP out of the
// face, for the light bounced about).
vec2 sees(vec3 P, vec3 N) {
    // ToScreen's rows are the screen's right, up and out: turned back, the screen's space to the display's blocks'.
    mat3 toBlock = transpose(mat3(ToScreen));
    vec3 origin = ToScreen[3].xyz;
    vec4 near = vec4(0.0);
    vec3 far = toBlock * (P + N * ROOM_STEP - origin);
    bool roomy = false;
    if (unsure != ivec2(0)) {
        near = off(toBlock * (P - origin), toBlock * N);
        roomy = !solid(far);
    }
    int facing = 0;
    int lit = 0;
    int open = 0;
    for (int i = 0; i < SPOTS; i++) {
        int bit = 1 << i;
        // Each point on a column and a row of its own.
        int row = ((i & 1) << 3) | ((i & 2) << 1) | ((i & 4) >> 1) | ((i & 8) >> 3);
        vec3 spot = vec3((vec2(i, row) + 0.5) / float(SPOTS) * PictureSize, 0.05);
        vec3 to = toBlock * (spot - origin);
        if (dot(N, spot - P) > 0.0) {
            facing += 1;
            if ((sure.x & bit) != 0) {
                lit += 1;
            } else if ((unsure.x & bit) != 0 && near.w > 0.5 && !blocked(near.xyz, to)) {
                lit += 1;
            }
        }
        if ((sure.y & bit) != 0) {
            open += 1;
        } else if ((unsure.y & bit) != 0 && roomy && !blocked(far, to)) {
            open += 1;
        }
    }
    float seen = 1.0;
    if (facing != 0) seen = float(lit) / float(facing);
    return vec2(seen, float(open) / float(SPOTS));
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
    // From the face as it is, not as its ripples turn it.
    vec2 shade = sees(P, normalize(localNormal));
    float seen = shade.x;
    float room = shade.y;
    vec3 light = vec3(0.0);
    if (!water) {
        vec2 size = vec2(textureSize(Sampler1, 0));
        vec3 average = textureLod(Sampler1, vec2(0.5), floor(log2(max(size.x, size.y)))).rgb;
        vec3 bounce = BOUNCE * average * room * P.z / max(distanceToScreen, 1e-4);
        vec3 direct = vec3(0.0);
        if (seen > 0.0) {
            direct = diffuse(P, N) * seen;
        }
        light = albedo * (direct + bounce);
    }
    float roughness = material.r;
    if (Specular > 0.5 && roughness < 0.75 && seen > 0.0) {
        light += specular(P, N, roughness, material.g, albedo) * seen;
    }
    // Fading out towards the light's reach, rather than stopping at it.
    float d = min(distanceToScreen / Range, 1.0);
    float d4 = d * d * d * d;
    float reach = (1.0 - d4) * (1.0 - d4);
    fragColor = vec4((vec3(1.0) - exp(-EXPOSURE * Strength * light)) * reach, 1.0);
}
