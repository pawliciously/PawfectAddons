#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec4 Color;
in vec4 Local;
in vec4 Shape;
in vec4 BorderColor;

out vec4 vertexColor;
out vec4 borderColor;
out vec4 local;
flat out vec4 shape;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
    borderColor = BorderColor;
    local = Local;
    shape = Shape;
}
