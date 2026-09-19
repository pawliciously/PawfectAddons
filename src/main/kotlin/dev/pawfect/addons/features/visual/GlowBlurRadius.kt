package dev.pawfect.addons.features.visual

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import dev.pawfect.addons.PawfectAddons
import dev.pawfect.addons.config.ConfigManager
import net.minecraft.client.renderer.PostPass
import net.minecraft.client.renderer.UniformValue
import net.minecraft.resources.Identifier
import org.joml.Vector2f
import org.lwjgl.system.MemoryStack
import java.util.IdentityHashMap

object GlowBlurRadius {

    private const val MIN = 2f
    private const val MAX = 32f

    private val SHADER = Identifier.fromNamespaceAndPath(PawfectAddons.MOD_ID, "post/glow_blur")
    private val DIRECTIONS = IdentityHashMap<PostPass, Vector2f>()

    private fun current(): Float = ConfigManager.features.visuals.starRadius.coerceIn(MIN, MAX)

    @JvmStatic
    fun register(pass: PostPass, pipeline: RenderPipeline?, uniforms: Map<String, List<UniformValue>>?) {
        if (pipeline == null || SHADER != pipeline.fragmentShader || uniforms == null) return
        val dir = Vector2f(1f, 0f)
        uniforms["BlurConfig"]?.forEach { if (it is UniformValue.Vec2Uniform) dir.set(it.value()) }
        DIRECTIONS[pass] = dir
    }

    @JvmStatic
    fun unregister(pass: PostPass) {
        DIRECTIONS.remove(pass)
    }

    @JvmStatic
    fun apply(pass: PostPass, buffers: Map<String, GpuBuffer>?) {
        val dir = DIRECTIONS[pass] ?: return
        val buffer = buffers?.get("BlurConfig") ?: return
        if (buffer.isClosed) return
        val size = Std140SizeCalculator().putVec2().putFloat().get()
        if (buffer.size() < size) return
        MemoryStack.stackPush().use { stack ->
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
                buffer.slice(),
                Std140Builder.onStack(stack, size).putVec2(dir.x, dir.y).putFloat(current()).get(),
            )
        }
    }
}
