#version 150

// A stage light's light on a block face (LightingIllumination), added to the face as it is: the beam's cone from
// Origin along Direction, Tangent wide, Range long (the same cone as LightingIllumination.exposure), falling on the
// face as it is turned to the light. Everything is in the lamp's block space. The vertices' colour is the face's tint,
// its alpha how much of the light gets there past the blocks between (traced on the CPU at each corner of a
// quarter of the face, faded between them).
// No ternaries inside expressions: Veil (with Sable) reprints this shader and drops their parentheses.

uniform sampler2D Sampler0;

uniform vec3 Origin;
uniform vec3 Direction;
uniform float Tangent;
uniform float Range;
uniform float Strength;
uniform vec3 LightColor;
// With Shadowed, the light is traced here, a pixel at a time, through the blocks round the camera (LightingOcclusion):
// a texel a block, a bit for each of its half-block corners a collision shape fills (bit x + 2y + 4z). Its unit is set
// on the program itself; Minecraft only binds 2D samplers.
uniform int Shadowed;
uniform usampler3D Occupancy;
uniform vec3 GridOrigin;
uniform int GridSize;

in vec2 texCoord0;
in vec4 tint;
in vec3 localPos;
in vec3 localNormal;

out vec4 fragColor;

// Whether half-block cell (in the grid's half blocks) is filled; outside the grid nothing is.
bool filled(ivec3 cell) {
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(GridSize * 2)))) {
        return false;
    }
    uint bits = texelFetch(Occupancy, cell / 2, 0).r;
    int bit = (cell.x & 1) + (cell.y & 1) * 2 + (cell.z & 1) * 4;
    return ((bits >> uint(bit)) & 1u) != 0u;
}

// How much of the light from Origin reaches this point, just off its face: 0 if a filled half block lies between,
// the first ones passed if the face lies inside them (its block's model drawn apart from its shape) excepted, and
// the last block before the lamp, the lamp's own, as well.
float reaches(vec3 point) {
    vec3 start = (point - GridOrigin) * 2.0;
    vec3 toward = (Origin - GridOrigin) * 2.0 - start;
    float span = length(toward);
    vec3 dir = toward / span;
    ivec3 cell = ivec3(floor(start));
    ivec3 stepTo = ivec3(sign(dir));
    vec3 across = 1.0 / max(abs(dir), vec3(1e-6));
    vec3 next = abs((vec3(cell) + step(0.0, dir) - start) * across);
    float end = span - 2.0;
    bool leaving = true;
    for (int i = 0; i < 512; i++) {
        bool hit = filled(cell);
        if (leaving) {
            leaving = hit;
        } else if (hit) {
            return 0.0;
        }
        float t = min(next.x, min(next.y, next.z));
        if (t > end) {
            break;
        }
        if (next.x == t) {
            next.x += across.x;
            cell.x += stepTo.x;
        } else if (next.y == t) {
            next.y += across.y;
            cell.y += stepTo.y;
        } else {
            next.z += across.z;
            cell.z += stepTo.z;
        }
    }
    return 1.0;
}

void main() {
    vec3 offset = localPos - Origin;
    float along = dot(offset, Direction);
    if (along <= 0.0 || along >= Range) {
        discard;
    }
    float radius = max(0.08, along * Tangent);
    float radial = sqrt(max(0.0, dot(offset, offset) - along * along)) / radius;
    float edge = clamp((1.0 - radial) / 0.35, 0.0, 1.0);
    float reach = clamp((Range - along) / max(1.0, Range * 0.2), 0.0, 1.0);
    float exposure = edge * edge * (3.0 - 2.0 * edge) * reach;
    float facing = max(0.0, dot(normalize(localNormal), -normalize(offset)));
    float opacity = clamp(exposure * Strength * facing * 3.0, 0.0, 1.0) * tint.a;
    if (opacity <= 0.0) {
        discard;
    }
    vec4 texel = texture(Sampler0, texCoord0);
    if (texel.a < 0.1) {
        discard;
    }
    if (Shadowed != 0 && reaches(localPos + normalize(localNormal) * 0.02) <= 0.0) {
        discard;
    }
    fragColor = vec4(texel.rgb * LightColor * tint.rgb, opacity);
}
