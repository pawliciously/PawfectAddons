package dev.pawfect.addons.features.visual.menu.hub

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.MeshData
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.renderer.block.BlockModelLighter
import net.minecraft.client.renderer.block.FluidRenderer
import net.minecraft.client.renderer.block.ModelBlockRenderer
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.RenderShape
import org.slf4j.LoggerFactory

class HubMesh(
    val originX: Int,
    val originY: Int,
    val originZ: Int,
    val layers: Map<ChunkSectionLayer, MeshData>,
    private val buffers: List<ByteBufferBuilder>,
) : AutoCloseable {

    val quads: Int get() = layers.values.sumOf { it.drawState().vertexCount() / 4 }

    override fun close() {
        layers.values.forEach { runCatching { it.close() } }
        buffers.forEach { runCatching { it.close() } }
    }
}

object HubMesher {

    private val logger = LoggerFactory.getLogger("PawfectAddons/HubMesher")

    private const val INITIAL_BYTES = 4 * 1024 * 1024

    /** Runs Minecraft's own block and fluid renderers over every captured block. */
    fun build(world: HubWorld, originX: Int, originY: Int, originZ: Int, eyeX: Float, eyeY: Float, eyeZ: Float): HubMesh {
        val mc = McCompat.mc
        val models = mc.modelManager.blockStateModelSet
        val blocks = ModelBlockRenderer(true, true, mc.blockColors)
        val fluids = FluidRenderer(mc.modelManager.fluidStateModelSet)

        val buffers = HashMap<ChunkSectionLayer, ByteBufferBuilder>()
        val builders = HashMap<ChunkSectionLayer, BufferBuilder>()
        fun layer(which: ChunkSectionLayer): BufferBuilder = builders.getOrPut(which) {
            val memory = ByteBufferBuilder(INITIAL_BYTES)
            buffers[which] = memory
            BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
        }

        val pos = BlockPos.MutableBlockPos()
        var shiftX = 0f
        var shiftY = 0f
        var shiftZ = 0f
        val fluidOutput = FluidRenderer.Output { which -> Shifted(layer(which), shiftX, shiftY, shiftZ) }

        BlockModelLighter.enableCaching()
        var failures = 0
        try {
            for (section in world.allSections) {
                val baseX = section.x shl 4
                val baseY = section.y shl 4
                val baseZ = section.z shl 4
                shiftX = (baseX - originX).toFloat()
                shiftY = (baseY - originY).toFloat()
                shiftZ = (baseZ - originZ).toFloat()
                for (index in 0 until 4096) {
                    val state = section.state(index)
                    if (state.isAir) continue
                    val lx = index and 15
                    val lz = (index shr 4) and 15
                    val ly = index shr 8
                    pos.set(baseX + lx, baseY + ly, baseZ + lz)
                    try {
                        val fluid = state.fluidState
                        if (!fluid.isEmpty) {
                            fluids.tesselate(world, pos, fluidOutput, state, fluid)
                        }
                        if (state.renderShape == RenderShape.MODEL) {
                            val opaque = ModelBlockRenderer.forceOpaque(true, state)
                            blocks.tesselateBlock(
                                { x, y, z, quad, instance ->
                                    val which = if (opaque) ChunkSectionLayer.SOLID else quad.materialInfo().layer()
                                    layer(which).putBlockBakedQuad(x, y, z, quad, instance)
                                },
                                (pos.x - originX).toFloat(),
                                (pos.y - originY).toFloat(),
                                (pos.z - originZ).toFloat(),
                                world,
                                pos,
                                state,
                                models.get(state),
                                state.getSeed(pos),
                            )
                        }
                    } catch (error: Exception) {
                        if (failures++ < 5) logger.warn("Skipped {} at {} while building the hub scene.", state, pos, error)
                    }
                }
            }
        } finally {
            BlockModelLighter.clearCache()
        }

        val layers = LinkedHashMap<ChunkSectionLayer, MeshData>()
        for ((which, builder) in builders) {
            val mesh = builder.build() ?: continue
            if (which == ChunkSectionLayer.TRANSLUCENT) {
                mesh.sortQuads(buffers.getValue(which), VertexSorting.byDistance(eyeX, eyeY, eyeZ))
            }
            layers[which] = mesh
        }
        return HubMesh(originX, originY, originZ, layers, buffers.values.toList())
    }

    private class Shifted(
        private val inner: VertexConsumer,
        private val dx: Float,
        private val dy: Float,
        private val dz: Float,
    ) : VertexConsumer by inner {
        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer = inner.addVertex(x + dx, y + dy, z + dz)
    }
}
