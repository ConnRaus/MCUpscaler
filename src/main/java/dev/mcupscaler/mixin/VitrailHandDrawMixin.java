package dev.mcupscaler.mixin;

import dev.mcupscaler.VitrailCompat;
import dev.mcupscaler.WorldUpscaler;
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
	// Called every frame, but only draws with a shader pack on: otherwise vanilla's hand pass runs as usual. The other
	// hooks do nothing unless this one started a pack hand frame.
	@Inject(method = "drawSolid", at = @At("HEAD"), require = 0)
	private static void mcupscaler$beforeHand(CallbackInfo ci) {
		if (VitrailCompat.drawsHand()) {
			WorldUpscaler.beforePackHand();
		}
	}

	@Inject(method = "drawSolid", at = @At("RETURN"), require = 0)
	private static void mcupscaler$afterHand(CallbackInfo ci) {
		WorldUpscaler.afterPackHand();
	}

	// Cut-out block items (and translucent ones) in the hand are drawn in this second pass.
	@Inject(method = "drawTranslucent", at = @At("HEAD"), require = 0)
	private static void mcupscaler$beforeTranslucentHand(CallbackInfo ci) {
		if (VitrailCompat.drawsHand()) {
			WorldUpscaler.beforePackTranslucentHand();
		}
	}

	@Inject(method = "drawTranslucent", at = @At("RETURN"), require = 0)
	private static void mcupscaler$afterTranslucentHand(CallbackInfo ci) {
		WorldUpscaler.afterPackTranslucentHand();
	}
}
