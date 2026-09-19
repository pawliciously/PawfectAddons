package dev.pawfect.addons.features.visual.playerchams

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import dev.pawfect.addons.mixin.RenderSetupAccessor
import dev.pawfect.addons.mixin.RenderTypeAccessor
import dev.pawfect.addons.mixin.RenderTypeInvoker
import dev.pawfect.addons.mixin.TextureBindingAccessor
import dev.pawfect.addons.utils.McCompat
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Player
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f
import org.joml.Vector3fc
import org.joml.Vector4f
import org.joml.Vector4fc
import java.util.IdentityHashMap

object PlayerChams {

    private val config get() = ConfigManager.features.playerChams

    private val END_SKY = Identifier.withDefaultNamespace("textures/environment/end_sky.png")
    private val END_PORTAL = Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png")
    private const val TIME_WRAP_NANOS = 3_600_000_000_000L

    private var pipelineReady = false

    val PIPELINE: RenderPipeline by lazy {
        pipelineReady = true
        RenderPipelines.register(
            RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "pipeline/player_chams"))
                .withVertexShader(Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "core/player_chams"))
                .withFragmentShader(Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "core/player_chams"))
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("Fog", UniformType.UNIFORM_BUFFER)
                .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                .withUniform("Lighting", UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0")
                .withSampler("Sampler2")
                .withSampler("Sampler3")
                .withSampler("Sampler4")
                .withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                .build(),
        )
    }

    private val sourcePipelines: Set<RenderPipeline> by lazy {
        setOf(
            RenderPipelines.ENTITY_SOLID,
            RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD,
            RenderPipelines.ENTITY_CUTOUT,
            RenderPipelines.ENTITY_CUTOUT_CULL,
            RenderPipelines.ENTITY_CUTOUT_Z_OFFSET,
            RenderPipelines.ENTITY_TRANSLUCENT,
            RenderPipelines.ENTITY_TRANSLUCENT_CULL,
            RenderPipelines.ARMOR_CUTOUT_NO_CULL,
            RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL,
            RenderPipelines.ARMOR_TRANSLUCENT,
        )
    }

    private val byTexture = HashMap<Identifier, RenderType>()
    private val bySource = IdentityHashMap<RenderType, RenderType>()
    private val passthrough = IdentityHashMap<RenderType, Boolean>()

    private val color = Vector4f()
    private val offset = Vector3f()
    private val params = Matrix4f()
    private val startNanos = System.nanoTime()

    private var filling = false

    @JvmStatic
    fun begin(state: LivingEntityRenderState) {
        filling = shouldFill(state)
        if (filling) updateParams()
    }

    @JvmStatic
    fun end() {
        filling = false
    }

    @JvmStatic
    fun wrap(original: RenderType): RenderType {
        if (!filling) return original
        bySource[original]?.let { return it }
        if (passthrough.containsKey(original)) return original
        val wrapped = texture(original)?.let { swapped(it) }
        if (wrapped == null) passthrough[original] = true else bySource[original] = wrapped
        return wrapped ?: original
    }

    @JvmStatic
    fun isChams(type: RenderType): Boolean = pipelineReady && type.pipeline() === PIPELINE

    @JvmStatic
    fun color(original: Vector4fc): Vector4fc = color

    @JvmStatic
    fun offset(original: Vector3fc): Vector3fc = offset

    @JvmStatic
    fun params(original: Matrix4fc): Matrix4fc = params

    private fun shouldFill(state: LivingEntityRenderState): Boolean {
        val cfg = config
        if (!cfg.enabled || state !is AvatarRenderState || state.isInvisible) return false
        val local = McCompat.player ?: return false
        if (state.id == local.id) return cfg.self
        if (!cfg.others) return false
        if (!cfg.ignoreNpcs) return true
        val entity = McCompat.mc.level?.getEntity(state.id) as? Player ?: return false
        return entity.uuid.version() == 4
    }

    private fun updateParams() {
        val cfg = config
        cfg.sanitize()
        val fill = cfg.color
        color.set(
            (fill shr 16 and 0xFF) / 255f,
            (fill shr 8 and 0xFF) / 255f,
            (fill and 0xFF) / 255f,
            cfg.tint.coerceIn(0f, 1f),
        )
        offset.set(cfg.style.id.toFloat(), cfg.intensity.coerceIn(0.1f, 1.5f), 0f)
        val elapsed = ((System.nanoTime() - startNanos) % TIME_WRAP_NANOS) / 1_000_000_000.0
        val accent = cfg.accentColor
        params.identity()
            .m00((elapsed / 1200.0 * cfg.speed).toFloat())
            .m01(cfg.opacity.coerceIn(0f, 1f))
            .m02(cfg.rim.coerceIn(0f, 2f))
            .m10((accent shr 16 and 0xFF) / 255f)
            .m11((accent shr 8 and 0xFF) / 255f)
            .m12((accent and 0xFF) / 255f)
    }

    private fun texture(type: RenderType): Identifier? {
        if (type.isOutline || type.pipeline() !in sourcePipelines) return null
        val setup = (type as RenderTypeAccessor).`pawfectaddons$state`()
        val binding = ((setup as Any) as RenderSetupAccessor).`pawfectaddons$textures`()["Sampler0"] ?: return null
        return (binding as TextureBindingAccessor).`pawfectaddons$location`()
    }

    private fun swapped(texture: Identifier): RenderType = byTexture.getOrPut(texture) {
        val repeat = { RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR) }
        val setup = RenderSetup.builder(PIPELINE)
            .withTexture("Sampler0", texture)
            .withTexture("Sampler3", END_SKY, repeat)
            .withTexture("Sampler4", END_PORTAL, repeat)
            .useLightmap()
            .affectsCrumbling()
            .sortOnUpload()
            .setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
            .createRenderSetup()
        RenderTypeInvoker.`pawfectaddons$create`("pawfect_player_chams", setup)
    }
}
