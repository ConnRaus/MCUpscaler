package dev.dlssmc.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import dev.dlssmc.DlssNative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The frame generation present thread uses the graphics queue too: Vulkan queue access must be serialized. */
@Mixin(VulkanQueue.class)
public class VulkanQueueMixin {
	@Inject(method = "waitIdle", at = @At("HEAD"))
	private void dlssmc$lock(CallbackInfo ci) {
		if (DlssNative.isLoaded()) {
			DlssNative.queueLock();
		}
	}

	@Inject(method = "waitIdle", at = @At("RETURN"))
	private void dlssmc$unlock(CallbackInfo ci) {
		if (DlssNative.isLoaded()) {
			DlssNative.queueUnlock();
		}
	}
}
