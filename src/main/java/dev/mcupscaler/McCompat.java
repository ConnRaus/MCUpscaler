package dev.mcupscaler;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.mcupscaler.mixin.CommandEncoderAccessor;
import dev.mcupscaler.mixin.GpuDeviceAccessor;
import net.minecraft.client.renderer.DebugCrosshairRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jspecify.annotations.Nullable;

/** Reaching Minecraft's Vulkan backend, and a few render helpers. */
final class McCompat {
	private McCompat() {
	}

	/** Minecraft's Vulkan device, or null when it runs another graphics backend. */
	static @Nullable VulkanDevice vulkanDevice(GpuDevice device) {
		return device instanceof FrontendGpuDevice frontend
			&& ((GpuDeviceAccessor)frontend).mcupscaler$getBackend() instanceof VulkanDevice vk ? vk : null;
	}

	/** Minecraft's Vulkan command encoder, or null; with {@code outsideRenderPass}, also null inside a render pass. */
	static @Nullable VulkanCommandEncoder vulkanEncoder(boolean outsideRenderPass) {
		if (!(RenderSystem.getDevice().createCommandEncoder() instanceof FrontendCommandEncoder frontend)) {
			return null;
		}
		CommandEncoderAccessor accessor = (CommandEncoderAccessor)frontend;
		if (outsideRenderPass && accessor.mcupscaler$isInRenderPass()) {
			return null;
		}
		return accessor.mcupscaler$backend() instanceof VulkanCommandEncoder encoder ? encoder : null;
	}

	/** An RGBA8 render target with a depth buffer. */
	static TextureTarget colorDepthTarget(String name, int width, int height) {
		return new TextureTarget(name, width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
	}

	/** Sets up {@code pass} to draw {@code source}, bilinear filtered, with a fullscreen triangle. */
	static void setupBlit(RenderPass pass, GpuTextureView source) {
		pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("InSampler", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
	}

	/** The F3 axis crosshair, drawn into {@code color} with its own cleared {@code depth}. */
	static void renderCrosshair(DebugCrosshairRenderer renderer, CameraRenderState camera, int guiScale, GpuTextureView color,
		GpuTextureView depth) {
		renderer.render(camera, guiScale, color, depth);
	}
}
