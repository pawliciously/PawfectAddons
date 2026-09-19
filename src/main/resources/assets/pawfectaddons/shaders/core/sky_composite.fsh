#version 330

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D SkySampler;

layout(std140) uniform SkyData {
    vec4 camRight;
    vec4 camUp;
    vec4 camFwd;
    vec4 sunDir;
    vec4 tint;
    vec4 cfg;
    vec4 march;
    vec4 extra;
    vec4 tint2;
    vec4 tint3;
};

const float PI = 3.14159265359;
const float TAU = 6.28318530718;
const float NUDGE = 0.739513;
const float SPIRAL_NORM = 0.8040288;

float spiral3(vec3 p, int iters) {
    float n = 0.0;
    float iter = 1.0;
    for (int i = 0; i < 6; i++) {
        if (i >= iters) break;
        n += (sin(p.y * iter) + cos(p.x * iter)) / iter;
        p.xz += vec2(p.z, -p.x) * NUDGE;
        p.xz *= SPIRAL_NORM;
        iter *= 1.33733;
    }
    return n;
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

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

float paNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(hash13(i), hash13(i + vec3(1.0, 0.0, 0.0)), f.x);
    float b = mix(hash13(i + vec3(0.0, 1.0, 0.0)), hash13(i + vec3(1.0, 1.0, 0.0)), f.x);
    float c = mix(hash13(i + vec3(0.0, 0.0, 1.0)), hash13(i + vec3(1.0, 0.0, 1.0)), f.x);
    float d = mix(hash13(i + vec3(0.0, 1.0, 1.0)), hash13(i + vec3(1.0, 1.0, 1.0)), f.x);
    return mix(mix(a, b, f.y), mix(c, d, f.y), f.z);
}

float fbm3(vec3 p, int oct) {
    float v = 0.0;
    float a = 0.55;
    for (int i = 0; i < 5; i++) {
        if (i >= oct) break;
        v += a * paNoise3(p);
        p = p * 2.07 + vec3(17.3, 9.1, 31.7);
        a *= 0.52;
    }
    return v;
}

float ridge3(vec3 p, int oct) {
    float sum = 0.0;
    float amp = 0.62;
    float freq = 1.0;
    float prev = 1.0;
    for (int i = 0; i < 6; i++) {
        if (i >= oct) break;
        float n = 1.0 - abs(paNoise3(p * freq) * 2.0 - 1.0);
        n *= n;
        sum += n * amp * prev;
        prev = n;
        freq *= 2.07;
        amp *= 0.52;
    }
    return sum;
}

vec3 rotAxis(vec3 p, vec3 axis, float a) {
    float c = cos(a);
    float s = sin(a);
    return p * c + cross(axis, p) * s + axis * dot(axis, p) * (1.0 - c);
}

vec3 unpackHdr(vec3 v) {
    vec3 p = v * v;
    return p / max(1.0 - p, vec3(1e-3));
}

vec2 dirToLutUv(vec3 d, float lutH) {
    vec2 hz = vec2(d.x, d.z);
    if (dot(hz, hz) < 1e-12) hz = vec2(1.0, 0.0);
    float u = atan(hz.y, hz.x) / TAU + 0.5;
    float t = sign(d.y) * sqrt(abs(d.y));
    float v = t * 0.5 + 0.5;
    return vec2(u, clamp(v, 0.5 / lutH, 1.0 - 0.5 / lutH));
}

vec3 tonemap(vec3 c) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 tc = c / (1.0 + c);
    return mix(c / (1.0 + l), tc, tc);
}

vec3 vivid(vec3 c, float s) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return max(vec3(0.0), mix(vec3(l), c, s));
}

vec3 starTint(float h, float mag) {
    vec3 cold = vec3(1.00, 0.62, 0.42);
    vec3 mid = vec3(1.00, 0.95, 0.88);
    vec3 hot = vec3(0.66, 0.79, 1.00);
    float t = h * 0.55 + mag * 0.45;
    return t < 0.5 ? mix(cold, mid, t * 2.0) : mix(mid, hot, (t - 0.5) * 2.0);
}

