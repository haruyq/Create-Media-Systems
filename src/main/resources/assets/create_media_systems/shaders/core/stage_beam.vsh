#version 150

// A beam strip a row of Sampler0 (LightingBeams.strip), expanded into its faded steps. The row layout and the fades
// are LightingGpu's; LightingGpuTest checks this file against it.

uniform samplerBuffer Sampler0;

out vec3 Point;
flat out uint PackedColor;

struct Vertex {
    vec3 point;
    vec4 color;
};

vec4 data(int column, int row) {
    return texelFetch(Sampler0, row * 16 + column);
}

int clip(inout Vertex polygon[7], int count, vec4 plane) {
    if (count == 0) return 0;
    int outside = 0;
    for (int i = 0; i < count; i++) {
        if (dot(plane, vec4(polygon[i].point, 1.0)) < 0.0) outside += 1;
    }
    if (outside == 0) return count;
    if (outside == count) return 0;
    Vertex result[7];
    int size = 0;
    Vertex previous = polygon[count - 1];
    float before = dot(plane, vec4(previous.point, 1.0));
    for (int i = 0; i < count; i++) {
        Vertex current = polygon[i];
        float after = dot(plane, vec4(current.point, 1.0));
        if ((before >= 0.0) != (after >= 0.0)) {
            float t = before / (before - after);
            result[size++] = Vertex(mix(previous.point, current.point, t), mix(previous.color, current.color, t));
        }
        if (after >= 0.0) result[size++] = current;
        previous = current;
        before = after;
    }
    for (int i = 0; i < size; i++) polygon[i] = result[i];
    return size;
}

bool expand() {
    gl_Position = vec4(0.0);
    Point = vec3(0.0);
    PackedColor = 0u;
    int row = gl_VertexID / 120;
    int step = (gl_VertexID % 120) / 15;
    int triangle = (gl_VertexID % 15) / 3;
    int vertex = gl_VertexID % 3;
    vec2 settings = data(9, row).xy;
    int mode = int(settings.x);
    if (mode == 2 && step != 0) return false;
    float t0 = float(step) / 8.0;
    float t1 = float(step + 1) / 8.0;
    if (mode == 2) {
        t0 = 0.0;
        t1 = 1.0;
    }
    float alpha0 = settings.y;
    float alpha1 = alpha0;
    if (mode == 0) {
        alpha0 = floor(alpha0 * (1.0 - t0 * 0.75));
        alpha1 = floor(alpha1 * (1.0 - t1 * 0.75));
    } else if (mode == 1) {
        alpha0 = floor(alpha0 * (1.0 - float(step) / 11.0) + 0.5);
        alpha1 = max(1.0, alpha0 - 3.0);
    }
    vec3 a = data(0, row).xyz;
    vec3 b = data(1, row).xyz;
    vec3 c = data(2, row).xyz;
    vec3 d = data(3, row).xyz;
    vec3 colorA = data(7, row).rgb * 255.0;
    vec3 colorB = data(8, row).rgb * 255.0;
    Vertex polygon[7];
    polygon[0] = Vertex(mix(a, d, t0), vec4(colorA, alpha0));
    polygon[1] = Vertex(mix(b, c, t0), vec4(colorB, alpha0));
    polygon[2] = Vertex(mix(b, c, t1), vec4(colorB, alpha1));
    polygon[3] = Vertex(mix(a, d, t1), vec4(colorA, alpha1));
    int count = clip(polygon, 4, data(4, row));
    count = clip(polygon, count, data(5, row));
    count = clip(polygon, count, data(6, row));
    if (triangle >= count - 2) return false;
    int index = 0;
    if (vertex != 0) index = triangle + vertex;
    mat4 model = mat4(data(10, row), data(11, row), data(12, row), data(13, row));
    Point = (model * vec4(polygon[index].point, 1.0)).xyz;
    uvec4 color = uvec4(clamp(floor(polygon[index].color + 0.5), 0.0, 255.0));
    PackedColor = color.r | (color.g << 8u) | (color.b << 16u) | (color.a << 24u);
    return true;
}

void main() {
    bool emitted = expand();
}
