#version 330

// The media card's backdrop: four colours from the album art drifting through each other
// like ink in water, inside an anti-aliased rounded card.
//
// vertexColor  rgb colour 1, a card opacity
// borderColor  rgb colour 2, a how "awake" the motion is (1 playing, lower when paused)
// local        xy pixel offset from the card centre, z animation phase, w corner radius
// shape        xy half size, zw colours 3 and 4 packed as 0xRRGGBB in a float

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;
in vec4 borderColor;
in vec4 local;
flat in vec4 shape;

out vec4 fragColor;

vec3 unpackRgb(float packed) {
    float n = floor(packed + 0.5);
    return vec3(floor(n / 65536.0), mod(floor(n / 256.0), 256.0), mod(n, 256.0)) / 255.0;
}

float roundedBox(vec2 point, vec2 halfSize, float radius) {
    vec2 corner = abs(point) - halfSize + radius;
    return min(max(corner.x, corner.y), 0.0) + length(max(corner, 0.0)) - radius;
}

float hash12(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += noise(p) * a;
        p = p * 2.03 + vec2(3.7, 1.9);
        a *= 0.5;
    }
    return v;
}

float blob(vec2 p, vec2 centre, float size) {
    vec2 d = p - centre;
    return exp(-dot(d, d) / (size * size));
}

void main() {
    vec2 halfSize = shape.xy;
    float radius = clamp(local.w, 0.0, min(halfSize.x, halfSize.y));
    float dist = roundedBox(local.xy, halfSize, radius);
    float edge = max(fwidth(dist), 1e-5);
    float coverage = clamp(0.5 - dist / edge, 0.0, 1.0);
    if (coverage <= 0.0) discard;

    vec2 uv = (local.xy + halfSize) / max(halfSize * 2.0, vec2(1.0));
    float aspect = halfSize.x / max(halfSize.y, 1.0);
    vec2 p = vec2(uv.x * aspect, uv.y);
    float t = local.z;
    float awake = borderColor.a;

    vec3 c1 = vertexColor.rgb;
    vec3 c2 = borderColor.rgb;
    vec3 c3 = unpackRgb(shape.z);
    vec3 c4 = unpackRgb(shape.w);

    // Warp the plane with slow noise so the blobs smear into each other instead of
    // reading as circles.
    vec2 warp = vec2(
        fbm(p * 1.35 + vec2(t * 0.11, -t * 0.07)),
        fbm(p * 1.35 + vec2(-t * 0.08, t * 0.12) + 7.3)
    ) - 0.5;
    vec2 q = p + warp * (0.55 + 0.25 * awake);

    vec2 b1 = vec2(aspect * (0.16 + 0.12 * sin(t * 0.53)), 0.22 + 0.22 * cos(t * 0.41));
    vec2 b2 = vec2(aspect * (0.84 + 0.10 * cos(t * 0.47 + 1.7)), 0.28 + 0.26 * sin(t * 0.59 + 0.6));
    vec2 b3 = vec2(aspect * (0.58 + 0.26 * sin(t * 0.31 + 3.1)), 0.86 + 0.14 * cos(t * 0.37 + 2.2));
    vec2 b4 = vec2(aspect * (0.34 + 0.22 * cos(t * 0.43 + 4.4)), 0.58 + 0.24 * sin(t * 0.29 + 5.0));

    float size = 0.42 + 0.10 * aspect;
    float w1 = blob(q, b1, size);
    float w2 = blob(q, b2, size * 0.95);
    float w3 = blob(q, b3, size * 1.10);
    float w4 = blob(q, b4, size * 0.90);

    // A dark floor keeps the gaps between blobs from going flat grey.
    vec3 floorColour = min(min(c1, c2), min(c3, c4)) * 0.45;
    float floorWeight = 0.18;
    vec3 colour = (c1 * w1 + c2 * w2 + c3 * w3 + c4 * w4 + floorColour * floorWeight)
        / (w1 + w2 + w3 + w4 + floorWeight);

    // Light from above like frosted glass, a slow sheen sweeping across, and a vignette.
    colour += vec3(0.07) * smoothstep(0.55, 0.0, uv.y);
    float sweep = 1.0 - abs(fract(uv.x * 0.45 - uv.y * 0.25 - t * 0.035) - 0.5) * 2.0;
    colour += vec3(0.05) * smoothstep(0.7, 1.0, sweep) * awake;
    float vignette = length((uv - vec2(0.5, 0.45)) * vec2(1.0, 0.9));
    colour *= mix(1.0, 0.72, smoothstep(0.35, 0.85, vignette));

    // Thin bright rim, strongest along the top edge.
    float rim = clamp(1.0 - abs(dist + 0.75) / max(edge * 1.2, 0.75), 0.0, 1.0);
    colour = mix(colour, vec3(1.0), rim * mix(0.22, 0.06, uv.y));

    // Dither so the soft gradients don't band.
    colour += (hash12(local.xy * 1.7 + fract(t)) - 0.5) / 160.0;

    float alpha = vertexColor.a * coverage;
    if (alpha <= 0.0) discard;
    fragColor = vec4(max(colour, vec3(0.0)), alpha) * ColorModulator;
}
