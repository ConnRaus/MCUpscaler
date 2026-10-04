package dev.dlssmc.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The command buffer Minecraft is recording (begun on demand), so DLSS can be recorded between its commands. */
@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
	@Invoker("commandBuffer")
	VkCommandBuffer dlssmc$commandBuffer();
}
