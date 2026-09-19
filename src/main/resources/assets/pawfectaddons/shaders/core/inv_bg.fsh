#version 330

in vec4 vertexColor;
in vec4 borderColor;
in vec4 local;
flat in vec4 shape;

out vec4 fragColor;

const float PI = 3.14159265359;

float hash12(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float paNoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash12(i), hash12(i + vec2(1.0, 0.0)), f.x),
        mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), f.x),
        f.y
    );
}

vec3 aurora(vec2 uv, vec2 px, vec3 base, vec3 accent, float t, float k) {
    float x = uv.x * 2.45 + t * 0.052;
    float warp = paNoise2(vec2(x * 1.65, uv.y * 1.05 - t * 0.085));
    float c1 = 0.5 + 0.5 * sin((x + warp * 0.92) * PI * 2.0);
    float c2 = 0.5 + 0.5 * sin((x * 1.73 - warp * 1.35 + 0.68) * PI * 2.0);
    float curtain = pow(c1, 3.0) * 0.66 + pow(c2, 5.0) * 0.52;
    float fall = smoothstep(1.08, 0.12, uv.y);
    float shimmer = 0.74 + 0.26 * sin(t * 1.65 + uv.x * 13.0 + warp * 5.0);
    vec3 hue = mix(accent, vec3(0.34, 1.00, 0.72), 0.45 + 0.45 * sin(uv.x * 3.05 + t * 0.31));
    vec3 c = base + hue * curtain * fall * shimmer * 0.46 * k;
    c += vec3(1.0) * pow(curtain, 4.0) * fall * 0.11 * k;
    return c;
}

vec3 caustics(vec2 uv, vec2 px, vec3 base, vec3 accent, float t, float k) {
    vec2 p = uv * vec2(3.4, 2.2);
    p += 0.30 * vec2(sin(p.y * 3.1 + t * 0.62), cos(p.x * 2.7 - t * 0.48));
    float a = sin(p.x * 3.6 + t * 0.51) + sin(p.y * 4.2 - t * 0.43);
    float b = sin((p.x + p.y) * 3.0 + t * 0.37);
    float web = abs(a * 0.5 + b * 0.5);
    float light = pow(1.0 - clamp(web, 0.0, 1.0), 3.2);
    vec3 c = base + mix(accent, vec3(0.62, 0.92, 1.00), 0.40) * light * 0.58 * k;
    c += vec3(1.0) * pow(light, 3.0) * 0.24 * k;
    c = mix(c, c * 0.88, smoothstep(0.0, 1.0, uv.y) * k);
    return c;
}

vec3 silk(vec2 uv, vec2 px, vec3 base, vec3 accent, float t, float k) {
    float a = sin((uv.x * 5.4 + uv.y * 3.0) * PI + t * 0.52);
    float b = sin((uv.y * 7.3 - uv.x * 3.1) * PI - t * 0.36);
    float band = 0.5 + 0.5 * (a * 0.62 + b * 0.38);
    vec3 c = mix(base, base * 1.32 + accent * 0.20, pow(band, 2.3) * k);
    float sweep = smoothstep(0.34, 0.0, abs(fract(uv.x * 0.5 + uv.y * 0.22 - t * 0.055) - 0.5));
    c += accent * sweep * 0.15 * k;
    return c;
}

vec3 carbon(vec2 uv, vec2 px, vec3 base, vec3 accent, float t, float k) {
    vec2 tw = px / 6.0;
    vec2 cell = floor(tw);
    vec2 f = fract(tw);
    float d = mod(cell.x + cell.y, 2.0) < 1.0 ? f.x : f.y;
    float weave = 0.66 + 0.34 * sin(d * PI);
    vec3 c = mix(base, base * weave, k);
    float spec = smoothstep(0.30, 0.0, abs(fract((uv.x + uv.y) * 0.5 - t * 0.045) - 0.5));
    c += accent * spec * 0.17 * k;
    c += vec3(1.0) * pow(weave, 9.0) * 0.055 * k;
    return c;
}

vec3 ember(vec2 uv, vec2 px, vec3 base, vec3 accent, float t, float k) {
    vec3 c = base + accent * pow(uv.y, 3.0) * 0.30 * k;
    vec2 g = vec2(px.x / 11.0, px.y / 11.0 + t * 0.28);
    vec2 cell = floor(g);
    float seed = hash12(cell);
    if (seed > 0.90) {
        vec2 o = vec2(hash12(cell + 5.3), hash12(cell + 2.7));
        float d = length((fract(g) - o) * vec2(1.0, 1.45));
        float spark = smoothstep(0.24, 0.0, d);
        float flick = 0.55 + 0.45 * sin(t * 3.1 + seed * 62.0);
        c += mix(accent, vec3(1.0, 0.80, 0.44), 0.5) * spark * flick * 0.85 * k;
    }
    return c;
}

void main() {
    vec2 extent = shape.xy;
    vec2 uv = (local.xy + extent) / max(extent * 2.0, vec2(1.0));
    vec2 px = local.xy + extent;

    float styleBits = local.z;
    float style = floor(styleBits / 2.0);
    float intensity = styleBits - style * 2.0;
    float time = local.w;

    vec3 top = vertexColor.rgb;
    vec3 bottom = borderColor.rgb;
    vec3 accent = vec3(shape.z, shape.w, borderColor.a);
    vec3 base = mix(top, bottom, smoothstep(0.0, 1.0, uv.y));

    int id = int(style + 0.5);
    vec3 colour;
    if (id == 1) {
        colour = caustics(uv, px, base, accent, time, intensity);
    } else if (id == 2) {
        colour = silk(uv, px, base, accent, time, intensity);
    } else if (id == 3) {
        colour = carbon(uv, px, base, accent, time, intensity);
    } else if (id == 4) {
        colour = ember(uv, px, base, accent, time, intensity);
    } else {
        colour = aurora(uv, px, base, accent, time, intensity);
    }

    vec2 far = min(px, extent * 2.0 - px);
    float inset = min(far.x, far.y);
    colour = mix(base, colour, smoothstep(0.0, 4.0, inset));

    colour *= mix(0.74, 1.0, smoothstep(0.0, 1.5, inset));
    colour += accent * smoothstep(0.9, 0.0, inset) * 0.30 * intensity;
    colour += (hash12(px + time) - 0.5) * 0.012;

    fragColor = vec4(max(colour, vec3(0.0)), vertexColor.a);
}
