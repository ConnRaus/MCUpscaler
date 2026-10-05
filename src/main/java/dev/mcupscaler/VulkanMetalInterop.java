package dev.mcupscaler;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMetalObjects;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMetalDeviceInfoEXT;
import org.lwjgl.vulkan.VkExportMetalObjectCreateInfoEXT;
import org.lwjgl.vulkan.VkExportMetalObjectsInfoEXT;
import org.lwjgl.vulkan.VkExportMetalSharedEventInfoEXT;
import org.lwjgl.vulkan.VkExportMetalTextureInfoEXT;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;

/**
 * Pulls the Metal objects that back MoltenVK's Vulkan objects (VK_EXT_metal_objects).
 */
public final class VulkanMetalInterop {
	public static final String EXTENSION = EXTMetalObjects.VK_EXT_METAL_OBJECTS_EXTENSION_NAME;

	private VulkanMetalInterop() {
	}

	public static boolean isAvailable(VkDevice device) {
		return device.getCapabilities().VK_EXT_metal_objects;
	}

	public static long mtlDevice(VkDevice device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkExportMetalDeviceInfoEXT info = VkExportMetalDeviceInfoEXT.calloc(stack).sType$Default();
			VkExportMetalObjectsInfoEXT export = VkExportMetalObjectsInfoEXT.calloc(stack).sType$Default().pNext(info.address());
			EXTMetalObjects.vkExportMetalObjectsEXT(device, export);
			return info.mtlDevice();
		}
	}

	public static long mtlTexture(VkDevice device, long vkImage) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkExportMetalTextureInfoEXT info = VkExportMetalTextureInfoEXT.calloc(stack).sType$Default().image(vkImage).plane(VK12.VK_IMAGE_ASPECT_PLANE_0_BIT);
			VkExportMetalObjectsInfoEXT export = VkExportMetalObjectsInfoEXT.calloc(stack).sType$Default().pNext(info.address());
			EXTMetalObjects.vkExportMetalObjectsEXT(device, export);
			return info.mtlTexture();
		}
	}

	/** Creates a timeline semaphore (initial value 0) that can be exported as an MTLSharedEvent. */
	public static long createSharedTimelineSemaphore(VkDevice device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkExportMetalObjectCreateInfoEXT exportInfo = VkExportMetalObjectCreateInfoEXT.calloc(stack)
				.sType$Default()
				.exportObjectType(EXTMetalObjects.VK_EXPORT_METAL_OBJECT_TYPE_METAL_SHARED_EVENT_BIT_EXT);
			VkSemaphoreTypeCreateInfo typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
				.sType$Default()
				.pNext(exportInfo.address())
				.semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
				.initialValue(0L);
			VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(typeInfo.address());
			LongBuffer handle = stack.callocLong(1);
			int result = VK12.vkCreateSemaphore(device, createInfo, null, handle);
			if (result != VK12.VK_SUCCESS) {
				throw new IllegalStateException("vkCreateSemaphore(timeline, MTLSharedEvent export) failed: " + result);
			}
			return handle.get(0);
		}
	}

	public static long mtlSharedEvent(VkDevice device, long semaphore) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkExportMetalSharedEventInfoEXT info = VkExportMetalSharedEventInfoEXT.calloc(stack).sType$Default().semaphore(semaphore);
			VkExportMetalObjectsInfoEXT export = VkExportMetalObjectsInfoEXT.calloc(stack).sType$Default().pNext(info.address());
			EXTMetalObjects.vkExportMetalObjectsEXT(device, export);
			return info.mtlSharedEvent();
		}
	}
}
