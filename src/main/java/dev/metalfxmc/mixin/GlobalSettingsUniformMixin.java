package dev.metalfxmc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.Std140Builder;
import dev.metalfxmc.ShaderPatches;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Packs the render-scale LOD correction into the UseRgss global (see {@link ShaderPatches}). */
@Mixin(GlobalSettingsUniform.class)
public abstract class GlobalSettingsUniformMixin {
	@WrapOperation(
		method = "update",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putInt(I)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 1)
	)
	private Std140Builder metalfx$encodeLodScale(Std140Builder builder, int useRgss, Operation<Std140Builder> original) {
		return original.call(builder, ShaderPatches.encodeRgss(useRgss));
	}
}
