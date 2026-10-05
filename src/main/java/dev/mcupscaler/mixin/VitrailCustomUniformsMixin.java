package dev.mcupscaler.mixin;

import dev.mcupscaler.VitrailCompat;
import java.util.Map;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The pack's custom uniforms were evaluated for the frame: its jitter uniform gets DLSS's jitter. Only applied with Vitrail. */
@Pseudo
@Mixin(targets = "dev.vitrail.uniform.expr.CustomUniforms", remap = false)
public abstract class VitrailCustomUniformsMixin {
	@Shadow
	@Final
	private Map<String, ?> declared;

	@Inject(method = "update", at = @At("TAIL"), require = 0)
	private void mcupscaler$afterUpdate(CallbackInfo ci) {
		VitrailCompat.overridePackJitter(this.declared);
	}
}
