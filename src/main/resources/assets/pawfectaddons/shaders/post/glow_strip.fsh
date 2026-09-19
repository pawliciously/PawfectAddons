#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    if (color.a > 0.92) {
        fragColor = vec4(0.0);
    } else {
        fragColor = vec4(color.rgb * color.a, color.a);
    }
}
