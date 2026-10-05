package dev.mcupscaler.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.renderpearl.api.device.GpuSurface;
import dev.mcupscaler.Reflex;
import dev.mcupscaler.WorldUpscaler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NVIDIA Reflex's frame boundaries (its sleep before the input is read, and the start of the rendering), and frame
 * generation's hooks around presenting: on macOS its presenter shows the frames instead of Minecraft's swapchain (see
 * {@link WorldUpscaler#shouldAcquireSwapchain}); on both, generated frames count in the FPS counter.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Shadow
	private int frames;

	@Inject(method = "run", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents(Lcom/mojang/blaze3d/platform/SDLEventHandler;)V"))
	private void mcupscaler$reflexSleep(CallbackInfo ci) {
		Reflex.frameStart();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
	private void mcupscaler$renderStart(CallbackInfo ci) {
		Reflex.renderStart();
	}

	/** No swapchain image while the macOS presenter is live: Minecraft then skips its blit and present too. */
	@WrapWithCondition(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V")
	)
	private boolean mcupscaler$skipAcquireForFrameGen(GpuSurface surface) {
		return WorldUpscaler.shouldAcquireSwapchain();
	}

	@Inject(
		method = "renderFrame",
		at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", args = "ldc=swapchainBlit")
	)
	private void mcupscaler$beforeSwapchainBlit(CallbackInfo ci) {
		WorldUpscaler.beforeSwapchainBlit((Minecraft)(Object)this);
		// The FPS counter then shows what reaches the screen.
		this.frames += WorldUpscaler.takeGeneratedFrames();
	}

	@Inject(
		method = "renderFrame",
		at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=present")
	)
	private void mcupscaler$beforePresent(CallbackInfo ci) {
		WorldUpscaler.beforePresent();
	}
}
