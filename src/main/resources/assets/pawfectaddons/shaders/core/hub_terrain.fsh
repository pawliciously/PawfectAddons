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
in float fogDistance;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    float fog = smoothstep(FogColor.a, SkyColor.a, fogDistance);
    float shown = fog + (1.0 - fog) * (1.0 - CameraForward.w);
    fragColor = vec4(mix(color.rgb, FogColor.rgb, shown), color.a);
}
