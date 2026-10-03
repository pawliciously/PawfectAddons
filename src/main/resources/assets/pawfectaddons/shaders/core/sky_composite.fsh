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

// Angular radius a star is drawn at on a 1080p screen with a 70 degree field of view. Stars keep
// their total light relative to this, so they look the same at any resolution.
const float STAR_REF = 0.00085;

// A star's core at squared angular distance d2. It is never drawn narrower than about a pixel:
// its light is spread over the wider spot instead, so a star keeps its brightness at any
// resolution and stays steady as the sky drifts rather than crawling between pixels.
float starCore(float d2, float radius) {
    return exp(-d2 / (radius * radius)) * (STAR_REF * STAR_REF) / (radius * radius);
}

// Colour, brightness and twinkle for the light a star sends to this pixel. Each star twinkles on
// its own: two sines at unrelated rates and phases taken from its hash, so the field never pulses
// together and no star repeats on an obvious beat.
vec3 starShade(float light, float lum, vec3 r, float time, float shimmer) {
    float wave = 0.6 * sin(time * (0.9 + 2.3 * r.x) + 37.0 * r.y) + 0.4 * sin(time * (0.29 + 0.73 * r.z) + 11.0 * r.x);
    float sparkle = 1.0 + shimmer * (0.3 + 0.7 * lum) * wave;
    vec3 hue = mix(vec3(1.0, 0.98, 0.95), vec3(0.80, 0.87, 1.0), r.z * r.z);
    return hue * light * (0.18 + lum * 3.2) * sparkle;
}

// How much of a star shows (x) and how bright it is (y). Whether it shows depends on how crowded
// its part of the sky is: it fades in as the crowding rises past its rank rather than switching
// on there, which keeps it whole where the crowding changes across it, and every star is gone
// where there is no crowding at all. Its brightness comes from the rank too, but folded so that
// the stars on the threshold are not always the brightest ones.
vec2 starRank(float h, float chance, float density) {
    float rank = h / chance;
    float mag = fract(rank * 7.0);
    float lum = mag * mag;
    return vec2(smoothstep(rank, rank + 0.2, density), lum * lum);
}

// One candidate star per cell, so a layer costs one hash per pixel instead of a search through
// neighbouring cells. Only this cell's pixels see its star, so the star's light has to die out
// before the cell walls: a cut through it would flicker as it drifts across pixels. Stars without
// that room fade away instead, which thins a layer out gracefully where its cells are only a few
// pixels wide.
vec3 milkyStars(vec3 g, float scale, float chance, float density, float gain, float size, float halo, vec3 tint, float px, float time, float shimmer) {
    vec3 cell = floor(g * scale);
    float h = hash13(cell);
    if (h >= chance) return vec3(0.0);
    vec2 rank = starRank(h, chance, density);
    if (rank.x <= 0.0) return vec3(0.0);

    vec3 r = hash33(cell + 19.19);
    vec3 star = normalize(cell + 0.2 + r * 0.6);
    // The star's distance to its cell walls across the sky. A wall the sphere meets at a grazing
    // angle lies further off on the sky than inside the cell, hence the slope.
    vec3 local = star * scale - cell;
    vec3 wall = min(local, 1.0 - local) * inversesqrt(max(1.0 - star * star, 1e-4));
    float room = min(wall.x, min(wall.y, wall.z)) / scale;

    float lum = rank.y;
    float radius = max(size * (0.00022 + 0.00055 * lum), px * 0.75);
    float keep = smoothstep(1.8, 2.6, room / radius);
    if (keep <= 0.0) return vec3(0.0);

    vec3 delta = g - star;
    float d2 = dot(delta, delta);
    float haloSize = min(STAR_REF * STAR_REF * 22.0, room * room * 0.16);
    float light = starCore(d2, radius) + halo * lum * exp(-d2 / haloSize) * 0.07;
    light *= smoothstep(room * room, room * room * 0.64, d2) * keep * rank.x;
    return starShade(light, lum, r, time, shimmer) * tint * gain;
}

// The band holds more stars than one per cell can show cleanly, so its densest layer looks at the
// four cells around each pixel instead, laid out in galactic longitude and latitude where they
// stay nearly square close to the plane. Any star within half a cell of a pixel is in those four,
// so as long as its light dies out within that reach, no star is ever cut off at a cell edge.
vec3 bandStars(vec3 g, float u, float scale, float chance, float density, float gain, vec3 tint, float px, float time, float shimmer) {
    // A whole number of cells around keeps the wrap at the anticentre seamless.
    float around = floor(TAU * scale);
    float cosLat = sqrt(max(1.0 - g.y * g.y, 0.05));
    vec2 size = vec2(TAU * cosLat / around, 1.0 / (scale * cosLat));
    float reach = 0.5 * min(size.x, size.y);
    vec2 p = vec2(u * around, g.y * scale);
    vec2 base = floor(p - 0.5);

    vec3 light = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec2 cell = base + vec2(float(i & 1), float(i >> 1));
        vec3 key = vec3(mod(cell.x, around), cell.y, 71.0);
        float h = hash13(key);
        if (h >= chance) continue;
        vec2 rank = starRank(h, chance, density);
        if (rank.x <= 0.0) continue;

        vec3 r = hash33(key + 19.19);
        vec2 delta = (p - cell - r.xy) * size;
        float d2 = dot(delta, delta);
        float radius = max(0.9 * (0.00022 + 0.00055 * rank.y), px * 0.75);
        float keep = smoothstep(0.45, 0.35, radius / reach);
        float fade = smoothstep(reach * reach, reach * reach * 0.49, d2);
        light += starShade(starCore(d2, radius) * fade * keep * rank.x, rank.y, r, time, shimmer);
    }
    return light * tint * gain;
}

