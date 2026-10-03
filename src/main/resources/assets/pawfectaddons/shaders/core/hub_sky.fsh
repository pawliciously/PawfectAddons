#version 330

layout(std140) uniform HubScene {
    vec4 CameraRight;
    vec4 CameraUp;
    vec4 CameraForward;
    vec4 FogColor;
    vec4 SkyColor;
    vec4 MoonDir;
    vec4 Params;
};

in vec2 texCoord;

out vec4 fragColor;

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

float starLayer(vec3 dir, float scale, float chance, float seed, float time) {
    vec3 p = dir * scale;
    vec3 cell = floor(p);
    float h = hash13(cell + seed);
    if (h < chance) return 0.0;
    vec3 centre = cell + 0.5 + (hash33(cell + seed) - 0.5) * 0.7;
    float d = length(p - centre);
    float size = mix(0.12, 0.27, fract(h * 37.0));
    float twinkle = 0.7 + 0.3 * sin(time * (1.3 + fract(h * 91.0) * 2.7) + h * 600.0);
    return smoothstep(size, 0.0, d) * twinkle;
}

float blob(vec2 uv, vec2 centre, float radius) {
    return smoothstep(radius, 0.0, length(uv - centre));
}

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec3 dir = normalize(CameraForward.xyz + ndc.x * CameraRight.w * CameraRight.xyz + ndc.y * CameraUp.w * CameraUp.xyz);
    float h = dir.y;
    float time = Params.x;

    vec3 colour = mix(FogColor.rgb, SkyColor.rgb, smoothstep(0.0, 0.5, h));
    colour = mix(colour, FogColor.rgb * 0.7, smoothstep(0.0, -0.25, h));

    vec3 moon = normalize(MoonDir.xyz);
    float radius = MoonDir.w;
    float cosine = clamp(dot(dir, moon), -1.0, 1.0);
    float angle = acos(cosine);

    float glow = exp(-angle / (radius * 2.0)) * 0.28 + exp(-angle / (radius * 10.0)) * 0.1;
    colour += vec3(0.55, 0.65, 0.95) * glow;

    float above = smoothstep(-0.02, 0.12, h);
    float stars = 1.3 * starLayer(dir, 150.0, 0.989, 0.0, time) + 0.8 * starLayer(dir, 300.0, 0.993, 17.0, time);
    float disc = smoothstep(radius, radius * 0.9, angle);
    colour += vec3(0.9, 0.93, 1.0) * stars * above * (1.0 - smoothstep(radius * 6.0, radius, angle)) * Params.z;

    vec3 side = normalize(cross(moon, vec3(0.0, 1.0, 0.0)));
    vec3 lift = cross(side, moon);
    vec3 local = dir - moon * cosine;
    vec2 uv = vec2(dot(local, side), dot(local, lift)) / radius;
    float maria = blob(uv, vec2(-0.32, 0.18), 0.55) + 0.8 * blob(uv, vec2(0.05, 0.34), 0.42) + 0.7 * blob(uv, vec2(0.36, -0.05), 0.4) + 0.5 * blob(uv, vec2(-0.12, -0.4), 0.34) + 0.4 * blob(uv, vec2(0.42, 0.42), 0.22);
    float limb = sqrt(max(0.0, 1.0 - dot(uv, uv)));
    vec3 surface = vec3(0.95, 0.96, 1.0) * (1.0 - 0.11 * min(maria, 1.6)) * mix(0.8, 1.0, limb);
    colour = mix(colour, surface, disc);

    fragColor = vec4(colour, 1.0);
}
