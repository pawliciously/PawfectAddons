package dev.pawfect.addons.features.visual.menu.hub

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.MeshData
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexSorting
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.renderer.block.BlockModelLighter
import net.minecraft.client.renderer.block.FluidRenderer
import net.minecraft.client.renderer.block.ModelBlockRenderer
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.RenderShape
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.sqrt

class HubMeshPart(
    val layers: Map<ChunkSectionLayer, MeshData>,
    private val buffers: List<ByteBufferBuilder>,
    val distance: Double,
) : AutoCloseable {

    val quads: Int get() = layers.values.sumOf { it.drawState().vertexCount() / 4 }

    override fun close() {
        layers.values.forEach { runCatching { it.close() } }
        buffers.forEach { runCatching { it.close() } }
    }
}

class HubMesh(val originX: Int, val originY: Int, val originZ: Int, val parts: List<HubMeshPart>) : AutoCloseable {

    val quads: Int get() = parts.sumOf { it.quads }

    override fun close() {
        parts.forEach { it.close() }
    }
}

object HubMesher {

    private val logger = LoggerFactory.getLogger("PawfectAddons/HubMesher")

    private const val INITIAL_BYTES = 2 * 1024 * 1024

    fun build(world: HubWorld, originX: Int, originY: Int, originZ: Int, eyeX: Float, eyeY: Float, eyeZ: Float): HubMesh {
        val worldEyeX = originX + eyeX.toDouble()
        val worldEyeY = originY + eyeY.toDouble()
        val worldEyeZ = originZ + eyeZ.toDouble()
        fun distance(section: HubWorld.Section): Double {
            val dx = (section.x shl 4) + 8 - worldEyeX
            val dy = (section.y shl 4) + 8 - worldEyeY
            val dz = (section.z shl 4) + 8 - worldEyeZ
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        val sections = world.allSections.sortedBy(::distance)
        val workers = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 6)
        val groups = if (sections.isEmpty()) emptyList() else sections.chunked(ceil(sections.size / workers.toDouble()).toInt())
        val pool = Executors.newFixedThreadPool(groups.size.coerceAtLeast(1)) { runnable ->
            Thread(runnable, "PawfectAddons Hub Mesher").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
        try {
            val futures = groups.map { group ->
                pool.submit(Callable {
                    val part = buildPart(world, group, originX, originY, originZ, eyeX, eyeY, eyeZ)
                    HubMeshPart(part.first, part.second, group.sumOf(::distance) / group.size)
                })
            }
            return HubMesh(originX, originY, originZ, futures.map { it.get() })
        } finally {
            pool.shutdown()
        }
    }

    private fun buildPart(
        world: HubWorld,
        sections: List<HubWorld.Section>,
        originX: Int,
        originY: Int,
        originZ: Int,
        eyeX: Float,
        eyeY: Float,
        eyeZ: Float,
    ): Pair<Map<ChunkSectionLayer, MeshData>, List<ByteBufferBuilder>> {
        val mc = McCompat.mc
        val models = mc.modelManager.blockStateModelSet
        val blocks = ModelBlockRenderer(true, true, mc.blockColors)
        val fluids = FluidRenderer(mc.modelManager.fluidStateModelSet)

        val buffers = HashMap<ChunkSectionLayer, ByteBufferBuilder>()
        val builders = HashMap<ChunkSectionLayer, BufferBuilder>()
        fun layer(which: ChunkSectionLayer): BufferBuilder = builders.getOrPut(which) {
            val memory = ByteBufferBuilder(INITIAL_BYTES)
            buffers[which] = memory
            BufferBuilder(memory, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK)
        }

        val pos = BlockPos.MutableBlockPos()
        var shiftX = 0f
        var shiftY = 0f
        var shiftZ = 0f
        val fluidOutput = FluidRenderer.Output { which -> Shifted(layer(which), shiftX, shiftY, shiftZ) }

        BlockModelLighter.enableCaching()
        var failures = 0
        try {
            for (section in sections) {
                val baseX = section.x shl 4
                val baseY = section.y shl 4
                val baseZ = section.z shl 4
                shiftX = (baseX - originX).toFloat()
                shiftY = (baseY - originY).toFloat()
                shiftZ = (baseZ - originZ).toFloat()
                for (index in 0 until 4096) {
                    val state = section.state(index)
                    if (state.isAir) continue
                    pos.set(baseX + (index and 15), baseY + (index shr 8), baseZ + ((index shr 4) and 15))
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
        return layers to buffers.values.toList()
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
