package dev.mcupscaler.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import dev.mcupscaler.FrameGen;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Creates one more queue in Minecraft's graphics queue family for frame generation to present on: presents on Minecraft's
 * own queue wait behind the next frame's rendering (so both frames would show at once), and presents from a compute-only
 * queue are very slow on NVIDIA's driver.
 */
@Mixin(VulkanBackend.class)
public class VulkanBackendMixin {
	@WrapOperation(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;queueFamilyCreateInfoMap()Lit/unimi/dsi/fastutil/ints/Int2IntMap;"))
	private static Int2IntMap mcupscaler$addPresentQueue(VulkanPhysicalDevice physicalDevice, Operation<Int2IntMap> original) {
		Int2IntMap queues = original.call(physicalDevice);
		IntIntPair graphics = physicalDevice.graphicsQueueFamilyAndIndex();
		if (graphics == null) {
			return queues;
		}
		int family = graphics.leftInt();
		int used = queues.get(family);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer count = stack.callocInt(1);
			VK12.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), count, null);
			VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
			VK12.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), count, families);
			if (family >= count.get(0) || families.get(family).queueCount() <= used) {
				return queues;
			}
		}
		Int2IntMap more = new Int2IntArrayMap(queues);
		more.put(family, used + 1);
		FrameGen.presentQueueCreated(family, used);
		return more;
	}
}
