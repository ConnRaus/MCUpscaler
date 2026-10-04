package dev.dlssmc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import dev.dlssmc.FrameGen;
import dev.dlssmc.Reflex;
import it.unimi.dsi.fastutil.longs.LongList;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DLSS Frame Generation takes over the swapchain while it runs: acquire, blit and present go to {@link FrameGen}, and
 * the present thread is stopped whenever Minecraft recreates or destroys the swapchain.
 */
@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceMixin implements FrameGen.Surface {
	@Shadow @Final private VulkanDevice device;
	@Shadow @Final private VkQueue presentQueue;
	@Shadow @Final private long surface;
	@Shadow @Final private int swapchainImageFormat;
	@Shadow private long swapchain;
	@Shadow private int swapchainWidth;
	@Shadow private int swapchainHeight;
	@Shadow @Final private LongList swapchainImages;
	@Shadow private boolean swapchainSuboptimal;
	@Shadow private boolean swapchainOutOfDate;

	@Unique private boolean dlssmc$fifo = true;
	@Unique private @Nullable VulkanQueue dlssmc$presentQueue;

	@Inject(method = "configure", at = @At("HEAD"))
	private void dlssmc$stopBeforeConfigure(GpuSurface.Configuration config, CallbackInfo ci) {
		FrameGen.stop();
		Reflex.swapchainDestroyed();
		dlssmc$fifo = config.presentMode() == GpuSurface.PresentMode.FIFO;
	}

	@WrapOperation(method = "configure", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int dlssmc$latencySwapchain(VkDevice vkDevice, VkSwapchainCreateInfoKHR info, @Nullable VkAllocationCallbacks allocator, LongBuffer handle,
		Operation<Integer> original) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Reflex.prepareSwapchain(vkDevice, info, stack);
			return original.call(vkDevice, info, allocator, handle);
		}
	}

	@Inject(method = "configure", at = @At("RETURN"))
	private void dlssmc$afterConfigure(GpuSurface.Configuration config, CallbackInfo ci) {
		Reflex.swapchainCreated(swapchain);
		FrameGen.surfaceConfigured(this);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void dlssmc$stopBeforeClose(CallbackInfo ci) {
		FrameGen.stop();
		Reflex.swapchainDestroyed();
	}

	@Inject(method = "acquireNextTexture", at = @At("HEAD"), cancellable = true)
	private void dlssmc$acquire(CallbackInfo ci) throws SurfaceException {
		if (!swapchainOutOfDate && FrameGen.beginFrame(this)) {
			ci.cancel();
		}
	}

	@Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
	private void dlssmc$blit(CommandEncoderBackend encoder, GpuTextureView view, CallbackInfo ci) {
		if (FrameGen.isRunning()) {
			FrameGen.blit((VulkanCommandEncoder)encoder, view);
			ci.cancel();
		}
	}

	@Inject(method = "present", at = @At("HEAD"), cancellable = true)
	private void dlssmc$present(CallbackInfo ci) {
		Reflex.presentStart();
		if (FrameGen.isRunning()) {
			FrameGen.present();
			Reflex.presentEnd();
			ci.cancel();
		}
	}

	@Inject(method = "present", at = @At("RETURN"))
	private void dlssmc$presented(CallbackInfo ci) {
		Reflex.presentEnd();
	}

	@WrapOperation(method = "present", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int dlssmc$presentId(VkQueue queue, VkPresentInfoKHR info, Operation<Integer> original) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Reflex.tagPresent(info, stack);
			return original.call(queue, info);
		}
	}

	@Override
	public long dlssmc$swapchain() {
		return swapchain;
	}

	@Override
	public long[] dlssmc$swapchainImages() {
		return swapchainImages.toLongArray();
	}

	@Override
	public int dlssmc$width() {
		return swapchainWidth;
	}

	@Override
	public int dlssmc$height() {
		return swapchainHeight;
	}

	/** The extra graphics-family queue (VulkanBackendMixin) if it can present, else Minecraft's graphics queue. */
	@Unique
	private VulkanQueue dlssmc$pickPresentQueue() {
		if (dlssmc$presentQueue == null) {
			dlssmc$presentQueue = device.graphicsQueue();
			int[] extra = FrameGen.presentQueue();
			if (extra != null) {
				try (MemoryStack stack = MemoryStack.stackPush()) {
					IntBuffer supported = stack.callocInt(1);
					if (KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(device.vkDevice().getPhysicalDevice(), extra[0], surface, supported) == 0
						&& supported.get(0) != 0) {
						PointerBuffer handle = stack.callocPointer(1);
						VK12.vkGetDeviceQueue(device.vkDevice(), extra[0], extra[1], handle);
						dlssmc$presentQueue = new VulkanQueue(new VkQueue(handle.get(0), device.vkDevice()), extra[0]);
					}
				}
			}
		}
		return dlssmc$presentQueue;
	}

	@Override
	public long dlssmc$presentQueue() {
		return dlssmc$pickPresentQueue().vkQueue().address();
	}

	@Override
	public int dlssmc$presentQueueFamily() {
		return dlssmc$pickPresentQueue().queueFamilyIndex();
	}

	@Override
	public int dlssmc$graphicsQueueFamily() {
		return device.graphicsQueue().queueFamilyIndex();
	}

	@Override
	public boolean dlssmc$hasOwnPresentQueue() {
		return dlssmc$pickPresentQueue() != device.graphicsQueue();
	}

	@Override
	public int dlssmc$swapchainFormat() {
		return swapchainImageFormat;
	}

	@Override
	public boolean dlssmc$fifo() {
		return dlssmc$fifo;
	}

	@Override
	public void dlssmc$markSuboptimal() {
		swapchainSuboptimal = true;
	}
}
