#version 150

// A display's light on a block face in front of it (DisplayLight): the face's own texture times the video's colour
// nearest it, blurred, added to the face as it is. The vertex colour carries that colour's place on the video
// (red, green) and how bright the light is there (alpha).

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 albedo = texture(Sampler0, texCoord0);
    if (albedo.a < 0.1) {
        discard;
    }
    // The video's mipmap about 16 texels across: the colours of the part of the picture nearest, as they are.
    vec2 size = vec2(textureSize(Sampler1, 0));
    float lod = max(log2(max(size.x, size.y) / 16.0), 0.0);
    vec3 light = textureLod(Sampler1, vertexColor.rg, lod).rgb;
    fragColor = vec4(albedo.rgb * light, vertexColor.a);
}
