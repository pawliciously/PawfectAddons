#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
layout(std140) uniform HubScene {
    vec4 CameraRight;
    vec4 CameraUp;
    vec4 CameraForward;
    vec4 FogColor;
    vec4 SkyColor;
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

out vec4 vertexColor;
out vec2 texCoord0;
out float fogDistance;

float lightCurve(float level) {
    float l = clamp(level, 0.0, 15.0) / 15.0;
    return l / (4.0 - 3.0 * l);
}

void main() {
    vec4 view = ModelViewMat * vec4(Position + ModelOffset, 1.0);
    gl_Position = ProjMat * view;
    fogDistance = length(view.xyz);

    float sky = lightCurve(float(UV2.y) / 16.0);
    float block = lightCurve(float(UV2.x) / 16.0);
    vec3 light = max(vec3(sky), vec3(1.0, 0.92, 0.8) * block);
    light = mix(vec3(0.07), vec3(1.0), light);

    vertexColor = vec4(Color.rgb * light, Color.a);
    texCoord0 = UV0;
}
