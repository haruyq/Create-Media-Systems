#version 150

#moj_import <fog.glsl>

// Clear polished acrylic, as far as one can go without tracing rays through the world itself: how it looks changes
// with where it is seen from. Its faces mirror what is about them by Fresnel's law (hardly at all seen straight on,
// like a mirror seen along them), its edges show the light carried inside the sheet, and what is behind a thick
// sheet is seen where the sheet bends the sight to. Sampler0's alpha says which is face and which edge: the sheet's
// painted edges and rims are the more opaque texels (see tools/at_lpa2/gen_at_lpa2.py). Sampler3 and Sampler4 are
// the picture behind the face and its depth, and Sampler5 the blocks about the camera (TurntableRenderer).

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
// Where the camera is among the cells of Sampler5, and towards the sun, with how much of it the weather leaves.
uniform vec3 Eye;
uniform vec4 Sun;

in float vertexDistance;
in vec4 light;
in vec2 levels;
in vec2 texCoord0;
in vec3 toEye;
in vec3 normal;
in float thickness;

out vec4 fragColor;

// Acrylic's refractive index.
const float INDEX = 1.49;
// How many cells Sampler5 has each way (TurntableRenderer.SIDE), and through how many a sight is followed.
const int SIDE = 32;
const int STEPS = 64;
// How bright beside white the open sky is, a block that glows, the sun and the moon.
const float SKY = 1.6;
const float GLARE = 3.0;
const float SUN = 12.0;
const float MOON = 1.5;

// The light there is at a block light and a sky light (each 0 to 1).
vec3 lit(vec2 at) {
    return texelFetch(Sampler2, ivec2(at * 15.0 + 0.5), 0).rgb;
}

// The sky along d from where the light is as at: bluer overhead, with the sun and the moon where they are if their
// discs are wanted.
vec3 sky(vec3 d, vec2 at, float discs) {
    vec3 color = mix(FogColor.rgb, vec3(1.0), 0.5 - 0.35 * max(d.y, 0.0)) * SKY * lit(at);
    float sun = smoothstep(0.9960, 0.9975, dot(d, Sun.xyz));
    float moon = smoothstep(0.9975, 0.9985, dot(d, -Sun.xyz));
    return color + (SUN * sun + MOON * moon) * discs * Sun.w * at.y * at.y;
}

// What is seen from p, a place among the cells as Eye is, along d: the first full block that way, lit as the air
// before it is, or with none within reach the sky (see sky for discs). at is the light where p is.
vec3 seen(vec3 p, vec3 d, vec2 at, float discs) {
    ivec3 home = ivec3(floor(Eye));
    ivec3 cell = ivec3(floor(p));
    ivec3 way = ivec3(sign(d));
    // How far along d it is from one cell's wall to the next, and to the first of them.
    vec3 pace = 1.0 / max(abs(d), 0.00001);
    vec3 next = (0.5 + sign(d) * (vec3(cell) + 0.5 - p)) * pace;
    vec3 wall = vec3(0.0);
    // Near the end of what the cells hold, blocks give way to the sky, not to appear all at once on coming nearer.
    vec3 off = abs(p - Eye);
    float known = 1.0 - smoothstep(float(SIDE / 2) - 5.0, float(SIDE / 2) - 1.0, max(off.x, max(off.y, off.z)));
    for (int i = 0; i < STEPS; i++) {
        ivec3 from = cell - home;
        if (any(lessThan(from, ivec3(-SIDE / 2))) || any(greaterThanEqual(from, ivec3(SIDE / 2)))) {
            break;
        }
        ivec3 kept = cell & (SIDE - 1);
        vec4 here = texelFetch(Sampler5, ivec2(kept.x + SIDE * kept.z, kept.y), 0);
        if (here.a < 0.4) {
            at = here.rg;
        } else if (i > 0) {
            // As the game shades a block's faces: its top most, its bottom least. A block that glows is not shaded.
            float shade = wall.y > 0.5 ? (d.y > 0.0 ? 0.5 : 1.0) : wall.x > 0.5 ? 0.6 : 0.8;
            float glow = (here.a * 255.0 - 128.0) / 120.0;
            return mix(sky(d, at, discs), here.rgb * mix(shade * lit(at), vec3(GLARE), glow), known);
        }
        // A part that reaches into a full block's cell starts inside it: that block is not seen.
        wall = step(next.xyz, next.yzx) * step(next.xyz, next.zxy);
        next += wall * pace;
        cell += ivec3(wall) * way;
    }
    return sky(d, at, discs);
}

void main() {
    vec4 paint = texture(Sampler0, texCoord0);
    vec3 n = normalize(normal);
    vec3 v = normalize(toEye);
    vec3 p = Eye - toEye;
    float facing = abs(dot(n, v));
    // Schlick's approximation of Fresnel's law.
    float mirror = 0.04 + 0.96 * pow(1.0 - facing, 5.0);
    // The sheet's own veil is thinner than the texture's, which has to stay over the vanilla shader's cut-off.
    float alpha = max(paint.a - 0.1, 0.0) * 1.1;
    float edge = smoothstep(0.05, 0.28, alpha);
    // An edge scatters the light that falls on it, and shows a little of what the sheet's faces mirror inside it:
    // what is along the line of sight, turned back upwards, too mixed up in there to show the sun's disc.
    vec3 piped = seen(p, vec3(-v.x, v.y, -v.z), levels, 0.0);
    vec3 body = paint.rgb * mix(light.rgb, piped, 0.3 * edge);
    vec3 mirrored = seen(p, reflect(-v, n), levels, 1.0);
    // Both laid over what is behind: the body by its alpha, what is mirrored by how much the face mirrors.
    vec3 color = body * alpha * (1.0 - mirror) + mirrored * mirror;
    float cover = alpha + mirror * (1.0 - alpha);
    if (thickness > 0.0) {
        // The sight bends towards the normal as it enters, and leaves the sheet's far face short of where it would
        // have: behind this fragment is what is behind that place, unless something nearer than the face is there.
        vec3 bent = refract(-v, n, 1.0 / INDEX);
        vec3 leaves = -toEye + thickness / max(-dot(bent, n), 0.3) * (bent + v);
        vec4 there = ProjMat * ModelViewMat * vec4(leaves, 1.0);
        vec2 at = clamp(there.xy / there.w * 0.5 + 0.5, 0.0, 1.0);
        if (textureLod(Sampler4, at, 0.0).r < gl_FragCoord.z) {
            at = gl_FragCoord.xy / vec2(textureSize(Sampler3, 0));
        }
        // The picture behind has its fog already: only the sheet's own light is fogged.
        float clear = 1.0 - min(cover, 1.0);
        vec4 own = linear_fog(vec4(color / max(1.0 - clear, 0.004), 1.0), vertexDistance, FogStart, FogEnd, FogColor);
        fragColor = vec4(own.rgb * (1.0 - clear) + textureLod(Sampler3, at, 0.0).rgb * clear, 1.0) * ColorModulator;
    } else {
        cover = clamp(max(cover, max(color.r, max(color.g, color.b))), 0.004, 1.0);
        fragColor = linear_fog(vec4(color / cover, cover) * ColorModulator, vertexDistance, FogStart, FogEnd, FogColor);
    }
}
