#version 150

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D DepthSampler;

layout(std140) uniform MaskData {
    vec4 maskParams;
};

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    float result = depth < maskParams.x ? 1.0 : 0.0;
    fragColor = vec4(result, result, result, 1.0);
}
