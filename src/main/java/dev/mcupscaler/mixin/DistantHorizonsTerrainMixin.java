package dev.mcupscaler.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.mcupscaler.WorldUpscaler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * With its anti-aliasing on, Distant Horizons shakes its terrain by its own TAA jitter on top of the upscaler's: DLSS and
 * FSR then see random sub-pixel noise there and blur and flicker it. Its anti-aliasing reads as off while they run.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.BlazeDhTerrainRenderer", remap = false)
public abstract class DistantHorizonsTerrainMixin {
	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Ljava/lang/Boolean;booleanValue()Z", ordinal = 0), require = 0)
	private boolean mcupscaler$noJitterUnderUpscaler(boolean antiAliasing) {
		return antiAliasing && !WorldUpscaler.isTemporalFrame();
	}
}
