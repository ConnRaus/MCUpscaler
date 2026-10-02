package dev.metalfxmc.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.metalfxmc.VulkanMetalInterop;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Asks Minecraft to enable VK_EXT_metal_objects when the device offers it (MoltenVK does), so we can
 * reach the Metal objects behind Vulkan images and semaphores.
 */
@Mixin(VulkanFeatureSets.class)
public class VulkanFeatureSetsMixin {
	@Unique
	private static final FeatureSet METALFX$METAL_OBJECTS = new FeatureSet("MetalFX: Metal objects export", Set.of(VulkanMetalInterop.EXTENSION), Set.of());

	@ModifyReturnValue(method = "optionalFeatureSets", at = @At("RETURN"))
	private static Set<FeatureSet> metalfx$addMetalObjects(Set<FeatureSet> sets) {
		sets.add(METALFX$METAL_OBJECTS);
		return sets;
	}
}
