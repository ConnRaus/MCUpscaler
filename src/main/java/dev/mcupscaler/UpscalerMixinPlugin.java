package dev.mcupscaler;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Leaves out the mixins only the Windows side uses (frame generation's swapchain takeover and present queue, NGX's
 * instance extension) on other systems, where they would only change Minecraft's Vulkan setup for nothing.
 */
public final class UpscalerMixinPlugin implements IMixinConfigPlugin {
	private static final Set<String> WINDOWS_ONLY = Set.of("VulkanBackendMixin", "VulkanGpuSurfaceMixin", "VulkanQueueMixin",
		"VulkanQueueSubmissionMixin", "VulkanInstanceMixin");

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return Platform.WINDOWS || !WINDOWS_ONLY.contains(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
