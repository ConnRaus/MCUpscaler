package dev.dlssmc.mixin;

import dev.dlssmc.VitrailCompat;
import java.nio.file.Path;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vitrail reads a shader pack afresh: what was learnt from the previous one is forgotten. Only applied with Vitrail. */
@Pseudo
@Mixin(targets = "dev.vitrail.pack.source.OpenedPack", remap = false)
public abstract class VitrailOpenedPackMixin {
	@Inject(method = "open", at = @At("HEAD"), require = 0)
	private static void dlssmc$beforeOpen(Path pack, Map<?, ?> options, String key, CallbackInfoReturnable<?> cir) {
		VitrailCompat.packOpening();
	}
}
