package dev.mcupscaler.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import dev.mcupscaler.VulkanMetalInterop;
import java.util.Set;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupSizeControlFeaturesEXT;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Asks Minecraft to enable the device extensions and features NVIDIA NGX (DLSS) needs, where the device offers them
 * (NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements; VK_KHR_push_descriptor is already required by Minecraft), and
 * those AMD FSR's shaders use: FSR picks its fp16 and wave64 shader variants from what the GPU supports, not from what
 * the device enabled (ffx_vk.cpp GetDeviceCapabilitiesVK), so they have to be enabled wherever the GPU has them. On macOS,
 * VK_EXT_metal_objects (MoltenVK) reaches the Metal objects behind Vulkan images and semaphores. Each set is only enabled
 * where the device offers it.
 */
@Mixin(VulkanFeatureSets.class)
public class VulkanFeaturesMixin {
	@Unique
	private static final FeatureSet MCUPSCALER$NGX = new FeatureSet(
		"DLSS: NVIDIA NGX",
		Set.of("VK_NVX_binary_import", "VK_NVX_image_view_handle", "VK_KHR_buffer_device_address"),
		Set.of(new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress"))
	);

	/** NVIDIA Reflex: VK_NV_low_latency2, whose frame markers need present IDs. */
	@Unique
	private static final FeatureSet MCUPSCALER$REFLEX = new FeatureSet(
		"DLSS: NVIDIA Reflex",
		Set.of("VK_NV_low_latency2", "VK_KHR_present_id"),
		Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDevicePresentIdFeaturesKHR.class), "presentId"))
	);

	/** FSR's fp16 shader variants: 16-bit floats and integers in shaders and in storage buffers. */
	@Unique
	private static final FeatureSet MCUPSCALER$FSR_FP16 = new FeatureSet(
		"FSR: 16-bit shader types",
		Set.of(),
		Set.of(
			new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderFloat16"),
			new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderInt16"),
			new VulkanFeature(VulkanFeatureSets.VK11_FEATURES_STRUCT, "storageBuffer16BitAccess"),
			new VulkanFeature(VulkanFeatureSets.VK11_FEATURES_STRUCT, "uniformAndStorageBuffer16BitAccess")
		)
	);

	/** FSR's wave64 shader variants (AMD GPUs; NVIDIA's subgroups are always 32 wide). */
	@Unique
	private static final FeatureSet MCUPSCALER$FSR_SUBGROUP_SIZE = new FeatureSet(
		"FSR: subgroup size control",
		Set.of("VK_EXT_subgroup_size_control"),
		Set.of(
			new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceSubgroupSizeControlFeaturesEXT.class), "subgroupSizeControl"),
			new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceSubgroupSizeControlFeaturesEXT.class), "computeFullSubgroups")
		)
	);

	/** macOS: the Metal textures and events behind Vulkan's (MetalBackend). */
	@Unique
	private static final FeatureSet MCUPSCALER$METAL_OBJECTS = new FeatureSet(
		"MetalFX: Metal objects export", Set.of(VulkanMetalInterop.EXTENSION), Set.of()
	);

	@ModifyReturnValue(method = "optionalFeatureSets", at = @At("RETURN"))
	private static Set<FeatureSet> mcupscaler$addNgx(Set<FeatureSet> sets) {
		if (!Boolean.getBoolean("mcupscaler.skipFeatures")) {
			sets.add(MCUPSCALER$NGX);
			sets.add(MCUPSCALER$REFLEX);
			sets.add(MCUPSCALER$FSR_FP16);
			sets.add(MCUPSCALER$FSR_SUBGROUP_SIZE);
			sets.add(MCUPSCALER$METAL_OBJECTS);
		}
		return sets;
	}
}
