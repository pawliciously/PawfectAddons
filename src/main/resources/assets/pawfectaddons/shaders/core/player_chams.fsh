#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:matrix.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec4 texProj0;
in float rimFactor;

out vec4 fragColor;

const int PORTAL_LAYERS = 15;

const vec3[] PORTAL_COLORS = vec3[](
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

const mat4 PORTAL_SCALE_TRANSLATE = mat4(
    0.5, 0.0, 0.0, 0.25,
    0.0, 0.5, 0.0, 0.25,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
);

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.52;
    mat2 m = mat2(0.80, -0.60, 0.60, 0.80);
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = m * p * 2.05;
        a *= 0.52;
    }
    return v;
}

float starLayer(vec2 uv, float t, float threshold) {
    vec2 id = floor(uv);
    float n = hash(id);
    if (n <= threshold) return 0.0;
    vec2 f = fract(uv);
    vec2 pos = 0.18 + 0.64 * vec2(hash(id + vec2(3.1, 1.7)), hash(id + vec2(7.7, 4.2)));
    float d = length(f - pos);
    if (d > 0.22) return 0.0;
    float tw = 0.40 + 0.60 * sin(t * (2.2 + n * 3.5) + n * 40.0);
    float core = smoothstep(0.08, 0.0, d);
    float glow = smoothstep(0.22, 0.0, d) * 0.42;
    float present = smoothstep(threshold, 1.0, n);
    return (core + glow) * tw * present;
}

vec3 recolor(vec3 color, vec3 fill, float amount) {
    float fillLuma = max(dot(fill, vec3(0.2126, 0.7152, 0.0722)), 0.08);
    return mix(color, color * (fill / fillLuma), amount);
}

vec3 endPortal(vec3 fill, float amount, float gameTime) {
    vec3 color = textureProj(Sampler3, texProj0).rgb * PORTAL_COLORS[0];
    for (int i = 0; i < PORTAL_LAYERS; i++) {
        float layer = float(i + 1);
        mat4 translate = mat4(
            1.0, 0.0, 0.0, 17.0 / layer,
            0.0, 1.0, 0.0, (2.0 + layer / 1.5) * (gameTime * 1.5),
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        );
        mat2 rotate = mat2_rotate_z(radians((layer * layer * 4321.0 + layer * 9.0) * 2.0));
        mat2 scale = mat2((4.5 - layer / 4.0) * 2.0);
        mat4 layerMat = mat4(scale * rotate) * translate * PORTAL_SCALE_TRANSLATE;
        color += textureProj(Sampler4, texProj0 * layerMat).rgb * PORTAL_COLORS[i];
    }
    color *= mix(0.70, 1.20, (amount - 0.10) / 1.40);
    return recolor(color, fill, 0.86);
}

vec3 stars(vec3 tinted, vec3 fill, vec3 light, float amount, float gameTime) {
    vec2 screen = gl_FragCoord.xy;
    float t = gameTime * 90.0;
    float far = starLayer(screen * 0.030 + vec2(t * 0.55, -t * 0.22), t, 0.50);
    float mid = starLayer(screen * 0.050 + vec2(-t * 0.90, t * 0.40), t * 1.25, 0.44);
    float near = starLayer(screen * 0.078 + vec2(t * 1.10, t * 0.16), t * 1.65, 0.54);
    float dust = starLayer(screen * 0.118 + vec2(-t * 0.35, t * 0.80), t * 2.10, 0.34);
    float spark = starLayer(screen * 0.168 + vec2(t * 1.40, -t * 0.55), t * 2.55, 0.58);
    float field = far + mid * 0.92 + near * 0.82 + dust * 0.58 + spark * 0.72;
    float nebula = pow(fbm(screen * 0.012 + vec2(t * 0.10, -t * 0.07)), 2.2);
    float density = mix(0.58, 1.0, (amount - 0.10) / 1.40);
    vec3 starCol = mix(fill, vec3(0.96, 0.97, 1.0), 0.72) * light;
    vec3 body = tinted + fill * light * nebula * mix(0.06, 0.22, density);
    body += starCol * field * density;
    body += light * near * near * 0.55 * density;
    return body;
}

vec3 ghost(vec3 albedo, vec3 fill, vec3 accent, vec3 light, float amount, float cover, float gameTime, inout float alpha) {
    float t = gameTime * 600.0;
    vec2 screen = gl_FragCoord.xy;
    float wisp = fbm(screen * 0.026 + vec2(t * 0.35, -t * 1.05));
    float veil = smoothstep(0.28, 0.86, wisp);
    float edge = pow(rimFactor, 1.30);
    float gain = (amount - 0.10) / 1.40;

    vec3 spectral = mix(fill, accent, 0.34) * max(light, vec3(0.30));
    vec3 body = mix(albedo * 0.30, spectral * 0.55, cover);
    body += spectral * edge * mix(0.70, 2.10, gain);
    body += spectral * veil * mix(0.10, 0.34, gain);

    float solid = clamp(0.14 + edge * 1.45 + veil * 0.22, 0.0, 1.0);
    alpha *= mix(1.0, solid, cover);
    return body;
}

