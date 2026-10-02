package dev.metalfxmc.mixin;

import dev.metalfxmc.VitrailCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vitrail keeps the Distant Horizons terrain's depth to itself: taken right after it is drawn. Only applied with Vitrail. */
@Pseudo
@Mixin(targets = "dev.vitrail.render.PackChain", remap = false)
public abstract class VitrailPackChainMixin {
	@Inject(method = "takeDistantDepth", at = @At("RETURN"), require = 0)
	private static void metalfx$afterDistantDepth(CallbackInfo ci) {
		VitrailCompat.captureDistantDepth();
	}
}
