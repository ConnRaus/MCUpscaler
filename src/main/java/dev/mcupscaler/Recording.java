package dev.mcupscaler;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.mcupscaler.mixin.VulkanCommandEncoderAccessor;
import java.lang.foreign.MemorySegment;
import org.jspecify.annotations.Nullable;

/** Access to what Minecraft is recording, for the native passes that are recorded in between its own commands. */
final class Recording {
	private Recording() {
	}

	/** Minecraft's current Vulkan command buffer, or 0 if it can't take commands right now (inside a render pass). */
	static long commandBuffer() {
		VulkanCommandEncoder encoder = McCompat.vulkanEncoder(true);
		if (encoder == null) {
			return 0L;
		}
		return ((VulkanCommandEncoderAccessor)encoder).mcupscaler$commandBuffer().address();
	}

	/** {@link DlssNative#writeTex} for a texture that has no view of its own. */
	static boolean writeTex(MemorySegment segment, long offset, @Nullable GpuTexture texture) {
		return texture != null && DlssNative.writeTex(segment, offset, texture, TextureViews.of(texture));
	}
}
