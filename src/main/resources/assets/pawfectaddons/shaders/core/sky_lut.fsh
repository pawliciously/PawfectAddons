#version 330

in vec2 texCoord;
out vec4 fragColor;

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
    vec4 galX;
    vec4 galY;
    vec4 galZ;
    vec4 galRight;
    vec4 galUp;
    vec4 meteorHead[3];
    vec4 meteorPath[3];
    vec4 meteorSide[3];
    vec4 meteorTint[3];
};

const float PI = 3.14159265359;
const float NUDGE = 0.739513;
const float SPIRAL_NORM = 0.8040288;

float spiralC(vec3 p, int iters) {
    float n = 0.0;
    float iter = 1.0;
    for (int i = 0; i < 8; i++) {
        if (i >= iters) break;
        n += -abs(sin(p.y * iter) + cos(p.x * iter)) / iter;
        p.xy += vec2(p.y, -p.x) * NUDGE;
        p.xy *= SPIRAL_NORM;
        p.xz += vec2(p.z, -p.x) * NUDGE;
        p.xz *= SPIRAL_NORM;
        iter *= 1.733733;
    }
    return -n;
}

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

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float paNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash13(i);
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));
    return mix(
        mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
        mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y),
        f.z
    );
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

const mat3 OCTAVE_TURN = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);

float fbmTurned(vec3 p, int oct) {
    float sum = 0.0;
    float amp = 0.5;
    float norm = 0.0;
    for (int i = 0; i < 6; i++) {
        if (i >= oct) break;
        sum += amp * paNoise3(p);
        norm += amp;
        p = OCTAVE_TURN * p * 2.03;
        amp *= 0.55;
    }
    return sum / norm;
}

vec3 rotAxis(vec3 p, vec3 axis, float a) {
    float c = cos(a);
    float s = sin(a);
    return p * c + cross(axis, p) * s + axis * dot(axis, p) * (1.0 - c);
}

vec3 lutUvToDir(vec2 uv) {
    float az = (uv.x * 2.0 - 1.0) * PI;
    float t = uv.y * 2.0 - 1.0;
    float y = sign(t) * t * t;
    float horiz = sqrt(max(0.0, 1.0 - y * y));
    return vec3(cos(az) * horiz, y, sin(az) * horiz);
}

vec3 packHdr(vec3 c) {
    return sqrt(clamp(c / (1.0 + c), 0.0, 1.0));
}

vec4 nebulaLut(vec3 dir, vec3 axis, float time, float seed, float intensity, int marchSteps, float k, vec3 warm, vec3 cool, vec3 hot) {
    vec3 off = vec3(seed * 3.17, seed * 1.91, seed * 5.43);
    vec3 deep = cool * 0.011 + vec3(0.002, 0.003, 0.009);

    float ang = acos(clamp(dot(dir, axis), -1.0, 1.0));
    float envelope = smoothstep(2.05, 0.40, ang);
    if (envelope < 0.004) return vec4(deep, 1.0);

    float thr = mix(0.60, 0.36, intensity);

    vec3 acc = vec3(0.0);
    float trans = 1.0;
    float t = 0.30;
    float dt = 2.60 / float(marchSteps);

    for (int i = 0; i < 48; i++) {
        if (i >= marchSteps || trans < 0.02) break;

        vec3 p = dir * t;
        float h = dot(p, axis);
        vec3 rad = p - axis * h;
        float rl = length(rad);

        float twist = (3.30 + time * 0.16) / (0.34 + rl * 1.30) + h * 0.55 + time * 0.055;
        vec3 q = (axis * h * 0.70 + rotAxis(rad, axis, twist)) * (2.15 * k) + off;

        float fil = ridge3(q, 5);
        float lane = paNoise3(q * 0.40 + vec3(13.0, time * 0.09, -time * 0.06));
        float shape = fil * (0.58 + 0.62 * lane);

        float shell = exp(-rl * rl * 0.62) * envelope;
        float dens = smoothstep(thr, thr + 0.38, shape) * shell;

        if (dens > 0.002) {
            float depth = clamp((t - 0.30) / 2.60, 0.0, 1.0);
            float heat = clamp(1.0 - rl * 0.72, 0.0, 1.0);
            float crest = smoothstep(thr + 0.34, thr + 0.06, shape);

            vec3 col = mix(cool, warm, smoothstep(0.04, 0.52, shape - thr));
            col = mix(col * 0.22, col, smoothstep(0.0, 0.52, dens));
            col += hot * pow(heat, 2.6) * 1.30;
            col += mix(warm, hot, 0.60) * crest * 0.42;
            col *= mix(1.30, 0.32, depth);

            float st = exp(-dens * dt * 2.6);
            acc += trans * col * (1.0 - st) * 2.05;
            trans *= st;
        }

        t += dt;
    }

    acc += trans * deep;

    float glow = exp(-ang * ang * 7.5);
    acc += mix(hot, vec3(1.0), 0.30) * glow * glow * (0.35 + intensity * 0.55);

    return vec4(acc, trans);
}

