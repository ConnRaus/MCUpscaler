package dev.metalfxmc.mixin;

import dev.metalfxmc.ShaderPatches;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Sodium's equivalent of {@link GlobalSettingsUniformMixin}; only applied when Sodium is installed. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager", remap = false)
public abstract class SodiumUniformBufferManagerMixin {
	@ModifyArg(
		method = "update",
		at = @At(
			value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager$GlobalUniforms;<init>(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FFFFFFFFFFFFFI)V"
		),
		index = 15,
		require = 0
	)
	private int metalfx$encodeLodScale(int useRgss) {
		return ShaderPatches.encodeRgss(useRgss);
	}
}
