package dev.pawfect.addons.ui.gpu

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import dev.pawfect.addons.mixin.BufferBuilderAccessor

object UiFormats {

    val SHAPE_FORMAT: VertexFormat = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("Local", GpuFormat.RGBA32_FLOAT)
        .addAttribute("Shape", GpuFormat.RGBA32_FLOAT)
        .addAttribute("BorderColor", GpuFormat.RGBA8_UNORM)
        .build()

    val LOCAL: Int = offset("Local")
    val SHAPE: Int = offset("Shape")
    val BORDER_COLOR: Int = offset("BorderColor")

    private fun offset(name: String): Int = requireNotNull(SHAPE_FORMAT.getElement(name)) { "No $name in the shape format" }.offset()

    /**
     * Where an attribute of the vertex being built sits in memory. The buffer builder only fills
     * vanilla's own attributes, so ours are written straight into the vertex.
     */
    fun pointer(consumer: VertexConsumer, offset: Int): Long {
        val vertex = (consumer as BufferBuilderAccessor).`pawfectaddons$vertexPointer`()
        return if (vertex == -1L) -1L else vertex + offset
    }
}
