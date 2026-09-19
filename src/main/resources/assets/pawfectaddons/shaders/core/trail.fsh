#version 150

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D MaskSampler;
uniform sampler2D HistorySampler;

layout(std140) uniform TrailData {
    vec4 trailParams;
};

void main() {
    float decay = trailParams.x;
    float current = texture(MaskSampler, texCoord).r;
    float history = texture(HistorySampler, texCoord).r * decay;
    float value = max(current, history);
    if (value < 0.004) value = 0.0;
    fragColor = vec4(value, value, value, 1.0);
}
