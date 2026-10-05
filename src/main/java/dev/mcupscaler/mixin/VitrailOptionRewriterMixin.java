package dev.mcupscaler.mixin;

import dev.mcupscaler.VitrailCompat;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vitrail applies the pack's settings (shaderpacks/<pack>.txt) line by line as it expands the sources: the lines as
 * finally compiled, with the player's values, go through {@link VitrailCompat#rewrittenLine}. Only applied with Vitrail.
 */
@Pseudo
@Mixin(targets = "dev.vitrail.pack.option.OptionRewriter", remap = false)
public abstract class VitrailOptionRewriterMixin {
	@Inject(method = "apply", at = @At("RETURN"), cancellable = true, require = 0)
	private static void mcupscaler$afterOptions(String line, Map<?, ?> options, int scale, CallbackInfoReturnable<String> cir) {
		String result = cir.getReturnValue();
		String patched = VitrailCompat.rewrittenLine(result);
		if (patched != result) {
			cir.setReturnValue(patched);
		}
	}
}
