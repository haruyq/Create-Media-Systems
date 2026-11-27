#version 150

in vec3 Position;
in vec4 Color;
in vec3 Normal;

uniform vec2 Size;
uniform int VertexOffset;
uniform vec3 ScreenRight;
uniform vec3 ScreenOut;

out vec3 point;
out vec3 normal;

void main() {
    int index = gl_VertexID + VertexOffset;
    vec2 pixel = vec2(index % int(Size.x), index / int(Size.x));
    gl_Position = vec4((pixel + 0.5) / Size * 2.0 - 1.0, 0.0, 1.0);
    gl_PointSize = 1.0;
    // From just inside the face (Color's blue: which way its middle is, a third per axis): on the corner itself,
    // where blocks meet, which of them a ray starts in is a toss-up.
    int inward = int(Color.b * 255.0 + 0.5);
    point = Position + vec3(inward % 3 - 1, inward / 3 % 3 - 1, inward / 9 - 1) * 0.01;
    normal = ScreenRight * Normal.x + vec3(0.0, Normal.y, 0.0) + ScreenOut * Normal.z;
}
