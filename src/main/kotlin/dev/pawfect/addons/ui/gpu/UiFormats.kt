package dev.pawfect.addons.ui.gpu

import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement

object UiFormats {

    val LOCAL: VertexFormatElement =
        VertexFormatElement.register(20, 0, VertexFormatElement.Type.FLOAT, false, 4)

    val SHAPE: VertexFormatElement =
        VertexFormatElement.register(21, 0, VertexFormatElement.Type.FLOAT, false, 4)

    val BORDER_COLOR: VertexFormatElement =
        VertexFormatElement.register(22, 0, VertexFormatElement.Type.UBYTE, true, 4)

    val SHAPE_FORMAT: VertexFormat = VertexFormat.builder()
        .add("Position", VertexFormatElement.POSITION)
        .add("Color", VertexFormatElement.COLOR)
        .add("Local", LOCAL)
        .add("Shape", SHAPE)
        .add("BorderColor", BORDER_COLOR)
        .build()
}
