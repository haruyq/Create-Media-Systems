#version 150

// A stage light's light on a block face it reaches (LightingIllumination): passes on where the face is in the lamp's
// block space, for spot_light.fsh to light it from there.

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord0;
out vec4 tint;
out vec3 localPos;
out vec3 localNormal;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    tint = Color;
    localPos = Position;
    localNormal = Normal;
}
