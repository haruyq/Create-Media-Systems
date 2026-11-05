#version 150

// A display's light on the wall around it, like YouTube's ambient mode: its video, blurred, fading out past the
// display's edges. UV 0..1 is the display; the quad reaches REACH further on each side (DisplayRenderer.GLOW_REACH).

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

const float REACH = 0.3;

void main() {
    // The video's mipmap about 16 texels across is its blur; clamped at the edges, the edge colours spread outwards.
    vec2 size = vec2(textureSize(Sampler0, 0));
    float lod = max(log2(max(size.x, size.y) / 16.0), 0.0);
    vec3 color = textureLod(Sampler0, texCoord0, lod).rgb;
    vec2 outside = max(max(-texCoord0, texCoord0 - 1.0), 0.0) / REACH;
    float fade = 1.0 - smoothstep(0.0, 1.0, length(outside));
    fragColor = vec4(color, vertexColor.a * fade * fade);
}
