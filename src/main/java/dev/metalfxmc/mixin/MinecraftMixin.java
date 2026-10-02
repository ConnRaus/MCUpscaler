package dev.metalfxmc.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.renderpearl.api.device.GpuSurface;
import dev.metalfxmc.WorldUpscaler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame generation: while it runs, our presenter shows the frames instead of Minecraft's swapchain (see
 * {@link WorldUpscaler#shouldAcquireSwapchain}).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Shadow
	private int frames;

	/** No swapchain image while the presenter is live: Minecraft then skips its blit and present too. */
	@WrapWithCondition(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V")
	)
	private boolean metalfx$skipAcquireForFrameGen(GpuSurface surface) {
		return WorldUpscaler.shouldAcquireSwapchain();
	}

	@Inject(
		method = "renderFrame",
		at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", args = "ldc=swapchainBlit")
	)
	private void metalfx$submitToPresenter(boolean advanceGameTime, CallbackInfo ci) {
		WorldUpscaler.beforeSwapchainBlit((Minecraft)(Object)this);
		// Count the generated frames the presenter showed: the FPS counter then shows what reaches the screen.
		this.frames += WorldUpscaler.takeGeneratedFrames();
	}

	@Inject(
		method = "renderFrame",
		at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=present")
	)
	private void metalfx$paceToPresenter(boolean advanceGameTime, CallbackInfo ci) {
		WorldUpscaler.beforePresent();
	}
}
