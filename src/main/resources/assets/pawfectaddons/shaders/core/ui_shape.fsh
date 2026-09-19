#version 330

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

const int MODE_SOLID = 0;
const int MODE_SV_FIELD = 1;
const int MODE_HUE_BAR = 2;

float roundedBox(vec2 point, vec2 halfSize, float radius) {
    vec2 corner = abs(point) - halfSize + radius;
    return min(max(corner.x, corner.y), 0.0) + length(max(corner, 0.0)) - radius;
}

vec3 hueToRgb(float hue) {
    float h = fract(hue) * 6.0;
    vec3 rgb = clamp(vec3(
        abs(h - 3.0) - 1.0,
        2.0 - abs(h - 2.0),
        2.0 - abs(h - 4.0)
    ), 0.0, 1.0);
    return rgb;
}

void main() {
    vec2 halfSize = shape.xy;
    float radius = clamp(shape.z, 0.0, min(halfSize.x, halfSize.y));
    float border = shape.w;
    float softness = local.z;
    int mode = int(local.w + 0.5);

    float distance = roundedBox(local.xy, halfSize, radius);
    float edge = max(fwidth(distance), 1e-5);

    float coverage;
    if (softness > 0.0) {
        coverage = 1.0 - smoothstep(-softness, softness, distance);
        coverage *= coverage;
    } else {
        coverage = clamp(0.5 - distance / edge, 0.0, 1.0);
    }
    if (coverage <= 0.0) discard;

    vec4 colour;
    if (mode == MODE_SV_FIELD) {
        vec2 unit = (local.xy + halfSize) / (halfSize * 2.0);
        vec3 tint = mix(vec3(1.0), hueToRgb(border), unit.x);
        colour = vec4(tint * (1.0 - unit.y), vertexColor.a);
    } else if (mode == MODE_HUE_BAR) {
        float unit = (local.y + halfSize.y) / (halfSize.y * 2.0);
        colour = vec4(hueToRgb(unit), vertexColor.a);
    } else {
        colour = vertexColor;
        if (border > 0.0) {
            float inner = distance + border;
            float fill = clamp(0.5 - inner / edge, 0.0, 1.0);
            colour = mix(borderColor, vertexColor, fill);
        }
    }

    float alpha = colour.a * coverage;
    if (alpha <= 0.0) discard;

    fragColor = vec4(colour.rgb, alpha) * ColorModulator;
}
