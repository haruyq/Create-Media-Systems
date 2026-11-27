#version 150

// A display's light on a face in front of it (DisplayLight): passes on where the face is in the screen's own space,
// x across the picture, y up, z out of it, for display_light.fsh to light it from there; and, for its shadows, which
// of the screen's points the face's four corners see (Sampler2, a texel per vertex, display_shadow.fsh's bits).

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 ToScreen;
uniform sampler2D Sampler2;
uniform int VisibilityOffset;

out vec2 texCoord0;
out vec4 material;
out vec3 localPos;
out vec3 localNormal;
// A bit per point of the screen, x for its light and y for its room: those all four corners see, and those only some
// do. The same from each of the face's vertices.
flat out ivec2 sure;
flat out ivec2 unsure;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    material = Color;
    // A face's four vertices follow one another.
    int first = gl_VertexID - gl_VertexID % 4 + VisibilityOffset;
    int width = textureSize(Sampler2, 0).x;
    ivec4 every = ivec4(255);
    ivec4 some = ivec4(0);
    for (int k = 0; k < 4; k++) {
        int index = first + k;
        ivec4 bits = ivec4(texelFetch(Sampler2, ivec2(index % width, index / width), 0) * 255.0 + 0.5);
        every &= bits;
        some |= bits;
    }
    sure = every.rb | (every.ga << 8);
    unsure = (some.rb | (some.ga << 8)) ^ sure;
    localPos = (ToScreen * vec4(Position, 1.0)).xyz;
    localNormal = Normal;
}
