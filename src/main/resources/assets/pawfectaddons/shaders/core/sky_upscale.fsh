#version 330

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D SkySampler;

void main() {
    fragColor = vec4(texture(SkySampler, texCoord).rgb, 1.0);
}
