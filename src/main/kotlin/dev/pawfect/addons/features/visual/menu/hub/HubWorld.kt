package dev.pawfect.addons.features.visual.menu.hub

import net.minecraft.client.renderer.BiomeColors
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.minecraft.world.level.CardinalLighting
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.lighting.LevelLightEngine
import net.minecraft.world.level.material.FluidState
import java.nio.file.Path

class HubWorld private constructor(
    private val sections: HashMap<Long, Section>,
    private val columns: HashMap<Long, Column>,
) : BlockAndTintGetter {

    private val skyline = HashMap<Long, IntArray>()

    init {
        val tops = HashMap<Long, MutableList<Section>>()
        for (section in sections.values) tops.getOrPut(SectionPos.asLong(section.x, 0, section.z)) { ArrayList() } += section
        for ((key, stack) in tops) {
            stack.sortByDescending { it.y }
            skyline[key] = IntArray(256) { column ->
                val x = column and 15
                val z = column shr 4
                for (section in stack) {
                    for (y in 15 downTo 0) {
                        if (section.state((y shl 8) or (z shl 4) or x).lightDampening > 0) return@IntArray (section.y shl 4) + y
                    }
                }
                Int.MIN_VALUE
            }
        }
    }

    private fun openToSky(pos: BlockPos): Boolean {
        val tops = skyline[SectionPos.asLong(pos.x shr 4, 0, pos.z shr 4)] ?: return true
        return pos.y > tops[((pos.z and 15) shl 4) or (pos.x and 15)]
    }

    class Section(
        val x: Int,
        val y: Int,
        val z: Int,
        val palette: Array<BlockState>,
        private val small: ByteArray?,
        private val large: IntArray?,
        private val sky: ByteArray?,
        private val block: ByteArray?,
    ) {
        fun state(index: Int): BlockState = palette[if (small != null) small[index].toInt() and 0xFF else large!![index]]

        fun sky(index: Int): Int = if (sky == null) 15 else nibble(sky, index)

        fun block(index: Int): Int = if (block == null) 0 else nibble(block, index)

        private fun nibble(data: ByteArray, index: Int): Int = (data[index shr 1].toInt() shr ((index and 1) shl 2)) and 0xF
    }

    private class Column(val grass: IntArray, val foliage: IntArray, val dry: IntArray, val water: IntArray)

    val allSections: Collection<Section> get() = sections.values

    private fun section(x: Int, y: Int, z: Int): Section? = sections[SectionPos.asLong(x shr 4, y shr 4, z shr 4)]

    private fun index(x: Int, y: Int, z: Int): Int = ((y and 15) shl 8) or ((z and 15) shl 4) or (x and 15)

    fun stateAt(x: Int, y: Int, z: Int): BlockState = section(x, y, z)?.state(index(x, y, z)) ?: AIR

    override fun getBlockState(pos: BlockPos): BlockState = stateAt(pos.x, pos.y, pos.z)

    override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState

    override fun getBlockEntity(pos: BlockPos): BlockEntity? = null

    override fun getHeight(): Int = 384

    override fun getMinY(): Int = -64

    override fun cardinalLighting(): CardinalLighting = CardinalLighting.DEFAULT

    override fun getLightEngine(): LevelLightEngine = throw UnsupportedOperationException("the hub scene has baked light")

    override fun getBrightness(layer: LightLayer, pos: BlockPos): Int {
        val section = section(pos.x, pos.y, pos.z) ?: return if (layer == LightLayer.SKY) 15 else 0
        val at = index(pos.x, pos.y, pos.z)
        if (layer != LightLayer.SKY) return section.block(at)
        return if (openToSky(pos)) 15 else section.sky(at)
    }

    override fun getRawBrightness(pos: BlockPos, darkening: Int): Int =
        maxOf(getBrightness(LightLayer.SKY, pos) - darkening, getBrightness(LightLayer.BLOCK, pos))

    override fun canSeeSky(pos: BlockPos): Boolean = getBrightness(LightLayer.SKY, pos) >= 15

    override fun getBlockTint(pos: BlockPos, resolver: ColorResolver): Int {
        val column = columns[SectionPos.asLong(pos.x shr 4, 0, pos.z shr 4)] ?: return DEFAULT_TINT
        val at = ((pos.z and 15) shl 4) or (pos.x and 15)
        return when (resolver) {
            BiomeColors.GRASS_COLOR_RESOLVER -> column.grass[at]
            BiomeColors.FOLIAGE_COLOR_RESOLVER -> column.foliage[at]
            BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER -> column.dry[at]
            BiomeColors.WATER_COLOR_RESOLVER -> column.water[at]
            else -> DEFAULT_TINT
        }
    }

    companion object {
        private val AIR: BlockState = Blocks.AIR.defaultBlockState()
        private const val DEFAULT_TINT = 0xFFFFFF

        fun load(path: Path): HubWorld? {
            val root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap())
            if (root.getIntOr("version", 0) != HubScene.VERSION) return null
            val blocks = BuiltInRegistries.BLOCK
            val sections = HashMap<Long, Section>()
            root.getListOrEmpty("sections").forEach { element ->
                val tag = element as? CompoundTag ?: return@forEach
                val palette = tag.getListOrEmpty("palette").map { entry ->
                    (entry as? CompoundTag)?.let { NbtUtils.readBlockState(blocks, it) } ?: AIR
                }.toTypedArray()
                if (palette.isEmpty()) return@forEach
                val x = tag.getIntOr("x", 0)
                val y = tag.getIntOr("y", 0)
                val z = tag.getIntOr("z", 0)
                val small = tag.getByteArray("blocks").orElse(null)?.takeIf { it.size == 4096 }
                val large = if (small == null) tag.getIntArray("blocks").orElse(null)?.takeIf { it.size == 4096 } else null
                if (small == null && large == null) return@forEach
                sections[SectionPos.asLong(x, y, z)] = Section(
                    x,
                    y,
                    z,
                    palette,
                    small,
                    large,
                    tag.getByteArray("sky").orElse(null)?.takeIf { it.size == 2048 },
                    tag.getByteArray("light").orElse(null)?.takeIf { it.size == 2048 },
                )
            }
            val columns = HashMap<Long, Column>()
            root.getListOrEmpty("columns").forEach { element ->
                val tag = element as? CompoundTag ?: return@forEach
                fun ints(key: String): IntArray = tag.getIntArray(key).orElse(null)?.takeIf { it.size == 256 } ?: IntArray(256) { DEFAULT_TINT }
                columns[SectionPos.asLong(tag.getIntOr("x", 0), 0, tag.getIntOr("z", 0))] =
                    Column(ints("grass"), ints("foliage"), ints("dry_foliage"), ints("water"))
            }
            return HubWorld(sections, columns)
        }
    }
}
