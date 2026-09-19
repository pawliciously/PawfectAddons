#version 330

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D BlurSampler;
uniform sampler2D MaskSampler;
uniform sampler2D GlowSampler;
uniform sampler2D PortalSkySampler;
uniform sampler2D PortalSampler;
uniform sampler2D TrailSampler;
uniform sampler2D RowSampler;

layout(std140) uniform CompositeData {
    vec4 tintColor;
    vec4 outlineColor;
    vec4 params1;
    vec4 params2;
    vec4 params3;
    vec4 params4;
    vec4 overlayA;
    vec4 overlayB;
    vec4 overlayC;
    vec4 trailColor;
    vec4 params5;
    vec4 params6;
};

const vec3 PORTAL_COLORS[16] = vec3[](
    vec3(0.022087, 0.098399, 0.110818),
    vec3(0.011892, 0.095924, 0.089485),
    vec3(0.027636, 0.101689, 0.100326),
    vec3(0.046564, 0.109883, 0.114838),
    vec3(0.064901, 0.117696, 0.097189),
    vec3(0.063761, 0.086895, 0.123646),
    vec3(0.084817, 0.111994, 0.166380),
    vec3(0.097489, 0.154120, 0.091064),
    vec3(0.106152, 0.131144, 0.195191),
    vec3(0.097721, 0.110188, 0.187229),
    vec3(0.133516, 0.138278, 0.148582),
    vec3(0.070006, 0.243332, 0.235792),
    vec3(0.196766, 0.142899, 0.214696),
    vec3(0.047281, 0.315338, 0.321970),
    vec3(0.204675, 0.390010, 0.302066),
    vec3(0.080955, 0.314821, 0.661491)
);

const mat4 SCALE_TRANSLATE = mat4(
    0.5, 0.0, 0.0, 0.25,
    0.0, 0.5, 0.0, 0.25,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
);

mat2 mat2_rotate_z(float radians) {
    return mat2(
        cos(radians), -sin(radians),
        sin(radians), cos(radians)
    );
}

mat4 end_portal_layer(float layer, float gameTime) {
    mat4 translate = mat4(
        1.0, 0.0, 0.0, 17.0 / layer,
        0.0, 1.0, 0.0, (2.0 + layer / 1.5) * (gameTime * 1.5),
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    );

    mat2 rotate = mat2_rotate_z(radians((layer * layer * 4321.0 + layer * 9.0) * 2.0));
    mat2 scale = mat2((4.5 - layer / 4.0) * 2.0);

    return mat4(scale * rotate) * translate * SCALE_TRANSLATE;
}

vec3 endPortal(vec2 uv, float gameTime, int layers) {
    vec4 texProj0 = vec4(uv, 0.0, 1.0);
    vec3 color = textureProj(PortalSkySampler, texProj0).rgb * PORTAL_COLORS[0];
    for (int i = 0; i < layers; i++) {
        color += textureProj(PortalSampler, texProj0 * end_portal_layer(float(i + 1), gameTime)).rgb * PORTAL_COLORS[i];
    }
    return color;
}

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
        mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x),
        f.y
    );
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.52;
    for (int i = 0; i < 4; i++) {
        v += noise(p) * a;
        p = p * 2.02 + vec2(8.4, 5.7);
        a *= 0.5;
    }
    return v;
}

vec3 liquid(vec2 uv, float time, vec3 colA, vec3 colB, vec3 colC) {
    vec2 p = uv * 3.0;
    float t = time * 0.06;

    vec2 q = vec2(fbm(p + vec2(0.0, t)), fbm(p + vec2(5.2, 1.3) - t * 0.6));
    vec2 r = vec2(
        fbm(p + 4.0 * q + vec2(1.7, 9.2) + t * 0.15),
        fbm(p + 4.0 * q + vec2(8.3, 2.8) - t * 0.126)
    );

    float density = fbm(p + 4.0 * r);
    float flow = clamp(density * density * 2.6, 0.0, 1.0);

    vec3 colour = mix(colA * 0.25, colA, flow);
    colour = mix(colour, colB, clamp(length(r) * 0.9, 0.0, 1.0));
    colour = mix(colour, colC, pow(clamp(density, 0.0, 1.0), 5.0) * 0.8);
    return colour;
}

