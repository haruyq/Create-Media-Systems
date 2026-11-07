#version 150

// A stage light's light on a block face (LightingIllumination), added to the face as it is: the beam's cone from
// Origin along Direction, Tangent wide, Range long (the same cone as LightingIllumination.exposure), falling on the
// face as it is turned to the light. Everything is in the lamp's block space. The vertices' colour is the face's tint,
// its alpha how much of the light the face sees past the blocks between (shaded on the CPU, a block face at a time).
// No ternaries inside expressions: Veil (with Sable) reprints this shader and drops their parentheses.

uniform sampler2D Sampler0;

uniform vec3 Origin;
uniform vec3 Direction;
uniform float Tangent;
uniform float Range;
uniform float Strength;
uniform vec3 LightColor;

in vec2 texCoord0;
in vec4 tint;
in vec3 localPos;
in vec3 localNormal;

out vec4 fragColor;

void main() {
    vec3 offset = localPos - Origin;
    float along = dot(offset, Direction);
    if (along <= 0.0 || along >= Range) {
        discard;
    }
    float radius = max(0.08, along * Tangent);
    float radial = sqrt(max(0.0, dot(offset, offset) - along * along)) / radius;
    float edge = clamp((1.0 - radial) / 0.35, 0.0, 1.0);
    float reach = clamp((Range - along) / max(1.0, Range * 0.2), 0.0, 1.0);
    float exposure = edge * edge * (3.0 - 2.0 * edge) * reach;
    float facing = max(0.0, dot(normalize(localNormal), -normalize(offset)));
    float opacity = clamp(exposure * Strength * facing * 3.0, 0.0, 1.0) * tint.a;
    if (opacity <= 0.0) {
        discard;
    }
    vec4 texel = texture(Sampler0, texCoord0);
    if (texel.a < 0.1) {
        discard;
    }
    fragColor = vec4(texel.rgb * LightColor * tint.rgb, opacity);
}
