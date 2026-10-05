package dev.mcupscaler.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanDebug;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NVIDIA NGX asks for VK_KHR_get_physical_device_properties2 on the instance (core since Vulkan 1.1, but NGX looks the
 * KHR names up): enabled when available, while Minecraft assembles its instance extensions.
 */
@Mixin(VulkanInstance.class)
public class VulkanInstanceMixin {
	@WrapOperation(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;")
	)
	private VulkanDebug mcupscaler$addNgxInstanceExtensions(int level, boolean sync, Set<String> supported, Set<String> enabled, Operation<VulkanDebug> original) {
		if (supported.contains("VK_KHR_get_physical_device_properties2")) {
			enabled.add("VK_KHR_get_physical_device_properties2");
		}
		return original.call(level, sync, supported, enabled);
	}
}
