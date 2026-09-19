#version 330

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D MaskSampler;

layout(std140) uniform RowData {
    vec4 params;
};

void main() {
    int reach = int(params.z + 0.5);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(MaskSampler, 0);

    if (texelFetch(MaskSampler, texel, 0).r >= 0.5) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    int best = 17;
    for (int d = 1; d <= 16; d++) {
        if (d > reach || d >= best) break;
        int left = max(texel.x - d, 0);
        int right = min(texel.x + d, size.x - 1);
        if (texelFetch(MaskSampler, ivec2(left, texel.y), 0).r >= 0.5 ||
            texelFetch(MaskSampler, ivec2(right, texel.y), 0).r >= 0.5) {
            best = d;
        }
    }

    fragColor = vec4(float(best) / 16.0, 0.0, 0.0, 1.0);
}
