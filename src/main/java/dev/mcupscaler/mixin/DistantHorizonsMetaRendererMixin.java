package dev.mcupscaler.mixin;

import dev.mcupscaler.DistantHorizonsDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant Horizons without a shader pack keeps its terrain's depth to itself and copies only the colour into the world:
 * the depth is taken right before that copy. Only applied with Distant Horizons.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.BlazeDhMetaRenderer", remap = false)
public abstract class DistantHorizonsMetaRendererMixin {
	@Inject(method = "copyToMcTexture", at = @At("HEAD"), require = 0)
	private void mcupscaler$beforeCopy(@Coerce Object params, CallbackInfo ci) {
		DistantHorizonsDepth.capture(this, params);
	}
}
