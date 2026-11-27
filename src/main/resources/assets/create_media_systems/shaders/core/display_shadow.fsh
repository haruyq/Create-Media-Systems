#version 150

// For a vertex of a face a display lights (DisplayShadows): which of SPOTS points spread over the screen it sees past
// the blocks about the display (Voxels, eight to a texel). display_light.vsh reads a face's four, and
// display_light.fsh traces again, per pixel, only to the points they don't agree on.

uniform usampler3D Voxels;
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

bool blocked(vec3 from, vec3 to) {
    vec3 d = to - from;
    ivec3 cell = ivec3(floor(from));
    ivec3 end = ivec3(floor(to));
    ivec3 step = ivec3(sign(d));
    vec3 next = vec3(boundary(from.x, d.x, cell.x), boundary(from.y, d.y, cell.y), boundary(from.z, d.z, cell.z));
    vec3 delta = vec3(1e20);
    if (d.x != 0.0) delta.x = abs(1.0 / d.x);
    if (d.y != 0.0) delta.y = abs(1.0 / d.y);
    if (d.z != 0.0) delta.z = abs(1.0 / d.z);
    ivec3 size = textureSize(Voxels, 0) * 2;
    for (int guard = 0; guard < 1024; guard++) {
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
        if (min(next.x, min(next.y, next.z)) > 1.0 || all(equal(cell, end))) return false;
        ivec3 at = cell - ivec3(VoxelMin);
        if (any(lessThan(at, ivec3(0))) || any(greaterThanEqual(at, size))) continue;
        uint mask = texelFetch(Voxels, at / 2, 0).r;
        int bit = (at.x & 1) | ((at.y & 1) << 1) | ((at.z & 1) << 2);
        if ((mask & (1u << uint(bit))) != 0u) return true;
    }
    return false;
}

void main() {
    // A bit per point. lit: in front of the face, and seen from it. open: seen from ROOM_STEP out of it.
    int lit = 0;
    int open = 0;
    for (int i = 0; i < SPOTS; i++) {
        // Each point on a column and a row of its own.
        int row = ((i & 1) << 3) | ((i & 2) << 1) | ((i & 4) >> 1) | ((i & 8) >> 3);
        vec2 at = (vec2(i, row) + 0.5) / float(SPOTS) * PictureSize;
        vec3 spot = ScreenOrigin + ScreenRight * at.x + vec3(0.0, at.y, 0.0) + ScreenOut * 0.05;
        if (dot(normal, spot - point) > 0.0 && !blocked(point + normal * 0.02, spot)) lit |= 1 << i;
        if (!blocked(point + normal * ROOM_STEP, spot)) open |= 1 << i;
    }
    fragColor = vec4(lit & 255, lit >> 8, open & 255, open >> 8) / 255.0;
}
