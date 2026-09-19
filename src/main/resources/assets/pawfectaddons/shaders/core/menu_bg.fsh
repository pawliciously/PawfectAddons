#version 330

in vec4 vertexColor;
in vec4 borderColor;
in vec4 local;
flat in vec4 shape;

out vec4 fragColor;

float hash12(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float paNoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm2(vec2 p) {
    float v = 0.0;
    float a = 0.52;
    for (int i = 0; i < 4; i++) {
        v += paNoise2(p) * a;
        p = p * 2.07 + vec2(5.2, 1.3);
        a *= 0.5;
    }
    return v;
}

float waves(vec2 p, float freq, float amp, float t) {
    float ripple = sin(p.x * 2.10 + t) * 0.55
        + sin(p.x * 3.70 - t * 0.72 + 1.9) * 0.28
        + sin(p.x * 6.30 + t * 0.44 + 4.2) * 0.13;
    return sin((p.y + ripple * amp) * freq);
}

void main() {
    vec2 extent = shape.xy;
    vec2 uv = (local.xy + extent) / max(extent * 2.0, vec2(1.0));
    float time = local.w;
    float intensity = local.z;

    float aspect = extent.x / max(extent.y, 1.0);
    vec2 field = vec2(uv.x * aspect, uv.y);

    vec3 top = vertexColor.rgb;
    vec3 bottom = borderColor.rgb;
    vec3 accent = vec3(shape.z, shape.w, borderColor.a);

    vec3 pale = mix(top, accent, 0.80);
    vec3 deep = mix(bottom, accent * 0.34, 0.62);
    vec3 base = mix(pale, deep, smoothstep(-0.15, 1.10, uv.y));

    float t = time * 0.22;
    vec2 tilt = vec2(field.x + field.y * 0.38, field.y - field.x * 0.16);

    float band = waves(tilt * 4.6, 3.10, 0.30, t);
    float ridge = smoothstep(-0.30, 0.65, band);
    base = mix(base, mix(base, pale, 0.42), ridge * 0.55 * intensity);

    float band2 = waves(tilt * 7.4 + vec2(3.1, 1.7), 2.40, 0.22, -t * 0.78);
    float ridge2 = smoothstep(0.10, 0.90, band2);
    base = mix(base, base * 0.82, ridge2 * 0.40 * intensity);

    float crest = pow(clamp(1.0 - abs(band) * 1.35, 0.0, 1.0), 5.0);
    base += accent * crest * 0.10 * intensity;

    float bloom = fbm2(field * 1.4 + vec2(0.0, time * 0.03));
    base = mix(base, mix(base, accent, 0.30), smoothstep(0.44, 0.98, bloom) * 0.24 * intensity);

    float halo = smoothstep(0.62, 0.0, length((uv - vec2(0.5, 0.16)) * vec2(aspect, 1.0)));
    base += accent * halo * 0.16 * intensity;

    float vignette = smoothstep(1.28, 0.34, length((uv - 0.5) * vec2(aspect, 1.0)) * 1.5);
    base *= mix(0.52, 1.0, vignette);

    float grain = (hash12(local.xy + time) - 0.5) * 0.012;
    base += grain;

    fragColor = vec4(max(base, vec3(0.0)), 1.0);
}