vec3 starField3(vec3 dir, float grain, float chance, float gain) {
    vec3 g = dir * grain;
    vec3 fl = floor(g);
    vec3 nb = step(vec3(0.5), g - fl) * 2.0 - 1.0;
    float fp = max(length(fwidth(dir)) * grain, 1e-4);

    vec3 light = vec3(0.0);

    for (int i = 0; i < 8; i++) {
        vec3 sel = vec3(float(i & 1), float((i >> 1) & 1), float((i >> 2) & 1));
        vec3 cell = fl + sel * nb;
        float presence = hash13(cell);
        if (presence > chance) continue;

        float mag = hash13(cell + 41.3);
        float lum = pow(mag, 5.0);

        vec3 jitter = hash33(cell + 3.7);
        vec3 delta = g - (cell + 0.20 + jitter * 0.60);
        float d2 = dot(delta, delta);
        if (d2 > 1.2) continue;

        float core = 0.050 * (0.60 + lum * 2.4);
        float radius = max(core, fp);
        float energy = min(1.0, (core / radius) * (core / radius));

        vec3 hue = starTint(hash13(cell + 17.3), mag);
        float bright = gain * (0.12 + lum * 6.2);

        float profile = exp(-d2 / (radius * radius));
        float halo = exp(-d2 * 24.0) * 0.15;
        light += hue * (profile + halo) * bright * energy;

        if (lum > 0.30) {
            float spikeGain = (lum - 0.30) * 1.5;
            float sx = exp(-abs(delta.x) * 16.0) * exp(-dot(delta.yz, delta.yz) * 1200.0);
            float sy = exp(-abs(delta.y) * 16.0) * exp(-dot(delta.xz, delta.xz) * 1200.0);
            light += hue * (sx + sy) * bright * spikeGain * 0.40 * energy;
        }
    }
    return light;
}

vec3 nebulaDetail(vec3 dir, vec3 axis, vec3 lut, float time, float seed, float k, vec3 warm, vec3 hot) {
    float ang = acos(clamp(dot(dir, axis), -1.0, 1.0));
    float envelope = smoothstep(2.05, 0.30, ang);
    if (envelope < 0.01) return lut;

    float h = dot(dir, axis);
    vec3 rad = dir - axis * h;
    float rl = length(rad);
    float twist = (3.30 + time * 0.16) / (0.34 + rl * 1.30) + h * 0.55 + time * 0.055;
    vec3 q = (axis * h * 0.70 + rotAxis(rad, axis, twist)) * (6.6 * k) + seed * 2.3;

    float fine = ridge3(q, 4);
    float lane = smoothstep(0.66, 0.20, paNoise3(q * 0.33 + vec3(21.0, time * 0.09, -time * 0.06)));

    vec3 colour = lut;
    colour *= 1.0 + smoothstep(0.52, 1.30, fine) * 1.55 * envelope;
    colour *= mix(1.0, 0.48, lane * envelope);
    colour += mix(warm, hot, 0.65) * pow(smoothstep(0.90, 1.55, fine), 2.0) * 0.34 * envelope;
    return colour;
}

vec3 skyStars(vec3 dir, vec3 warm, vec3 cool, vec3 hot, float amount, float time, float k, int layers) {
    float t = time * 0.05;
    float density = mix(0.55, 1.30, clamp((amount - 0.10) / 1.40, 0.0, 1.0));

    vec3 body = vec3(0.003, 0.004, 0.011);
    body += mix(cool * 0.030, cool * 0.085, clamp(dir.y * 0.5 + 0.5, 0.0, 1.0));

    float dust = pow(clamp(fbm3(dir * (2.4 * k) + vec3(t * 0.05, 0.0, -t * 0.04), 4), 0.0, 1.0), 2.2);
    body += mix(cool, warm, 0.55) * dust * mix(0.10, 0.34, density);

    float twinkle = 0.84 + 0.16 * sin(time * 1.7 + dot(dir, vec3(37.0, 61.0, 29.0)) * 9.0);

    vec3 field = starField3(dir * k, 38.0, 0.150, 1.00);
    field += starField3(dir * k + 11.7, 74.0, 0.105, 0.52);
    if (layers > 1) field += starField3(dir * k + 27.3, 126.0, 0.070, 0.26);

    body += mix(hot, vec3(0.96, 0.97, 1.0), 0.55) * field * density * twinkle;
    return max(body, vec3(0.0));
}

