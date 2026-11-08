#version 150

uniform usampler3D Voxels;
uniform vec3 VoxelMin;
uniform vec3 Targets[24];
uniform int RayCount;

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
    int rays = 0;
    int clear = 0;
    for (int i = 0; i < RayCount; i++) {
        if (dot(normal, Targets[i] - point) <= 0.0) continue;
        rays += 1;
        if (!blocked(point + normal * 0.02, Targets[i])) clear += 1;
    }
    float seen = 1.0;
    if (rays != 0) seen = floor(float(clear) / float(rays) * 255.0) / 255.0;
    fragColor = vec4(seen, seen, seen, 1.0);
}
