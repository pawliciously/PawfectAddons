#version 330

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D ColorSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform MotionData {
    vec4 camRight;
    vec4 camUp;
    vec4 camForward;
    vec4 prevRight;
    vec4 prevUp;
    vec4 prevForward;
    vec4 motion;
    vec4 params;
    vec4 depthInfo;
};

float hash12(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

void main() {
    float aspectTan = camRight.w;
    float tanHalf = camUp.w;

    float strength = params.x;
    int maxSamples = int(params.y + 0.5);
    float maxRadius = params.z;
    float shutter = params.w;

    float near = depthInfo.x;
    float far = depthInfo.y;
    float movement = motion.w;

    vec2 ndc = texCoord * 2.0 - 1.0;
    vec3 dir = normalize(
        camForward.xyz +
        camRight.xyz * (ndc.x * aspectTan) +
        camUp.xyz * (ndc.y * tanHalf)
    );

    vec3 previous = dir;
    if (movement > 0.5) {
        ivec2 texel = ivec2(gl_FragCoord.xy);
        float z = texelFetch(DepthSampler, texel, 0).r;
        z = min(z, texelFetch(DepthSampler, texel + ivec2(1, 0), 0).r);
        z = min(z, texelFetch(DepthSampler, texel + ivec2(-1, 0), 0).r);
        z = min(z, texelFetch(DepthSampler, texel + ivec2(0, 1), 0).r);
        z = min(z, texelFetch(DepthSampler, texel + ivec2(0, -1), 0).r);
        float linear = (near * far) / max(far - z * (far - near), 0.0001);
        previous = normalize(dir * min(linear, far) + motion.xyz);
    }

    float facing = dot(previous, prevForward.xyz);
    if (facing < 0.02) {
        fragColor = vec4(texture(ColorSampler, texCoord).rgb, 1.0);
        return;
    }

    vec2 prevUv = vec2(
        (dot(previous, prevRight.xyz) / facing) / aspectTan,
        (dot(previous, prevUp.xyz) / facing) / tanHalf
    ) * 0.5 + 0.5;

    vec2 velocity = (texCoord - prevUv) * strength * shutter;
    float travel = length(velocity);
    if (travel < 0.0004 || maxSamples < 2) {
        fragColor = vec4(texture(ColorSampler, texCoord).rgb, 1.0);
        return;
    }
    if (travel > maxRadius) {
        velocity *= maxRadius / travel;
        travel = maxRadius;
    }

    int samples = clamp(int(ceil(travel / maxRadius * float(maxSamples))), 4, maxSamples);

    float jitter = hash12(gl_FragCoord.xy) - 0.5;
    vec3 total = vec3(0.0);
    float weight = 0.0;

    for (int i = 0; i < 32; i++) {
        if (i >= samples) break;
        float t = (float(i) + 0.5 + jitter) / float(samples);
        vec2 uv = clamp(texCoord - velocity * t, vec2(0.0005), vec2(0.9995));
        float w = 1.0 - t * 0.35;
        vec3 tap = texture(ColorSampler, uv).rgb;
        total += tap * tap * w;
        weight += w;
    }

    fragColor = vec4(sqrt(total / max(weight, 0.0001)), 1.0);
}