// A handful of bright stars placed by hand in galactic coordinates, with the soft glow and the
// horizontal lens streak of the reference. A fixed list costs a dot product each per pixel.
const vec4 HEROES[6] = vec4[6](
    vec4(0.901, 0.105, 0.420, 1.00),
    vec4(0.912, -0.242, -0.332, 0.70),
    vec4(0.316, 0.375, 0.871, 0.55),
    vec4(-0.750, -0.500, 0.433, 0.60),
    vec4(-0.262, 0.643, -0.720, 0.50),
    vec4(-0.931, 0.139, -0.339, 0.65)
);

vec3 heroStars(vec3 g, float px, float time, float shimmer) {
    vec3 light = vec3(0.0);
    for (int i = 0; i < 6; i++) {
        float facing = dot(g, HEROES[i].xyz);
        if (facing < 0.985) continue;
        vec3 delta = g - HEROES[i].xyz;
        float d2 = dot(delta, delta);
        float x = dot(delta, galRight.xyz);
        float y = dot(delta, galUp.xyz);
        float a = HEROES[i].w;

        // A sharp core, two soft glows and the long horizontal streak and short vertical spike
        // a camera lens adds to a very bright point. The streak follows the screen, not the sky.
        float radius = max(0.0008 * (0.6 + a), px * 0.75);
        float point = exp(-d2 / (radius * radius)) * 4.0;
        float dist = sqrt(d2);
        float glow = exp(-dist * 150.0) * 0.8 + exp(-dist * 42.0) * 0.32;
        float streak = exp(-abs(y) / max(0.0007, px * 1.2)) * exp(-abs(x) * 18.0 / (0.4 + a)) * 1.5;
        float spike = exp(-abs(x) / max(0.0005, px)) * exp(-abs(y) * 90.0) * 0.3;
        float fade = smoothstep(0.985, 0.992, facing);
        float sparkle = 1.0 + shimmer * 0.2 * sin(time * (1.1 + float(i) * 0.37) + float(i) * 2.1);
        light += vec3(0.80, 0.89, 1.0) * a * (point + glow + streak * a + spike) * fade * sparkle;
    }
    return light;
}

// Two distant galaxies far from the band: a face-on spiral and a thin edge-on disc.
vec3 deepSky(vec3 g) {
    vec3 light = vec3(0.0);

    vec3 spiral = vec3(0.650, -0.530, -0.545);
    float sc = dot(g, spiral);
    if (sc > 0.9982) {
        vec3 a = normalize(cross(spiral, vec3(0.0, 1.0, 0.0)));
        vec3 b = cross(spiral, a);
        vec3 delta = g - spiral;
        vec2 uv = vec2(dot(delta, a), dot(delta, b) * 1.6) / 0.032;
        float r = length(uv);
        float arms = 0.5 + 0.5 * cos(2.0 * (atan(uv.y, uv.x) - log(r + 0.04) * 2.4));
        float disc = exp(-r * 3.4) * (0.25 + 0.75 * arms * smoothstep(0.05, 0.35, r));
        float core = exp(-r * r * 140.0);
        light += mix(vec3(0.62, 0.70, 1.0), vec3(1.0, 0.86, 0.76), core) * (disc * 0.85 + core * 1.1);
    }

    vec3 edgeOn = vec3(0.568, 0.469, 0.676);
    float ec = dot(g, edgeOn);
    if (ec > 0.9994) {
        vec3 a = normalize(cross(edgeOn, vec3(0.3, 1.0, 0.1)));
        vec3 b = cross(edgeOn, a);
        vec3 delta = g - edgeOn;
        vec2 uv = vec2(dot(delta, a), dot(delta, b)) / 0.016;
        float body = exp(-(uv.x * uv.x * 2.4 + uv.y * uv.y * 70.0));
        float lane = 1.0 - 0.55 * exp(-uv.y * uv.y * 900.0) * smoothstep(0.9, 0.2, abs(uv.x));
        float core = exp(-dot(uv, uv) * 40.0);
        light += vec3(0.78, 0.82, 1.0) * (body * 0.45 * lane + core * 0.5);
    }
    return light;
}

