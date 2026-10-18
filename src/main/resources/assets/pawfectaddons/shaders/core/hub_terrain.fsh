#version 330

layout(std140) uniform HubScene {
    vec4 CameraRight;
    vec4 CameraUp;
    vec4 CameraForward;
    vec4 FogColor;
    vec4 SkyColor;
    vec4 MoonDir;
    vec4 Params;
};

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    fragColor = color;
}