vec3 prism(vec3 tinted, vec3 fill, vec3 accent, vec3 light, float amount, float gameTime) {
    float t = gameTime * 1200.0;
    vec2 screen = gl_FragCoord.xy;
    float gain = (amount - 0.10) / 1.40;

    float flow = fbm(screen * 0.017 + vec2(t * 0.07, -t * 0.05));
    float band = rimFactor * 2.60 + flow * 2.20 + t * 0.22;

    vec3 iris = 0.5 + 0.5 * cos(6.2831853 * (band + vec3(0.00, 0.33, 0.67)));
    iris = mix(fill, iris, 0.66);
    iris = mix(iris, accent, pow(rimFactor, 2.20) * 0.55);

    float facet = pow(clamp(1.0 - abs(fract(band * 1.6) - 0.5) * 2.30, 0.0, 1.0), 7.0);
    float sheen = pow(clamp(1.0 - abs(fract(band * 0.7 + 0.35) - 0.5) * 3.40, 0.0, 1.0), 4.0);

    vec3 body = mix(tinted, iris * light * mix(0.95, 1.45, gain), 0.86);
    body += iris * facet * mix(0.30, 0.85, gain);
    body += mix(iris, vec3(1.0), 0.55) * sheen * 0.22;
    return body;
}

vec3 ripple(vec3 tinted, vec3 fill, vec3 accent, vec3 light, float amount, float gameTime) {
    float t = gameTime * 1200.0;
    vec2 screen = gl_FragCoord.xy;
    float gain = (amount - 0.10) / 1.40;

    float warp = fbm(screen * 0.013 + vec2(t * 0.06, -t * 0.04)) * 2.20;
    float wave = sin(screen.y * 0.155 - t * 2.40 + warp);
    float crest = pow(clamp(0.5 + 0.5 * wave, 0.0, 1.0), 8.0);

    float wave2 = sin(screen.y * 0.086 + screen.x * 0.034 + t * 1.35 + warp * 1.40);
    float crest2 = pow(clamp(0.5 + 0.5 * wave2, 0.0, 1.0), 14.0);

    float wave3 = sin(screen.y * 0.290 - t * 3.60 + warp * 0.60);
    float crest3 = pow(clamp(0.5 + 0.5 * wave3, 0.0, 1.0), 22.0);

    vec3 glow = mix(fill, accent, 0.42) * max(light, vec3(0.22));
    vec3 body = mix(tinted, fill * light * 0.62, 0.55);
    body += glow * (crest * 1.00 + crest2 * 0.62) * mix(0.70, 1.90, gain);
    body += mix(glow, vec3(1.0), 0.45) * crest3 * mix(0.35, 1.00, gain);
    body += glow * pow(rimFactor, 2.40) * 0.45;
    return body;
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }

    vec3 fill = ColorModulator.rgb;
    float tintAmount = ColorModulator.a;
    float style = ModelOffset.x;
    float amount = clamp(ModelOffset.y, 0.10, 1.50);
    float gameTime = TextureMat[0][0];
    float opacity = TextureMat[0][1];
    float rimStrength = TextureMat[0][2];
    vec3 accent = TextureMat[1].rgb;

    vec3 light = max(vertexColor.rgb, vec3(0.02));
    vec3 albedo = tex.rgb * light;
    float albedoPeak = max(tex.r, max(tex.g, tex.b));
    albedo = mix(fill * light, albedo, step(0.04, albedoPeak));
    vec3 tinted = mix(albedo, albedo * fill, tintAmount);
    float cover = clamp(tintAmount, 0.08, 1.0);
    float alpha = opacity;

    vec3 body;
    if (style > 4.5) {
        body = ripple(tinted, fill, accent, light, amount, gameTime);
    } else if (style > 2.5) {
        body = mix(tinted, endPortal(fill, amount, gameTime), cover);
    } else if (style > 1.5) {
        body = stars(tinted, fill, light, amount, gameTime);
    } else if (style > 0.5) {
        body = prism(tinted, fill, accent, light, amount, gameTime);
    } else {
        body = ghost(albedo, fill, accent, light, amount, cover, gameTime, alpha);
    }

    body += accent * pow(rimFactor, 3.0) * rimStrength;

    fragColor = apply_fog(vec4(body, alpha), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
