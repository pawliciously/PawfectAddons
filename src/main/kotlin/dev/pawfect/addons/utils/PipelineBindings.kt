package dev.pawfect.addons.utils

import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType

fun RenderPipeline.Builder.withBindings(
    samplers: List<String> = emptyList(),
    uniforms: List<String> = emptyList(),
): RenderPipeline.Builder = withBindGroupLayout(
    BindGroupLayout.builder().apply {
        uniforms.forEach { withUniform(it, UniformType.UNIFORM_BUFFER) }
        samplers.forEach { withSampler(it) }
    }.build(),
)