vec3 liquidLut(vec3 dir, float time, float seed, float intensity, float k, vec3 warm, vec3 cool, vec3 hot) {
    vec3 p = dir * (2.2 * k) + vec3(seed * 2.7, time * 0.06, seed * 1.3);

    vec3 q = vec3(
        spiral3(p, 4),
        spiral3(p + 5.2, 4),
        spiral3(p + 11.7, 4)
    ) * 0.30;

    vec3 r = vec3(
        spiral3(p + 2.4 * q + 1.7, 4),
        spiral3(p + 2.4 * q + 8.3, 4),
        spiral3(p + 2.4 * q + 4.1, 4)
    ) * 0.30;

    float d = spiral3(p + 2.4 * r, 5) * 0.22;

    float v = clamp(length(r) * 0.55 + d * 0.35 + 0.25, 0.0, 1.0);

    vec3 col = mix(cool * 0.28, cool, smoothstep(0.0, 0.44, v));
    col = mix(col, warm, smoothstep(0.36, 0.84, v));
    col = mix(col, mix(hot, vec3(1.0), 0.42), smoothstep(0.82, 1.0, v));

    float ridge = 1.0 - clamp(abs(d) * 2.6, 0.0, 1.0);
    col += mix(hot, vec3(1.0), 0.45) * pow(ridge, 7.0) * 0.62;

    float lift = clamp(0.55 + dir.y * 0.45, 0.0, 1.0);
    col *= mix(0.50, 1.40, lift);

    return max(col, vec3(0.0)) * mix(0.55, 1.55, intensity);
}

