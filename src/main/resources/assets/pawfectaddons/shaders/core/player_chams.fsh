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

float lumaOf(vec4 c) {
    return dot(c.rgb, vec3(0.299, 0.587, 0.114));
}

vec3 inkSketch(vec4 tex, float luma, vec3 fill, vec3 accent, vec3 light, float amount, float cover, float gameTime) {
    float t = gameTime * 1200.0;
    float gain = (amount - 0.10) / 1.40;
    float frame = floor(t * 6.0);

    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 cell = texCoord0 * size;
    vec2 wobble = vec2(hash(vec2(frame, 1.3)), hash(vec2(frame, 7.1))) - 0.5;
    vec2 skin = cell + wobble * 0.35;
    float texel = max(fwidth(cell.x), fwidth(cell.y));

    vec3 ink = accent * 0.28;
    vec3 paper = mix(vec3(1.0, 0.97, 0.91), fill, 0.12 + 0.30 * cover);

    float lit = clamp(dot(light, vec3(0.333)) * 1.25, 0.0, 1.0);
    float tone = lit * (0.45 + 0.55 * luma);
    float band = tone > 0.62 ? 1.0 : (tone > 0.32 ? 0.84 : 0.68);
    paper *= band * (0.95 + 0.05 * noise(gl_FragCoord.xy * 0.9));
    vec3 wash = min(tex.rgb * 1.15 + 0.08, vec3(1.0));
    paper *= mix(vec3(1.0), wash, 0.55 + 0.25 * noise(skin * 0.45));

    float density = mix(1.5, 2.5, gain);
    vec2 h = skin * density;
    float jitter = noise(skin * 0.7 + frame * 3.1) * 0.6;
    float aa = max(fwidth(h.x + h.y), 1e-4) * 1.2;
    float d1 = abs(fract(h.x + h.y + jitter) - 0.5) * 2.0;
    float d2 = abs(fract(h.x - h.y + jitter * 1.3) - 0.5) * 2.0;
    float line1 = 1.0 - smoothstep(0.28 - aa, 0.28 + aa, d1);
    float line2 = 1.0 - smoothstep(0.28 - aa, 0.28 + aa, d2);
    float hatch = line1 * (1.0 - smoothstep(0.42, 0.62, tone)) + line2 * (1.0 - smoothstep(0.18, 0.38, tone));

    ivec2 at = ivec2(floor(cell));
    ivec2 limit = ivec2(size) - 1;
    vec2 inside = fract(skin);
    float stroke = 0.11 + 0.04 * noise(skin * 1.3 + frame);
    float lineArt = 0.0;
    for (int side = 0; side < 4; side++) {
        ivec2 step2 = side == 0 ? ivec2(1, 0) : side == 1 ? ivec2(-1, 0) : side == 2 ? ivec2(0, 1) : ivec2(0, -1);
        vec4 next = texelFetch(Sampler0, clamp(at + step2, ivec2(0), limit), 0);
        if (next.a < 0.1 || abs(lumaOf(next) - luma) < 0.12) continue;
        float dist = side == 0 ? 1.0 - inside.x : side == 1 ? inside.x : side == 2 ? 1.0 - inside.y : inside.y;
        lineArt = max(lineArt, 1.0 - smoothstep(stroke - aa, stroke + aa, dist));
    }

    float far = smoothstep(0.35, 0.9, texel * density);
    float marks = mix(max(hatch, lineArt), (1.0 - tone) * 0.45, far);

    float brush = noise(skin * 0.5 + frame * 1.7);
    float outline = smoothstep(0.66 - 0.14 * brush, 0.86 - 0.14 * brush, rimFactor);

    vec3 col = mix(paper, ink, clamp(marks * mix(0.75, 1.0, gain), 0.0, 1.0));
    return mix(col, ink, outline * 0.9);
}

vec3 neon(float luma, vec3 fill, vec3 accent, vec3 light, float amount, float gameTime) {
    float t = gameTime * 1200.0;
    float gain = (amount - 0.10) / 1.40;
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 cell = texCoord0 * size;
    vec2 id = floor(cell);
    vec2 f = fract(cell);

    vec3 hue = mix(fill, accent, smoothstep(0.12, 0.88, luma));
    float seed = hash(id);
    float pulse = 0.55 + 0.45 * sin(t * (1.6 + seed * 2.4) + seed * 31.0);
    float sweep = pow(0.5 + 0.5 * sin(id.y * 0.35 - t * 2.4), 6.0);

    vec2 edge = min(f, 1.0 - f);
    float tile = smoothstep(0.0, 0.16, min(edge.x, edge.y));
    vec2 texelsPerPixel = fwidth(cell);
    float seams = smoothstep(0.35, 0.15, max(texelsPerPixel.x, texelsPerPixel.y));
    tile = mix(1.0, tile, seams);

    float glow = (0.35 + 0.65 * luma) * (0.60 + 0.40 * pulse) + sweep * 0.80;
    vec3 col = hue * glow * mix(0.80, 1.60, gain);
    col += mix(hue, vec3(1.0), 0.5) * sweep * 0.35;
    col = mix(hue * 0.08, col, tile);
    float lit = clamp(dot(light, vec3(0.333)) * 1.4, 0.0, 1.0);
    return col * mix(1.0, lit, 0.25);
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

    float luma = dot(tex.rgb, vec3(0.299, 0.587, 0.114));
    int id = int(style + 0.5);
    vec3 body;
    if (id == 4) {
        body = neon(luma, fill, accent, light, amount, gameTime);
    } else if (id == 3) {
        body = mix(tinted, endPortal(fill, amount, gameTime), cover);
    } else if (id == 2) {
        body = stars(tinted, fill, light, amount, gameTime);
    } else if (id == 1) {
        body = inkSketch(tex, luma, fill, accent, light, amount, cover, gameTime);
    } else {
        body = ghost(albedo, fill, accent, light, amount, cover, gameTime, alpha);
    }

    body += accent * pow(rimFactor, 3.0) * rimStrength;

    fragColor = apply_fog(vec4(body, alpha), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
