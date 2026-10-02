package dev.metalfxmc.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.metalfxmc.WorldUpscaler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SkyRenderer caches whichever target was current when it was created (which may be the MetalFX world target),
 * so always resolve the target freshly instead.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {
	@ModifyExpressionValue(
		method = "*",
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;", opcode = Opcodes.GETFIELD)
	)
	private RenderTarget metalfx$currentTarget(RenderTarget original) {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.gameRenderer != null ? minecraft.gameRenderer.mainRenderTarget() : WorldUpscaler.redirect(original);
	}
}
