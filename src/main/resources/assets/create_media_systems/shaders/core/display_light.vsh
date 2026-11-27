#version 150

// A display's light on a face in front of it (DisplayLight): passes on where the face is in the screen's own space,
// x across the picture, y up, z out of it, for display_light.fsh to light it from there; and, for its shadows, which
// of the screen's points the face's four corners see, and the light that reaches the vertex by way of other faces
// (Sampler2, display_shadow.fsh's: a texel per vertex of each, the second in its upper half).

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
// A bit per point of the screen: those all four corners see, and those only some do. The same from each of the
// face's vertices.
flat out int sure;
flat out int unsure;
out vec3 bounced;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    material = Color;
    // A face's four vertices follow one another.
    int first = gl_VertexID - gl_VertexID % 4 + VisibilityOffset;
    ivec2 size = textureSize(Sampler2, 0);
    int every = -1;
    int some = 0;
    for (int k = 0; k < 4; k++) {
        int index = first + k;
        ivec4 bits = ivec4(texelFetch(Sampler2, ivec2(index % size.x, index / size.x), 0) * 255.0 + 0.5);
        int seen = bits.r | (bits.g << 8) | (bits.b << 16) | (bits.a << 24);
        every &= seen;
        some |= seen;
    }
    sure = every;
    unsure = some ^ every;
    int own = gl_VertexID + VisibilityOffset;
    vec3 root = texelFetch(Sampler2, ivec2(own % size.x, own / size.x + size.y / 2), 0).rgb;
    bounced = root * root;
    localPos = (ToScreen * vec4(Position, 1.0)).xyz;
    localNormal = Normal;
}
