#version 150

// A stage light's light on a block face it reaches (LightingIllumination): passes on where the face is in the lamp's
// block space, for spot_light.fsh to light it from there, and in the colour's alpha how much of the light gets there.

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform sampler2D Sampler1;
uniform int Masked;

out vec2 texCoord0;
out vec4 tint;
out vec3 localPos;
out vec3 localNormal;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    float light = 1.0;
    if (Masked != 0) {
        // A texel a cell: how much of the light each of its four corners gets, red first (cellCorners), faded
        // across the cell between them. A cell none of it reaches isn't drawn.
        int cell = gl_VertexID / 4;
        int width = textureSize(Sampler1, 0).x;
        vec4 corners = texelFetch(Sampler1, ivec2(cell % width, cell / width), 0);
        if (max(max(corners.r, corners.g), max(corners.b, corners.a)) <= 0.0) {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        }
        light = corners[gl_VertexID % 4];
    }
    texCoord0 = UV0;
    tint = vec4(Color.rgb, Color.a * light);
    localPos = Position;
    localNormal = Normal;
}