vec3 skyCaustics(vec3 dir, vec3 warm, vec3 cool, vec3 hot, float amount, float time, float k, int layers) {
    float t = time * 0.9;
    float gain = mix(0.55, 1.40, clamp((amount - 0.10) / 1.40, 0.0, 1.0));

    vec3 p = dir * (3.4 * k);
    p += 0.30 * vec3(
        sin(p.y * 3.1 + t * 0.62),
        cos(p.z * 2.7 - t * 0.48),
        sin(p.x * 2.3 + t * 0.55)
    );

    float a = sin(p.x * 3.6 + t * 0.51) + sin(p.y * 4.2 - t * 0.43) + sin(p.z * 3.9 + t * 0.33);
    float b = sin((p.x + p.y + p.z) * 3.0 + t * 0.37);
    float web = abs(a * 0.34 + b * 0.50);
    float light = pow(1.0 - clamp(web, 0.0, 1.0), 3.2);

    float depth = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 body = mix(cool * 0.10, cool * 0.36, depth);

    body += mix(warm, vec3(0.72, 0.95, 1.00), 0.32) * light * 0.92 * gain;
    body += mix(hot, vec3(1.0), 0.50) * pow(light, 3.0) * 0.48 * gain;

    if (layers > 1) {
        vec3 q = p * 2.35 + vec3(4.1, -2.7, 6.3);
        float a2 = sin(q.x * 3.1 - t * 0.71) + sin(q.z * 3.7 + t * 0.58) + sin(q.y * 2.9 - t * 0.41);
        float fine = pow(1.0 - clamp(abs(a2 * 0.36), 0.0, 1.0), 4.5);
        body += mix(hot, vec3(1.0), 0.40) * fine * 0.32 * gain;
    }

    float shade = fbm3(p * 0.55 + vec3(0.0, t * 0.05, 0.0), 3);
    body *= mix(0.70, 1.25, clamp(shade, 0.0, 1.0));
    body += starField3(dir * k, 46.0, 0.070, 0.55);

    return max(body, vec3(0.0));
}

vec3 skyAurora(vec3 dir, vec3 warm, vec3 cool, vec3 hot, float amount, float time, float k, int layers) {
    float t = time * 0.6;
    float gain = mix(0.60, 1.55, clamp((amount - 0.10) / 1.40, 0.0, 1.0));

    float el = asin(clamp(dir.y, -1.0, 1.0));
    float az = atan(dir.z, dir.x);

    float n1 = max(1.0, floor(3.0 * k + 0.5));
    float n2 = max(1.0, floor(7.0 * k + 0.5));
    float n3 = max(1.0, floor(13.0 * k + 0.5));
    float n4 = max(1.0, floor(41.0 * k + 0.5));

    float wave = sin(az * n1 + t * 0.21) * 0.55
        + sin(az * n2 - t * 0.13 + 1.7) * 0.30
        + sin(az * n3 + t * 0.08 + 4.1) * 0.15;
    float curtain = pow(clamp(0.5 + 0.5 * wave, 0.0, 1.0), 6.0);

    float wave2 = sin(az * n2 + t * 0.17 + 2.3) * 0.62 + sin(az * n3 - t * 0.11 + 0.9) * 0.38;
    float curtain2 = pow(clamp(0.5 + 0.5 * wave2, 0.0, 1.0), 9.0);

    float rise = smoothstep(-0.06, 0.30, el) * smoothstep(1.30, 0.46, el);
    float ray = 0.62 + 0.38 * sin(az * n4 + t * 0.9 + el * 4.2);
    float pulse = 0.78 + 0.22 * sin(t * 1.5 + az * n1 * 1.7);

    float band = (curtain * 0.88 + curtain2 * 0.52) * rise * ray * pulse;

    vec3 sky = vec3(0.004, 0.006, 0.016) + cool * 0.045 * clamp(el * 1.2 + 0.35, 0.0, 1.0);
    vec3 glow = mix(warm, cool, clamp(smoothstep(0.14, 0.80, el), 0.0, 1.0));

    vec3 body = sky;
    float veil = clamp(band * 1.9, 0.0, 1.0);
    body += starField3(dir * k, 44.0, 0.105, 0.90) * (1.0 - veil);
    if (layers > 1) body += starField3(dir * k + 19.3, 88.0, 0.065, 0.36) * (1.0 - veil);

    body += glow * band * 1.05 * gain;
    body += mix(hot, vec3(1.0), 0.45) * pow(band, 3.0) * 0.44 * gain;
    body += warm * exp(-abs(el) * 7.0) * 0.055 * gain;

    return max(body, vec3(0.0));
}

