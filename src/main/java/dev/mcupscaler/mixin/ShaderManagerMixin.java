package dev.mcupscaler.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.mcupscaler.ShaderPatches;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Patches terrain shader sources (vanilla and Sodium, which loads through the same manager) as they are read. */
@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
	@WrapOperation(
		method = {"loadShader", "loadInclude"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/Resource;readAllAsString()Ljava/lang/String;")
	)
	private static String mcupscaler$patchSource(Resource resource, Operation<String> original, @Local(argsOnly = true) Identifier location) {
		return ShaderPatches.patch(location, original.call(resource));
	}
}
