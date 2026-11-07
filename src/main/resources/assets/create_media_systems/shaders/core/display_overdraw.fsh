#version 150

// A display's picture drawn again over a shader pack's frame (DisplayRenderer.overdraw), with the light that passed
// in front of it added back (ScreenLight, Sampler1: that light alone, one texel per pixel of the frame).
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec4 ColorModulator;

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * ColorModulator;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = vec4(color.rgb + texelFetch(Sampler1, ivec2(gl_FragCoord.xy), 0).rgb, color.a);
}
