package dev.mcupscaler.mixin;

import dev.mcupscaler.WorldUpscaler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant Horizons' own TAA (without a shader pack) only blurs and smears its terrain under DLSS and FSR, which
 * anti-alias the whole image themselves: skipped while they run. Only applied with Distant Horizons.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeDhTaaRenderer", remap = false)
public abstract class DistantHorizonsTaaMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void mcupscaler$skipUnderUpscaler(@Coerce Object params, CallbackInfo ci) {
		if (WorldUpscaler.isTemporalFrame()) {
			ci.cancel();
		}
	}
}