vec4 milkyWayLut(vec3 d, float time, float seed, float intensity, float k, int oct, vec3 pink, vec3 deep, vec3 glow) {
    vec3 off = mod(vec3(seed * 3.17, seed * 1.91, seed * 5.43), 97.0);

    vec3 flowA = time * vec3(0.0012, 0.00046, -0.00084);
    vec3 flowB = time * vec3(-0.00076, 0.00094, 0.00058);
    vec3 flowC = time * vec3(0.00054, -0.00062, 0.00104);

    float toward = d.x * 0.5 + 0.5;
    float bulge = toward * toward;
    bulge *= bulge;
    float meander = (paNoise3(d * (1.6 * k) + off) - 0.5) * 0.12 + (paNoise3(d * (4.3 * k) + off + 7.0) - 0.5) * 0.045;
    float swell = 0.65 + 0.7 * paNoise3(d * (1.1 * k) + off + 3.3 + flowC * 0.5);
    float width = (0.07 + 0.07 * bulge) * swell * mix(0.8, 1.3, intensity);
    float b = (d.y - meander) / width;
    float b2 = b * b;
    float band = exp(-b2);
    float halo = exp(-b2 * 0.09);
    float wide = exp(-b2 * 0.02);

    vec3 p = vec3(d.x, d.y * 2.2, d.z) * (2.6 * k) + off;

    vec3 q = vec3(
        fbmTurned(p * 0.8 + flowA, 3),
        fbmTurned(p * 0.8 + vec3(5.2, 1.3, 2.8) + flowB, 3),
        fbmTurned(p * 0.8 + vec3(1.7, 9.2, 4.4) + flowC, 3)
    );
    vec3 w = p + (q - 0.5) * 1.2;

    float lumps = smoothstep(0.45, 0.8, fbmTurned(w * 3.8 + flowA * 1.7, 3));
    float cloud = fbmTurned(w * 1.6 + flowB * 1.3, oct) * 0.85 + lumps * 0.2;
    vec3 dw = vec3(w.x, w.y * 1.5, w.z) * 2.1 + vec3(11.0, 3.0, 7.0) - flowA;
    float dust = fbmTurned(dw, max(oct - 1, 2)) * 0.75 + fbmTurned(dw * 3.1 + 4.0, 2) * 0.25;
    float region = fbmTurned(p * 0.45 + vec3(31.0, 17.0, 5.0) + flowC * 0.4, 3);
    float haze = fbmTurned(d * (1.3 * k) + off + flowB * 0.3, 3);

    float em = smoothstep(0.36, 0.76, cloud);
    float bright = smoothstep(0.60, 0.86, cloud);

    float blueSide = smoothstep(0.30, 0.62, region + clamp(b * 0.12, -0.3, 0.3)) * (1.0 - bulge * 0.7) * (1.0 - band * 0.85);

    vec3 violet = mix(pink, deep, 0.55);
    vec3 rose = mix(pink, glow, 0.55);
    vec3 sky = mix(deep, vec3(0.45, 0.65, 1.0), 0.35);
    vec3 warmRamp = mix(violet * 0.45, pink, smoothstep(0.0, 0.5, em));
    warmRamp = mix(warmRamp, rose * 1.25, smoothstep(0.45, 0.9, em));
    warmRamp = mix(warmRamp, glow * 1.6, bright * bright);
    vec3 coolRamp = mix(deep * 0.6, sky * 1.25, smoothstep(0.0, 0.7, em));
    coolRamp = mix(coolRamp, mix(sky, vec3(0.85, 0.92, 1.0), 0.5) * 1.2, bright * bright);
    vec3 nebula = mix(warmRamp, coolRamp, blueSide);

    float density = band * (0.26 + 1.6 * em + 1.1 * bright) + halo * (0.12 + 0.25 * em);
    vec3 col = nebula * density;

    float coreLine = exp(-b2 * 3.5);
    col += mix(glow, vec3(1.0), 0.35) * coreLine * (0.10 + 0.9 * bulge) * (0.3 + bright);
    col += rose * bulge * exp(-b2 * 0.6) * 0.30;

    float slab = exp(-b2 * 1.4);
    float lane = smoothstep(0.45, 0.60, dust) * slab;
    float rim = smoothstep(0.38, 0.50, dust) * slab;
    col *= mix(vec3(1.0), vec3(1.0, 0.6, 0.46), rim * 0.7);
    col *= 1.0 - 0.92 * lane;

    float veil = smoothstep(0.42, 0.75, region) * wide;
    vec3 night = deep * (0.09 + 0.14 * haze) + vec3(0.008, 0.008, 0.026);
    night += mix(violet, deep, 0.3) * halo * 0.34 * (1.0 - 0.85 * lane);
    night += sky * veil * (0.14 + 0.36 * haze);
    col += night;

    return vec4(col * mix(0.6, 1.3, intensity), lane);
}

vec3 voidBase(vec3 dir, float seed, float intensity, float k, vec3 warm, vec3 cool, vec3 hot) {
    vec3 off = vec3(seed * 4.13, seed * 2.37, seed * 6.71);
    float g = smoothstep(1.05, 2.60, spiralC(dir * (1.6 * k) + off, 5));
    vec3 c = mix(vec3(0.006, 0.008, 0.020), cool * 0.30, g);
    float w = smoothstep(1.80, 2.95, spiralC(dir * (3.4 * k) + off * 1.7, 5));
    c += mix(warm, hot, 0.35) * w * 0.22;
    return c * mix(0.55, 1.45, intensity);
}

void main() {
    vec3 dir = lutUvToDir(texCoord);

    int effect = int(cfg.x + 0.5);
    float intensity = clamp(cfg.y, 0.0, 1.0);
    float time = camFwd.w;
    float seed = extra.x;
    float k = 1.0 / max(march.y, 0.05);

    vec3 warm = tint.rgb;
    vec3 cool = tint2.rgb;
    vec3 hot = tint3.rgb;

    int marchSteps = int(march.x + 0.5);

    vec3 radiance;
    float alpha;

    if (effect == 1) {
        vec4 n = nebulaLut(dir, normalize(sunDir.xyz), time, seed, intensity, marchSteps, k, warm, cool, hot);
        radiance = n.rgb;
        alpha = n.a;
    } else if (effect == 2) {
        radiance = liquidLut(dir, time, seed, intensity, k, warm, cool, hot);
        alpha = 0.0;
    } else if (effect == 7) {
        vec4 m = milkyWayLut(dir, time, seed, intensity, k, marchSteps, warm, cool, hot);
        radiance = m.rgb;
        alpha = m.a;
    } else {
        radiance = voidBase(dir, seed, intensity, k, warm, cool, hot);
        alpha = 1.0;
    }

    fragColor = vec4(packHdr(max(radiance, vec3(0.0))), alpha);
}