vec3 galaxyBody(vec3 d, vec3 nrm, vec3 warm, vec3 cool, vec3 hot, float t, float density, float k, int layers) {
    float plane = dot(d, nrm);
    float band = exp(-plane * plane * 14.0);

    vec3 p = d * (2.6 * k);
    float dust = fbm3(p + vec3(t * 0.030, 0.0, -t * 0.020), 4);
    float arm = fbm3(p * 0.55 + vec3(-t * 0.015, t * 0.010, 0.0), 3);
    float dark = clamp(fbm3(p * 1.9 + 5.0, 3), 0.0, 1.0);

    vec3 milky = mix(cool, warm, 0.42) * band * (0.20 + dust * 0.95);
    milky += warm * band * pow(max(arm, 0.0), 2.1) * 0.70;
    milky += mix(cool, vec3(0.32, 0.58, 1.00), 0.42) * band * dust * 0.26;
    milky *= mix(0.45, 1.25, dark);

    vec3 body = vec3(0.006, 0.007, 0.018) + milky * density;

    float cluster = pow(max(fbm3(p * 2.3 + 8.0, 3), 0.0), 2.8);
    vec3 field = starField3(d * k, 40.0, 0.085 + band * 0.095 + cluster * 0.070, 1.00);
    if (layers > 1) field += starField3(d * k + 13.1, 82.0, 0.055 + band * 0.070, 0.45);

    body += mix(hot, vec3(0.96, 0.97, 1.0), 0.50) * field * density;
    return body;
}

