package dev.dlssmc.mixin;

import dev.dlssmc.VitrailCompat;
import java.nio.file.Path;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hands shader pack sources read by Vitrail to {@link VitrailCompat}; only applied when Vitrail is installed. */
@Pseudo
@Mixin(targets = "dev.vitrail.pack.source.ShaderPackSource", remap = false)
public abstract class VitrailShaderPackSourceMixin {
	@Inject(method = "readLines", at = @At("RETURN"), cancellable = true, require = 0)
	private void dlssmc$patchLines(Path path, CallbackInfoReturnable<List<String>> cir) {
		List<String> lines = cir.getReturnValue();
		List<String> patched = VitrailCompat.patchLines(path, lines);
		if (patched != lines) {
			cir.setReturnValue(patched);
		}
	}
}