float starField(vec2 uv, float density, float twinkle, float time) {
    vec2 grid = uv * density;
    vec2 cell = floor(grid);
    vec2 local = fract(grid) - 0.5;

    float seed = hash(cell);
    if (seed < 0.86) return 0.0;

    vec2 jitter = vec2(hash(cell + 3.1), hash(cell + 7.7)) - 0.5;
    float dist = length(local - jitter * 0.7);
    float core = smoothstep(0.13, 0.0, dist);

    float phase = twinkle * time + seed * 43.0;
    float pulse = 0.55 + 0.45 * sin(phase);
    return core * pulse;
}

vec3 space(vec2 uv, float time, vec3 colA, vec3 colB, vec3 colC) {
    float t = time * 0.05;
    vec2 p = uv * 2.2;

    vec2 warp = vec2(fbm(p + vec2(0.0, t * 0.6)), fbm(p + vec2(4.3, -t * 0.4)));
    float cloud = fbm(p * 1.4 + 2.0 * warp);
    float deep = pow(clamp(cloud, 0.0, 1.0), 2.4);

    vec3 colour = mix(colA * 0.06, colA * 0.45, deep);
    colour = mix(colour, colB, pow(clamp(cloud * 1.3, 0.0, 1.0), 3.5) * 0.75);

    vec2 drift = vec2(time * 0.004, time * 0.0015);
    float far = starField(uv + drift * 0.35, 90.0, 2.6, time);
    float mid = starField(uv + drift * 0.7, 55.0, 1.8, time);
    float near = starField(uv + drift * 1.3, 30.0, 1.1, time);

    colour += colC * far * 0.45;
    colour += colC * mid * 0.8;
    colour += colC * near * 1.35;
    return colour;
}

float strayHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float strayNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(strayHash(i), strayHash(i + vec2(1.0, 0.0)), u.x), mix(strayHash(i + vec2(0.0, 1.0)), strayHash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float strayFbm(vec2 p) {
    float v = 0.0;
    float a = 0.52;
    mat2 m = mat2(0.80, -0.60, 0.60, 0.80);
    for (int i = 0; i < 4; i++) {
        v += a * strayNoise(p);
        p = m * p * 2.05;
        a *= 0.52;
    }
    return v;
}

float starLayer(vec2 uv, float t, float threshold) {
    vec2 id = floor(uv);
    float n = strayHash(id);
    if (n <= threshold) return 0.0;
    vec2 f = fract(uv);
    vec2 pos = 0.18 + 0.64 * vec2(strayHash(id + vec2(3.1, 1.7)), strayHash(id + vec2(7.7, 4.2)));
    float d = length(f - pos);
    if (d > 0.22) return 0.0;
    float tw = 0.40 + 0.60 * sin(t * (2.2 + n * 3.5) + n * 40.0);
    float core = smoothstep(0.08, 0.0, d);
    float glow = smoothstep(0.22, 0.0, d) * 0.42;
    float present = smoothstep(threshold, 1.0, n);
    return (core + glow) * tw * present;
}

vec3 stars(vec2 screen, float time, vec3 base, vec3 fill) {
    float t = time / 1200.0 * 90.0;
    float far = starLayer(screen * 0.030 + vec2(t * 0.55, -t * 0.22), t, 0.50);
    float mid = starLayer(screen * 0.050 + vec2(-t * 0.90, t * 0.40), t * 1.25, 0.44);
    float near = starLayer(screen * 0.078 + vec2(t * 1.10, t * 0.16), t * 1.65, 0.54);
    float dust = starLayer(screen * 0.118 + vec2(-t * 0.35, t * 0.80), t * 2.10, 0.34);
    float spark = starLayer(screen * 0.168 + vec2(t * 1.40, -t * 0.55), t * 2.55, 0.58);
    float field = far + mid * 0.92 + near * 0.82 + dust * 0.58 + spark * 0.72;
    float nebula = pow(strayFbm(screen * 0.012 + vec2(t * 0.10, -t * 0.07)), 2.2);
    float density = 0.87;
    vec3 starCol = mix(fill, vec3(0.96, 0.97, 1.0), 0.72);
    vec3 body = base + fill * nebula * mix(0.06, 0.22, density);
    body += starCol * field * density;
    body += near * near * 0.55 * density;
    return body;
}

vec3 hologram(vec2 uv, float time, vec3 colA, vec3 colB, vec3 colC, float luma) {
    float lines = sin((uv.y * 320.0) - time * 5.0);
    float scan = 0.72 + 0.28 * lines;

    float sweep = smoothstep(0.0, 0.08, abs(fract(uv.y * 0.9 - time * 0.22) - 0.5) - 0.42);
    float flicker = 0.92 + 0.08 * sin(time * 41.0 + uv.y * 9.0);

    vec3 base = mix(colA, colB, clamp(luma * 1.4, 0.0, 1.0));
    vec3 colour = base * scan * flicker;
    colour += colC * sweep * 0.6;
    colour += base * pow(clamp(luma, 0.0, 1.0), 2.0) * 0.5;
    return colour;
}

vec3 filmSpectrum(float phase) {
    vec3 shift = vec3(0.0, 2.0944, 4.1888);
    return 0.5 + 0.5 * cos(phase + shift);
}

vec3 oilSlick(vec2 uv, float time, vec3 colA, vec3 colB, vec3 colC) {
    vec2 p = uv * 4.0;
    float t = time * 0.05;

    vec2 warp = vec2(
        fbm(p + vec2(0.0, t)),
        fbm(p + vec2(3.7, -t * 0.8))
    );
    float thickness = fbm(p + 3.0 * warp + vec2(t * 0.2, 0.0));
    float ripple = fbm(p * 2.6 - 2.0 * warp - vec2(0.0, t * 0.35));

    float phase = thickness * 26.0 + ripple * 9.0;
    vec3 film = filmSpectrum(phase);

    vec3 tinted = film * mix(colA, colB, clamp(thickness * 1.6, 0.0, 1.0));
    float sheen = pow(clamp(ripple * 1.4, 0.0, 1.0), 3.0);
    tinted += colC * sheen * 0.55;

    float dark = 0.25 + 0.75 * clamp(thickness * 1.5, 0.0, 1.0);
    return tinted * dark;
}

void main() {
    float bodyEnabled = params1.x;
    float opacity = params1.y;
    float saturation = params1.z;
    float softness = params1.w;

    float edgeLo = params2.x;
    float edgeHi = params2.y;
    float outlineIntensity = params2.z;
    int overlay = int(params2.w + 0.5);

    float overlayStrength = params3.x;
    float time = params3.y;
    int debugView = int(params3.z + 0.5);
    float outlineSoftness = params3.w;

    float customColors = params4.x;
    int portalLayers = int(params4.y + 0.5);
    float tintEnabled = params4.z;

    float trailEnabled = params5.x;
    float trailStrength = params5.y;
    float trailTinted = params5.z;

    vec2 maskTexel = params6.xy;
    float outlineWidth = params6.z;

    if (debugView == 1) {
        float m = texture(MaskSampler, texCoord).r;
        fragColor = vec4(m, m * 0.6, 0.0, 1.0);
        return;
    } else if (debugView == 2) {
        float g = texture(GlowSampler, texCoord).r;
        fragColor = vec4(0.0, g, g, 1.0);
        return;
    } else if (debugView == 3) {
        fragColor = vec4(texture(BlurSampler, texCoord).rgb, 1.0);
        return;
    } else if (debugView == 4) {
        float t = texture(TrailSampler, texCoord).r;
        fragColor = vec4(t * 0.4, t, t * 0.8, 1.0);
        return;
    }

    float sharp = texture(MaskSampler, texCoord).r;
    float soft = texture(GlowSampler, texCoord).r;

    float maskValue = mix(sharp, soft, softness);
    float coverage = smoothstep(edgeLo, edgeHi, maskValue);

    float ghost = 0.0;
    if (trailEnabled > 0.5) {
        float trailMask = texture(TrailSampler, texCoord).r;
        ghost = clamp(max(0.0, trailMask - sharp) * trailStrength, 0.0, 1.0);
    }


    float outline = 0.0;
    if (outlineIntensity > 0.002 && outlineWidth > 0.0 && sharp < 0.65) {
        float inside = smoothstep(0.35, 0.65, sharp);
        float limit = min(outlineWidth, 16.0);

        float nearestSq = 4096.0;
        for (int y = -16; y <= 16; y++) {
            float fy = float(y);
            if (abs(fy) > limit) continue;
            float dx = texture(RowSampler, texCoord + vec2(0.0, fy * maskTexel.y)).r * 16.0;
            if (dx > limit) continue;
            nearestSq = min(nearestSq, dx * dx + fy * fy);
        }
        float nearest = sqrt(nearestSq);

        float feather = clamp(outlineSoftness, 0.02, 1.0) * 1.5;
        float dilated = 1.0 - smoothstep(limit - feather, limit, nearest);
        float ring = max(0.0, dilated - inside);
        outline = ring * outlineIntensity;
    }

    if (coverage < 0.002 && outline < 0.002 && ghost < 0.002) discard;

    vec3 colA = overlayA.rgb;
    vec3 colB = overlayB.rgb;
    vec3 colC = overlayC.rgb;

    vec3 colour = texture(BlurSampler, texCoord).rgb;

    float luma = dot(colour, vec3(0.2126, 0.7152, 0.0722));
    colour = mix(vec3(luma), colour, saturation);

    if (tintEnabled > 0.5) colour = mix(colour, tintColor.rgb, tintColor.a);

    float alpha = coverage * opacity * bodyEnabled;

    if (overlay == 1) {
        vec3 liqA = customColors > 0.5 ? colA : vec3(0.42, 0.17, 0.63);
        vec3 liqB = customColors > 0.5 ? colB : vec3(0.12, 0.25, 0.62);
        vec3 liqC = customColors > 0.5 ? colC : vec3(1.0, 0.88, 0.96);
        colour = mix(colour, liquid(texCoord, time, liqA, liqB, liqC), overlayStrength);
        alpha = max(alpha, coverage * opacity * overlayStrength);
    } else if (overlay == 2) {
        vec3 portal = endPortal(texCoord, time / 1200.0, portalLayers);
        if (customColors > 0.5) {
            float energy = clamp(dot(portal, vec3(0.9, 1.1, 1.3)) * 2.4, 0.0, 1.0);
            vec3 graded = mix(colA * 0.35, colB, smoothstep(0.0, 0.55, energy));
            graded = mix(graded, colC, smoothstep(0.55, 1.0, energy));
            portal = graded * (0.45 + energy * 0.9);
        }
        colour = mix(colour, portal, overlayStrength);
        alpha = max(alpha, coverage * opacity * overlayStrength);
    } else if (overlay == 3) {
        vec3 oilA = customColors > 0.5 ? colA : vec3(0.55, 0.62, 1.0);
        vec3 oilB = customColors > 0.5 ? colB : vec3(1.0, 0.72, 0.45);
        vec3 oilC = customColors > 0.5 ? colC : vec3(1.0, 1.0, 1.0);
        colour = mix(colour, oilSlick(texCoord, time, oilA, oilB, oilC), overlayStrength);
        alpha = max(alpha, coverage * opacity * overlayStrength);
    } else if (overlay == 4) {
        vec3 holoA = customColors > 0.5 ? colA : vec3(0.35, 0.85, 1.0);
        vec3 holoB = customColors > 0.5 ? colB : vec3(0.55, 0.45, 1.0);
        vec3 holoC = customColors > 0.5 ? colC : vec3(0.85, 1.0, 1.0);
        colour = mix(colour, hologram(texCoord, time, holoA, holoB, holoC, luma), overlayStrength);
        alpha = max(alpha, coverage * opacity * overlayStrength);
    } else if (overlay == 5) {
        vec3 spaceA = customColors > 0.5 ? colA : vec3(0.28, 0.16, 0.55);
        vec3 spaceB = customColors > 0.5 ? colB : vec3(0.12, 0.42, 0.78);
        vec3 spaceC = customColors > 0.5 ? colC : vec3(1.0, 0.98, 0.92);
        colour = mix(colour, space(texCoord, time, spaceA, spaceB, spaceC), overlayStrength);
        alpha = max(alpha, coverage * opacity * overlayStrength);
    } else if (overlay == 6) {
        vec3 starFill = customColors > 0.5 ? colA : vec3(0.62, 0.45, 1.0);
        colour = mix(colour, stars(gl_FragCoord.xy, time, colour, starFill), overlayStrength);
        alpha = max(alpha, coverage * opacity * overlayStrength);
    }

    if (ghost > 0.002) {
        vec3 ghostColour = trailTinted > 0.5 ? trailColor.rgb : colour;
        colour = mix(colour, ghostColour, clamp(ghost, 0.0, 1.0));
        alpha = max(alpha, ghost * trailColor.a);
    }

    if (outline > 0.002) {
        colour = mix(colour, outlineColor.rgb, clamp(outline, 0.0, 1.0));
        alpha = max(alpha, clamp(outline, 0.0, 1.0) * outlineColor.a);
    }

    if (alpha < 0.002) discard;
    fragColor = vec4(colour, alpha);
}
