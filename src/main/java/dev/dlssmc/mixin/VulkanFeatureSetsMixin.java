package dev.dlssmc.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import java.util.Set;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Asks Minecraft to enable the device extensions and features NVIDIA NGX (DLSS) needs, where the device offers them
 * (NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements; VK_KHR_push_descriptor is already required by Minecraft).
 */
@Mixin(VulkanFeatureSets.class)
public class VulkanFeatureSetsMixin {
	@Unique
	private static final FeatureSet DLSSMC$NGX = new FeatureSet(
		"DLSS: NVIDIA NGX",
		Set.of("VK_NVX_binary_import", "VK_NVX_image_view_handle", "VK_KHR_buffer_device_address"),
		Set.of(new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress"))
	);

	/** NVIDIA Reflex: VK_NV_low_latency2, whose frame markers need present IDs. */
	@Unique
	private static final FeatureSet DLSSMC$REFLEX = new FeatureSet(
		"DLSS: NVIDIA Reflex",
		Set.of("VK_NV_low_latency2", "VK_KHR_present_id"),
		Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDevicePresentIdFeaturesKHR.class), "presentId"))
	);

	@ModifyReturnValue(method = "optionalFeatureSets", at = @At("RETURN"))
	private static Set<FeatureSet> dlssmc$addNgx(Set<FeatureSet> sets) {
		if (!Boolean.getBoolean("dlssmc.skipFeatures")) {
			sets.add(DLSSMC$NGX);
			sets.add(DLSSMC$REFLEX);
		}
		return sets;
	}
}
