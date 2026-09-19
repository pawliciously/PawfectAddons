#version 150

in vec2 texCoord;
in vec2 texelSize;
in float offset;

out vec4 fragColor;

uniform sampler2D Sampler0;

void main() {
    vec2 hp = texelSize * 0.5 * offset;
    vec2 lo = vec2(0.002);
    vec2 hi = vec2(0.998);

    vec4 sum = texture(Sampler0, clamp(texCoord + vec2(-hp.x * 2.0, 0.0), lo, hi));
    sum += texture(Sampler0, clamp(texCoord + vec2(-hp.x, hp.y), lo, hi)) * 2.0;
    sum += texture(Sampler0, clamp(texCoord + vec2(0.0, hp.y * 2.0), lo, hi));
    sum += texture(Sampler0, clamp(texCoord + vec2(hp.x, hp.y), lo, hi)) * 2.0;
    sum += texture(Sampler0, clamp(texCoord + vec2(hp.x * 2.0, 0.0), lo, hi));
    sum += texture(Sampler0, clamp(texCoord + vec2(hp.x, -hp.y), lo, hi)) * 2.0;
    sum += texture(Sampler0, clamp(texCoord + vec2(0.0, -hp.y * 2.0), lo, hi));
    sum += texture(Sampler0, clamp(texCoord + vec2(-hp.x, -hp.y), lo, hi)) * 2.0;

    fragColor = sum / 12.0;
}
