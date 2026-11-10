#version 150
uniform sampler2D Scene;
uniform sampler2D Depth;
uniform mat4 InverseProjection;
uniform mat4 CellProjection;
uniform vec2 Viewport;
uniform int LightCount;
uniform vec4 Source0, Source1, Source2, Source3, Source4, Source5, Source6, Source7;
uniform vec4 Tint0, Tint1, Tint2, Tint3, Tint4, Tint5, Tint6, Tint7;
out vec4 fragColor;

vec3 positionAt(vec2 uv, float depth) {
    vec4 p = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

// Depth-tested screen-space shadows prevent the glow from washing straight through visible walls.
float visibility(vec3 p, vec3 light, vec3 normal) {
    for (int step = 1; step <= 7; step++) {
        vec3 samplePoint = mix(p + normal * .035, light, float(step) / 8.0);
        vec4 clip = CellProjection * vec4(samplePoint, 1.0);
        if (clip.w <= 0.0) continue;
        vec2 uv = clip.xy / clip.w * .5 + .5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) continue;
        float depth = texture(Depth, uv).r;
        if (depth < .999999 && positionAt(uv, depth).z > samplePoint.z + .09) return .08;
    }
    return 1.0;
}

void main() {
    vec2 uv = gl_FragCoord.xy / Viewport;
    vec4 scene = texture(Scene, uv);
    float depth = texture(Depth, uv).r;
    fragColor = scene;
    if (depth >= .999999) return;
    vec3 p = positionAt(uv, depth);
    vec3 n = normalize(cross(dFdx(p), dFdy(p)));
    if (dot(n, -p) < 0.0) n = -n;
    vec4 sources[8] = vec4[8](Source0, Source1, Source2, Source3, Source4, Source5, Source6, Source7);
    vec4 colors[8] = vec4[8](Tint0, Tint1, Tint2, Tint3, Tint4, Tint5, Tint6, Tint7);
    vec3 illumination = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        if (i >= LightCount) break;
        vec3 delta = sources[i].xyz - p;
        float distance = length(delta), radius = sources[i].w;
        if (distance >= radius || radius <= 0.0) continue;
        float edge = 1.0 - distance / radius;
        float facing = .12 + .88 * max(0.0, dot(n, delta / max(distance, .001)));
        illumination += colors[i].rgb * colors[i].a * edge * edge * facing
                * visibility(p, sources[i].xyz, n) * 2.1;
    }
    // Keep the scene's texture/detail instead of drawing a flat translucent colored disk over it.
    fragColor = vec4(scene.rgb + illumination * (.10 + scene.rgb * .65), scene.a);
}
