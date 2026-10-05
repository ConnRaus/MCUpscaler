package dev.mcupscaler.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.mcupscaler.WorldUpscaler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NVIDIA NGX holds Vulkan objects of Minecraft's device: it is shut down before the device goes away. */
@Mixin(VulkanDevice.class)
public class VulkanDeviceMixin {
	@Inject(method = "close", at = @At("HEAD"))
	private void mcupscaler$shutdownNgx(CallbackInfo ci) {
		WorldUpscaler.shutdown();
	}
}
