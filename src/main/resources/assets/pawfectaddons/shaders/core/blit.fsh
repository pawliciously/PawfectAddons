#version 330

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D ColorSampler;

void main() {
    fragColor = vec4(texture(ColorSampler, texCoord).rgb, 1.0);
}
