package dev.mcupscaler.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mcupscaler.DlssNative;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Serializes Minecraft's queue submits with the frame generation present thread. */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanQueue$Submission")
public class VulkanQueueSubmissionMixin {
	@WrapOperation(method = "close", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"))
	private int mcupscaler$lockedSubmit(VkQueue queue, VkSubmitInfo2.Buffer submits, long fence, Operation<Integer> original) {
		if (!DlssNative.isLoaded()) {
			return original.call(queue, submits, fence);
		}
		DlssNative.queueLock();
		try {
			return original.call(queue, submits, fence);
		} finally {
			DlssNative.queueUnlock();
		}
	}
}
