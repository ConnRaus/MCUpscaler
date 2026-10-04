package dev.dlssmc.mixin;

import dev.dlssmc.WorldUpscaler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vitrail draws the first-person hand itself, into the world's depth buffer, instead of in vanilla's separate hand pass:
 * the world depth is captured right before and right after that so the hand can still be told apart. Only applied when
 * Vitrail is installed.
 */
@Pseudo
@Mixin(targets = "dev.vitrail.render.HandDraw", remap = false)
public abstract class VitrailHandDrawMixin {
	@Inject(method = "drawSolid", at = @At("HEAD"), require = 0)
	private static void dlssmc$beforeHand(CallbackInfo ci) {
		WorldUpscaler.beforePackHand();
	}

	@Inject(method = "drawSolid", at = @At("RETURN"), require = 0)
	private static void dlssmc$afterHand(CallbackInfo ci) {
		WorldUpscaler.afterPackHand();
	}

	// Cut-out block items (and translucent ones) in the hand are drawn in this second pass.
	@Inject(method = "drawTranslucent", at = @At("HEAD"), require = 0)
	private static void dlssmc$beforeTranslucentHand(CallbackInfo ci) {
		WorldUpscaler.beforePackTranslucentHand();
	}

	@Inject(method = "drawTranslucent", at = @At("RETURN"), require = 0)
	private static void dlssmc$afterTranslucentHand(CallbackInfo ci) {
		WorldUpscaler.afterPackTranslucentHand();
	}
}
