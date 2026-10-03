#version 330

layout(std140) uniform HubScene {
    vec4 CameraRight;
    vec4 CameraUp;
    vec4 CameraForward;
    vec4 FogColor;
    vec4 SkyColor;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec3 dir = normalize(CameraForward.xyz + ndc.x * CameraRight.w * CameraRight.xyz + ndc.y * CameraUp.w * CameraUp.xyz);
    float h = dir.y;
    vec3 colour = mix(FogColor.rgb, SkyColor.rgb, smoothstep(0.0, 0.6, h));
    colour = mix(colour, FogColor.rgb * 0.9, smoothstep(0.0, -0.3, h));
    fragColor = vec4(colour, 1.0);
}
