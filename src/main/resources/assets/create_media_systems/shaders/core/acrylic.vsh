#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec4 light;
out vec2 levels;
out vec2 texCoord0;
out vec3 toEye;
out vec3 normal;
out float thickness;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(Position, FogShape);
    light = texelFetch(Sampler2, UV2 / 16, 0);
    // The block light and the sky light here, each 0 to 1.
    levels = vec2(UV2) / 240.0;
    // What the colour's alpha lacks is how thick the sheet behind the face is, all of it a quarter of a block.
    thickness = (1.0 - Color.a) * 0.25;
    texCoord0 = UV0;
    // In the world a vertex is where it is from the camera, along the world's axes; so is its normal.
    toEye = -Position;
    normal = Normal;
}
