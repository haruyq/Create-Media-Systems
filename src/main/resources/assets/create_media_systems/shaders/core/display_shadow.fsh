#version 150

// For a vertex of a face a display lights (DisplayShadows): which of SPOTS points spread over the screen it sees past
// what stands about the display (Voxels and Shapes, DisplayShadows.Grid's). display_light.vsh reads a face's four, and
// display_light.fsh traces again, per pixel, only to the points they don't agree on.

uniform usampler3D Voxels;
uniform usampler2D Shapes;
uniform vec3 VoxelMin;
uniform vec3 ScreenOrigin;
uniform vec3 ScreenRight;
uniform vec3 ScreenOut;
uniform vec2 PictureSize;

// As display_light.fsh's, both: the points, and how far out of its face a point looks from for whether it shares the
// screen's room (for its bounced light), whichever way the face is turned.
const int SPOTS = 16;
const float ROOM_STEP = 0.6;

in vec3 point;
in vec3 normal;

out vec4 fragColor;

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

void main() {
    vec4 near = off(point, normal);
    vec3 far = point + normal * ROOM_STEP;
    bool roomy = !solid(far);
    int lit = 0;
    int open = 0;
    for (int i = 0; i < SPOTS; i++) {
        // Each point on a column and a row of its own.
        int row = ((i & 1) << 3) | ((i & 2) << 1) | ((i & 4) >> 1) | ((i & 8) >> 3);
        vec2 at = (vec2(i, row) + 0.5) / float(SPOTS) * PictureSize;
        vec3 spot = ScreenOrigin + ScreenRight * at.x + vec3(0.0, at.y, 0.0) + ScreenOut * 0.05;
        if (near.w > 0.5 && dot(normal, spot - point) > 0.0 && !blocked(near.xyz, spot)) lit |= 1 << i;
        if (roomy && !blocked(far, spot)) open |= 1 << i;
    }
    fragColor = vec4(lit & 255, lit >> 8, open & 255, open >> 8) / 255.0;
}
