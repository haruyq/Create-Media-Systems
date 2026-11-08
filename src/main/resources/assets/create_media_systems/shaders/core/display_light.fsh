#version 150

// A display's light on a block face or water surface in front of it (DisplayLight), added to it as it is.
// The screen is an area light, x 0..ScreenSize.x and y 0..ScreenSize.y on z = 0, shining towards +z, its colours the
// video's (Sampler1, whose mipmaps are its blur). Everything here is in that space.
// - Diffuse: the screen's exact form factor (Lambert's polygon formula, clipped to the face's horizon), coloured by
//   the part of the picture the face mostly sees, blurred as widely as it sees it (after Heitz et al. 2016's
//   textured area lights).
// - Specular: where the mirrored view ray meets the screen, the picture there blurred to the face's roughness,
//   weighted by how much of that blur falls on the screen and by Fresnel.
// - Bounce: some of the screen's light, its average colour, reflected about the room onto every face near enough,
//   turned towards it or not, as much as it is in the screen's room (room: none behind a wall).
// All of it seen as a camera would in a dark cinema, exposed for the room: what is near the screen saturates softly
// (1 - exp(-x)), and the far seats still show.
// material: roughness, F0 (above 0.5, a metal), how much of the screen the point sees past blocks between, and 1 for
// a block's face or 0 for water.
// No ternaries inside expressions: Veil (with Sable) reprints this shader and drops their parentheses.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform vec2 ScreenSize;
uniform vec3 CameraPos;
uniform float Range;
uniform float Strength;
uniform float Specular;
uniform float GameTime;

in vec2 texCoord0;
in vec4 material;
in vec3 localPos;
in vec3 localNormal;
in float room;

out vec4 fragColor;

const float PI = 3.14159265;
// How brightly the room is exposed: the gain on the light before it saturates.
const float EXPOSURE = 3.0;
// The share of the screen's light bounced about the room.
const float BOUNCE = 0.04;

// The picture around `at` (blocks on the screen), blurred to about `footprint` blocks across.
vec3 video(vec2 at, float footprint) {
    vec2 size = vec2(textureSize(Sampler1, 0));
    float texelsPerBlock = max(size.x / ScreenSize.x, size.y / ScreenSize.y);
    float lod = clamp(log2(max(footprint * texelsPerBlock, 1.0)), 0.0, floor(log2(max(size.x, size.y))));
    at = clamp(at, vec2(0.0), ScreenSize);
    return textureLod(Sampler1, vec2(at.x / ScreenSize.x, 1.0 - at.y / ScreenSize.y), lod).rgb;
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
    L[1] = toFrame * (vec3(ScreenSize.x, 0.0, 0.0) - P);
    L[2] = toFrame * (vec3(ScreenSize, 0.0) - P);
    L[3] = toFrame * (vec3(0.0, ScreenSize.y, 0.0) - P);
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
    return video(at, 0.5 + distance(P, vec3(clamp(at, vec2(0.0), ScreenSize), 0.0))) * formFactor;
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
    vec2 hi = min(hit + s, ScreenSize);
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

void main() {
    vec3 P = localPos;
    if (P.z <= 0.0) {
        discard;
    }
    float distanceToScreen = distance(P, vec3(clamp(P.xy, vec2(0.0), ScreenSize), 0.0));
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
    float seen = material.b;
    vec3 light = vec3(0.0);
    if (!water) {
        vec2 size = vec2(textureSize(Sampler1, 0));
        vec3 average = textureLod(Sampler1, vec2(0.5), floor(log2(max(size.x, size.y)))).rgb;
        float area = ScreenSize.x * ScreenSize.y;
        vec3 bounce = BOUNCE * average * area / (area + PI * distanceToScreen * distanceToScreen) * room;
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
