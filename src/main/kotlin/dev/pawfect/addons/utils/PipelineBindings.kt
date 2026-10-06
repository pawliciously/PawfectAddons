package dev.pawfect.addons.utils

import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType

/** Declares a pipeline's samplers and uniform buffers, by the names its shaders use, as one bind group. */
fun RenderPipeline.Builder.withBindings(
    samplers: List<String> = emptyList(),
    uniforms: List<String> = emptyList(),
): RenderPipeline.Builder = withBindGroupLayout(
    BindGroupLayout.builder().apply {
        uniforms.forEach { withUniform(it, UniformType.UNIFORM_BUFFER) }
        samplers.forEach { withSampler(it) }
    }.build(),
)