vec3 skyGalaxy(vec3 dir, vec3 anchor, vec3 warm, vec3 cool, vec3 hot, float amount, float time, float k, int layers) {
    float t = time * 0.35;
    float density = mix(0.70, 1.40, clamp((amount - 0.10) / 1.40, 0.0, 1.0));

    vec3 hole = normalize(anchor);
    vec3 upAxis = abs(hole.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 du = normalize(cross(upAxis, hole));
    vec3 dv = normalize(cross(hole, du));
    vec3 nrm = normalize(du * 0.32 + dv * 0.95);
    vec3 ga = normalize(cross(nrm, hole));
    vec3 gb = normalize(cross(nrm, ga));

    float rs = 0.055;
    float ang = acos(clamp(dot(dir, hole), -1.0, 1.0));

    vec3 tangent = dir - hole * dot(dir, hole);
    float tl = length(tangent);
    vec3 tdir = tl > 1e-5 ? tangent / tl : ga;

    float defl = min((rs * 1.45) / max(ang, 1e-3), 2.4) * smoothstep(2.10, 0.55, ang);
    float bent = ang + defl;
    vec3 lensed = normalize(hole * cos(bent) + tdir * sin(bent));

    vec3 body = galaxyBody(lensed, nrm, warm, cool, hot, t, density, k, layers);

    float rr = acos(clamp(dot(lensed, hole), -1.0, 1.0));
    float dplane = dot(lensed, nrm);
    float thick = 0.016 + rr * 0.16;
    float sheet = exp(-(dplane * dplane) / (thick * thick));
    float radial = smoothstep(rs * 2.10, rs * 2.80, rr) * smoothstep(rs * 12.0, rs * 4.2, rr);

    float phi = atan(dot(lensed, gb), dot(lensed, ga));
    float swirl = 0.58 + 0.42 * sin(phi * 7.0 + t * 2.2 / max(rr, 0.06) + rr * 24.0);
    float doppler = 0.45 + 0.95 * smoothstep(-0.9, 0.9, sin(phi));

    vec3 hotIn = mix(hot, vec3(1.0), 0.55);
    vec3 hotOut = mix(hot, warm, 0.45);
    vec3 disc = mix(hotIn, hotOut, smoothstep(rs * 2.4, rs * 8.0, rr));
    body += disc * sheet * radial * swirl * doppler * 2.4 * density;

    float shadow = smoothstep(rs * 2.62, rs * 2.46, ang);
    body *= 1.0 - shadow;

    float photon = exp(-pow((ang - rs * 2.62) / (rs * 0.17), 2.0));
    body += mix(vec3(1.0), hot, 0.28) * photon * 2.1;

    return max(body, vec3(0.0));
}

void main() {
    int effect = int(cfg.x + 0.5);
    float intensity = clamp(cfg.y, 0.0, 1.0);
    float exposure = cfg.w;
    float time = camFwd.w;
    float seed = extra.x;
    float starGain = extra.z;
    float starChance = extra.w;
    int starLayers = int(march.z + 0.5);
    float lutH = march.w;
    float k = 1.0 / max(march.y, 0.05);

    vec3 warm = tint.rgb;
    vec3 cool = tint2.rgb;
    vec3 hot = tint3.rgb;
    float amount = 0.1 + intensity * 1.4;

    vec2 ndc = texCoord * 2.0 - 1.0;
    vec3 dir = normalize(
        camFwd.xyz +
        camRight.xyz * (ndc.x * camRight.w) +
        camUp.xyz * (ndc.y * camUp.w)
    );

    vec3 colour;

    if (effect == 3) {
        colour = skyStars(dir, warm, cool, hot, amount, time, k, starLayers);
    } else if (effect == 4) {
        colour = skyCaustics(dir, warm, cool, hot, amount, time, k, starLayers);
    } else if (effect == 5) {
        colour = skyAurora(dir, warm, cool, hot, amount, time, k, starLayers);
    } else if (effect == 6) {
        colour = skyGalaxy(dir, normalize(sunDir.xyz), warm, cool, hot, amount, time, k, starLayers);
    } else {
        vec4 lut = texture(SkySampler, dirToLutUv(dir, lutH));
        colour = unpackHdr(lut.rgb);
        float starT = lut.a;

        if (effect == 1) {
            colour = nebulaDetail(dir, normalize(sunDir.xyz), colour, time, seed, k, warm, hot);
        } else if (effect == 2) {
            float rid = spiral3(dir * (5.2 * k) + vec3(0.0, time * 0.05, 0.0), 4);
            float sharp = pow(clamp(1.0 - abs(rid) * 0.55, 0.0, 1.0), 9.0);
            colour += mix(hot, vec3(1.0), 0.40) * sharp * 0.75;
        }

        if (effect != 2) {
            vec3 s = starField3(dir * k, 42.0, starChance, 1.0);
            if (starLayers > 1) {
                s += starField3(dir * k + 7.7, 86.0, starChance * 0.62, 0.42);
            }
            colour += s * starGain * starT;
        }
    }

    colour *= exposure;
    colour = tonemap(colour);
    colour = vivid(colour, 1.16);
    colour += (hash12(gl_FragCoord.xy) - 0.5) * 0.004;

    fragColor = vec4(max(colour, vec3(0.0)), 1.0);
}
