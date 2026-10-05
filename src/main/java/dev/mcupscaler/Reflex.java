package dev.mcupscaler;

import java.nio.LongBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.NVLowLatency2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkLatencySleepInfoNV;
import org.lwjgl.vulkan.VkLatencySleepModeInfoNV;
import org.lwjgl.vulkan.VkPresentIdKHR;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSetLatencyMarkerInfoNV;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkSwapchainLatencyCreateInfoNV;

/**
 * NVIDIA Reflex through VK_NV_low_latency2 (enabled by VulkanFeatureSetsMixin with VK_KHR_present_id). Every frame starts
 * with the driver's sleep, which ends just early enough for the GPU to pick the frame up without it waiting in a queue,
 * and is marked out (simulation, render submission, present) with the frame's present ID so the driver can time it.
 * Render thread only.
 */
public final class Reflex {
	private static @Nullable VkDevice device;
	private static boolean supported;
	private static long swapchain;
	private static long semaphore;
	private static long sleepValue;
	/** Frame number = present ID of the frame's (real) present. */
	private static long frame;
	private static long lastPresentId;
	private static int timeouts;
	private static boolean failed;
	private static boolean frameStarted;

	private Reflex() {
	}

	/** The device has VK_NV_low_latency2 and VK_KHR_present_id enabled (known once the window's swapchain exists). */
	public static boolean isSupported() {
		return supported && !failed;
	}

	private static boolean active() {
		return supported && !failed && swapchain != 0L && UpscalerConfig.reflex != UpscalerConfig.Reflex.OFF;
	}

	/** Before Minecraft creates its swapchain: allows the latency modes on it (they are switched in {@link #applyMode}). */
	public static void prepareSwapchain(VkDevice vkDevice, VkSwapchainCreateInfoKHR info, MemoryStack stack) {
		if (device != vkDevice) {
			device = vkDevice;
			supported = vkDevice.getCapabilities().VK_NV_low_latency2 && vkDevice.getCapabilities().VK_KHR_present_id;
			UpscalerMod.LOGGER.info(supported ? "NVIDIA Reflex is available (VK_NV_low_latency2)"
				: "NVIDIA Reflex is unavailable: the driver doesn't offer VK_NV_low_latency2 and VK_KHR_present_id");
		}
		if (supported) {
			VkSwapchainLatencyCreateInfoNV latency = VkSwapchainLatencyCreateInfoNV.calloc(stack).sType$Default().latencyModeEnable(true);
			latency.pNext(info.pNext());
			info.pNext(latency.address());
		}
	}

	public static void swapchainCreated(long handle) {
		swapchain = handle;
		lastPresentId = 0L;
		applyMode();
	}

	public static void swapchainDestroyed() {
		swapchain = 0L;
	}

	/** The option changed. */
	public static void settingsChanged() {
		applyMode();
	}

	private static void applyMode() {
		if (!supported || failed || swapchain == 0L || device == null) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkLatencySleepModeInfoNV mode = VkLatencySleepModeInfoNV.calloc(stack).sType$Default()
				.lowLatencyMode(UpscalerConfig.reflex != UpscalerConfig.Reflex.OFF)
				.lowLatencyBoost(UpscalerConfig.reflex == UpscalerConfig.Reflex.BOOST)
				.minimumIntervalUs(0);
			int result = NVLowLatency2.vkSetLatencySleepModeNV(device, swapchain, mode);
			if (result != VK12.VK_SUCCESS) {
				fail("vkSetLatencySleepModeNV returned " + result);
			}
		}
	}

	/** Start of a frame, before input is read: the Reflex sleep, then the simulation and input markers. */
	public static void frameStart() {
		frameStarted = false;
		if (!active() || device == null) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (semaphore == 0L) {
				VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
					.semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
				LongBuffer handle = stack.callocLong(1);
				if (VK12.vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type.address()), null, handle)
					!= VK12.VK_SUCCESS) {
					fail("could not create its semaphore");
					return;
				}
				semaphore = handle.get(0);
			}
			long value = ++sleepValue;
			int result = NVLowLatency2.vkLatencySleepNV(device, swapchain,
				VkLatencySleepInfoNV.calloc(stack).sType$Default().signalSemaphore(semaphore).value(value));
			if (result == VK12.VK_SUCCESS) {
				VkSemaphoreWaitInfo wait = VkSemaphoreWaitInfo.calloc(stack).sType$Default()
					.semaphoreCount(1).pSemaphores(stack.longs(semaphore)).pValues(stack.longs(value));
				// The sleep never lasts longer than a frame; a semaphore that doesn't come means Reflex isn't working here.
				if (VK12.vkWaitSemaphores(device, wait, 100_000_000L) == VK12.VK_TIMEOUT) {
					if (++timeouts >= 5) {
						fail("the driver's sleep never ended");
						return;
					}
				} else {
					timeouts = 0;
				}
			}
			frame++;
			frameStarted = true;
			marker(NVLowLatency2.VK_LATENCY_MARKER_SIMULATION_START_NV);
			marker(NVLowLatency2.VK_LATENCY_MARKER_INPUT_SAMPLE_NV);
		}
	}

	/** Game logic done, the frame's rendering starts. */
	public static void renderStart() {
		marker(NVLowLatency2.VK_LATENCY_MARKER_SIMULATION_END_NV);
		marker(NVLowLatency2.VK_LATENCY_MARKER_RENDERSUBMIT_START_NV);
	}

	/** Rendering submitted, the frame is about to be presented. */
	public static void presentStart() {
		marker(NVLowLatency2.VK_LATENCY_MARKER_RENDERSUBMIT_END_NV);
		marker(NVLowLatency2.VK_LATENCY_MARKER_PRESENT_START_NV);
	}

	public static void presentEnd() {
		marker(NVLowLatency2.VK_LATENCY_MARKER_PRESENT_END_NV);
		frameStarted = false;
	}

	private static void marker(int marker) {
		if (!frameStarted || !active() || device == null) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			NVLowLatency2.vkSetLatencyMarkerNV(device, swapchain,
				VkSetLatencyMarkerInfoNV.calloc(stack).sType$Default().presentID(frame).marker(marker));
		}
	}

	/** The present ID for this frame's present (frame generation: the real frame's), 0 = none. */
	public static long takePresentId() {
		if (!frameStarted || !active() || frame <= lastPresentId) {
			return 0L;
		}
		lastPresentId = frame;
		return frame;
	}

	/** Tags Minecraft's own present with the frame's present ID. */
	public static void tagPresent(VkPresentInfoKHR info, MemoryStack stack) {
		long id = takePresentId();
		if (id != 0L) {
			VkPresentIdKHR presentId = VkPresentIdKHR.calloc(stack).sType$Default().swapchainCount(1).pPresentIds(stack.longs(id));
			presentId.pNext(info.pNext());
			info.pNext(presentId.address());
		}
	}

	private static void fail(String why) {
		failed = true;
		UpscalerMod.LOGGER.warn("NVIDIA Reflex switched off: {}", why);
	}

	/** F3 line. */
	public static String statusLine() {
		if (!supported) {
			return "Reflex: unavailable";
		}
		if (failed) {
			return "Reflex: failed";
		}
		return "Reflex: " + UpscalerConfig.reflex.displayName();
	}
}
