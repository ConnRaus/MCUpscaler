package dev.dlssmc.mixin;

import dev.dlssmc.Reflex;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NVIDIA Reflex's frame boundaries: its sleep before the input is read, and the start of the rendering. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "run", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents(Lcom/mojang/blaze3d/platform/SDLEventHandler;)V"))
	private void dlssmc$reflexSleep(CallbackInfo ci) {
		Reflex.frameStart();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
	private void dlssmc$renderStart(CallbackInfo ci) {
		Reflex.renderStart();
	}
}
