package dev.mcupscaler.mixin;

import dev.mcupscaler.VitrailCompat;
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
	private static void mcupscaler$afterDistantDepth(CallbackInfo ci) {
		VitrailCompat.captureDistantDepth();
	}
}