// Shooting stars come from the CPU as up to three paths, so the shader never has to look for
// them. Each is drawn in its own frame: one dot product says how far a pixel sits off the path
// and another how far along it, which holds for the short arcs a meteor covers.
vec3 shootingStars(vec3 dir, float px) {
    vec3 light = vec3(0.0);
    for (int i = 0; i < 3; i++) {
        vec4 head = meteorHead[i];
        if (head.w <= 0.0) continue;
        if (dot(dir, head.xyz) < 0.7) continue;
        vec4 path = meteorPath[i];
        vec4 side = meteorSide[i];
        float across = dot(dir, side.xyz);
        float width = max(side.w, px * 0.75);
        if (abs(across) > width * 10.0) continue;
        float along = dot(dir, path.xyz);
        float energy = side.w / width;

        // The trail fades and thins toward its tail; the head is a round glow brighter than both.
        float u = clamp(-along / max(path.w, 1e-4), 0.0, 1.0);
        float thin = width * mix(1.0, 0.35, u);
        float tail = (1.0 - u) * (1.0 - u);
        float trail = exp(-across * across / (thin * thin)) * tail * step(along, 0.0);
        float r2 = (along * along + across * across) / (width * width);
        float glow = exp(-r2 * 0.5) * 1.6 + exp(-r2 * 0.03) * 0.12;
        light += meteorTint[i].rgb * head.w * energy * (trail + glow);
    }
    return light;
}

vec3 skyMilkyWay(vec3 dir, float time, float intensity, float lutH) {
    vec3 g = vec3(dot(dir, galX.xyz), dot(dir, galY.xyz), dot(dir, galZ.xyz));
    vec2 uv = dirToLutUv(g, lutH);
    vec4 lut = texture(SkySampler, uv);
    vec3 colour = unpackHdr(lut.rgb);
    float lane = lut.a;
    // The angle one pixel spans here: the centre's, shrinking off-axis as the screen tilts away
    // from the ray. It comes from the camera rather than screen derivatives, which some compilers
    // move into the per-star branches, where they come out as zero.
    float facing = dot(dir, camFwd.xyz);
    float px = galX.w * facing * sqrt(facing);
    int quality = int(galUp.w + 0.5);
    float shimmer = galRight.w * 0.45;

    // The LUT is coarser than the screen, so two octaves of fine noise put the detail back: grain
    // in the bright nebula and ragged, fractal edges on the dust lanes. Both only run where they
    // show, so the dark sky pays nothing for them.
    float lum = dot(colour, vec3(0.2126, 0.7152, 0.0722));
    float edge = lane * (1.0 - lane) * 4.0;
    float weight = smoothstep(0.05, 0.5, lum);
    if (quality > 0 && (weight > 0.0 || edge > 0.02)) {
        vec3 dp = vec3(g.x, g.y * 2.4, g.z) * 105.0 + time * 0.002;
        float n = paNoise3(dp) * 0.62 + paNoise3(dp * 2.1 + 5.0) * 0.38;
        colour *= 1.0 + (n - 0.5) * 0.3 * weight;
        colour *= 1.0 - clamp(edge * (n - 0.42) * 1.1, 0.0, 0.75);
    }
    float crowd = smoothstep(0.10, 0.8, lum) * (1.0 - lane * 0.85);

    // Three layers of single stars from faint and plentiful to sparse and bright, and a dense one
    // that only fills the band. The faint ones follow the star density in the LUT, which is what
    // makes the band read as millions of stars.
    float plenty = mix(0.7, 1.3, intensity);
    // Stars inside the band pick up some of the nebula colour behind them, so the band reads as a
    // pink-white stipple rather than white specks pasted on top.
    vec3 behind = colour / max(max(colour.r, max(colour.g, colour.b)), 1e-3);
    vec3 inBand = mix(vec3(1.0), behind, 0.45 * crowd);
    vec3 stars = milkyStars(g, 120.0, 0.75 * plenty, mix(0.45, 1.1, crowd), 0.45 * mix(0.4, 2.0, crowd), 1.0, 0.0, inBand, px, time, shimmer * 0.5);
    stars += milkyStars(g, 92.0, 0.48 * plenty, mix(0.6, 1.1, crowd), 0.55, 1.0, 0.4, inBand, px, time, shimmer);
    stars += milkyStars(g, 37.0, 0.22, 1.2, 1.4, 1.2, 1.0, vec3(1.0), px, time, shimmer);
    if (quality > 0 && crowd > 0.0) stars += bandStars(g, uv.x, 150.0, 0.85 * plenty, crowd * 1.1, 0.3, inBand, px, time, shimmer * 0.4);

    colour += stars;
    colour += heroStars(g, px, time, shimmer);
    colour += deepSky(g);
    colour += shootingStars(dir, px);
    return colour;
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
    } else if (effect == 7) {
        colour = skyMilkyWay(dir, time, intensity, lutH);
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
