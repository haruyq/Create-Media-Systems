#version 150

// A display's light on a face in front of it (DisplayLight): passes on where the face is in the screen's own space,
// x across the picture, y up, z out of it, for display_light.fsh to light it from there.

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 ToScreen;

out vec2 texCoord0;
out vec4 material;
out vec3 localPos;
out vec3 localNormal;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    material = Color;
    localPos = (ToScreen * vec4(Position, 1.0)).xyz;
    localNormal = Normal;
}
