#version 150

// For a vertex of a face a display lights (DisplayShadows), past what stands about the display (Voxels and Shapes,
// DisplayShadows.Grid's):
// - Pass 0: which of SPOTS points spread over the screen it sees. display_light.vsh reads a face's four, and
//   display_light.fsh traces again, per pixel, only to the points they don't agree on.
// - Pass 1: the screen's light that reaches it by way of one other face, for a screen white all over (its square
//   root). From the vertex, SPOTS rays over all it faces; where one meets a block, that block's colour times how much
//   of the screen's light falls there: the screen's form factor, from one point of it, if seen from there.

uniform usampler3D Voxels;
uniform usampler2D Shapes;
uniform vec3 VoxelMin;
uniform vec3 ScreenOrigin;
uniform vec3 ScreenRight;
uniform vec3 ScreenOut;
uniform vec2 PictureSize;
uniform int Pass;

const float PI = 3.14159265;
// As display_light.fsh's.
const int SPOTS = 32;
// How far, in blocks, a vertex looks for the faces that light it.
const float BOUNCE_REACH = 32.0;

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

// The screen's i-th point, and through `row` its row: each on a column and a row of its own.
vec3 spot(int i, out int row) {
    row = ((i & 1) << 4) | ((i & 2) << 2) | (i & 4) | ((i & 8) >> 2) | ((i & 16) >> 4);
    vec2 at = (vec2(i, row) + 0.5) / float(SPOTS) * PictureSize;
    return ScreenOrigin + ScreenRight * at.x + vec3(0.0, at.y, 0.0) + ScreenOut * 0.05;
}

// A block's colour: its map colour, in Shapes' second row, by Voxels' green.
vec3 colour(ivec3 cell) {
    uint id = texelFetch(Voxels, cell - ivec3(VoxelMin), 0).g;
    uint rgb = texelFetch(Shapes, ivec2(int(id), 1), 0).r;
    return vec3((rgb >> 16u) & 255u, (rgb >> 8u) & 255u, rgb & 255u) / 255.0;
}

vec3 bounced(vec3 from) {
    vec3 up = vec3(0.0, 1.0, 0.0);
    if (abs(normal.y) > 0.99) {
        up = vec3(1.0, 0.0, 0.0);
    }
    vec3 t1 = normalize(cross(normal, up));
    vec3 t2 = cross(normal, t1);
    float area = PictureSize.x * PictureSize.y;
    vec3 sum = vec3(0.0);
    for (int i = 0; i < SPOTS; i++) {
        int row;
        vec3 to = spot(i, row);
        // Weighted by the cosine to the face: each ray then carries the same share of what the face gathers.
        float u = (float(i) + 0.5) / float(SPOTS);
        float around = 2.0 * PI * (float(row) + 0.5) / float(SPOTS);
        vec3 way = (t1 * cos(around) + t2 * sin(around)) * sqrt(u) + normal * sqrt(1.0 - u);
        ivec3 cell;
        vec3 face;
        float t = meets(from, from + way * BOUNCE_REACH, cell, face);
        if (t > 1.5) continue;
        vec3 at = from + way * BOUNCE_REACH * t;
        vec3 light = to - at;
        float r2 = dot(light, light);
        float cosAt = dot(face, light);
        float cosScreen = -dot(ScreenOut, light);
        if (cosAt <= 0.0 || cosScreen <= 0.0) continue;
        vec4 lit = off(at, face);
        if (lit.w < 0.5 || blocked(lit.xyz, to)) continue;
        // No more than all of it: one point stands for the whole screen, too brightly right against it.
        sum += colour(cell) * min(area * cosAt * cosScreen / (PI * r2 * r2), 1.0);
    }
    return sum / float(SPOTS);
}

void main() {
    vec4 near = off(point, normal);
    if (Pass == 1) {
        vec3 light = vec3(0.0);
        if (near.w > 0.5) {
            light = bounced(near.xyz);
        }
        fragColor = vec4(sqrt(light), 1.0);
        return;
    }
    int lit = 0;
    for (int i = 0; i < SPOTS; i++) {
        int row;
        vec3 to = spot(i, row);
        if (near.w > 0.5 && dot(normal, to - point) > 0.0 && !blocked(near.xyz, to)) lit |= 1 << i;
    }
    fragColor = vec4(lit & 255, (lit >> 8) & 255, (lit >> 16) & 255, (lit >> 24) & 255) / 255.0;
}
