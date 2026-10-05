package dev.mcupscaler.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.mcupscaler.DeferredCrosshair;
import dev.mcupscaler.WorldUpscaler;
import net.minecraft.client.renderer.DebugCrosshairRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@Shadow
	@Final
	private RenderTarget hud3DTarget;

	@Shadow
	@Final
	private DebugCrosshairRenderer debugCrosshairRenderer;

	@Shadow
	@Final
	private Projection hudProjection;

	@Shadow
	public abstract void resize(int width, int height);

	// ---- sizing: world-only targets follow the scaled resolution

	@Inject(method = "render", at = @At("HEAD"))
	private void mcupscaler$syncSizes(CallbackInfo ci) {
		if (WorldUpscaler.needsResize(this.mainRenderTarget, this.hud3DTarget)) {
			this.resize(this.mainRenderTarget.width, this.mainRenderTarget.height);
		}
	}

	@WrapOperation(method = "resize", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;resize(II)V"))
	private void mcupscaler$scaleHud3dTarget(RenderTarget target, int width, int height, Operation<Void> original) {
		if (target == this.hud3DTarget) {
			original.call(target, WorldUpscaler.scaled(width), WorldUpscaler.scaled(height));
		} else {
			original.call(target, width, height);
		}
	}

	@WrapOperation(method = "resize", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;resize(II)V"))
	private void mcupscaler$scaleLevelRenderer(LevelRenderer levelRenderer, int width, int height, Operation<Void> original) {
		original.call(levelRenderer, WorldUpscaler.scaled(width), WorldUpscaler.scaled(height));
	}

	@Inject(method = "resize", at = @At("TAIL"))
	private void mcupscaler$resizeWorldTarget(int width, int height, CallbackInfo ci) {
		WorldUpscaler.onResize(width, height);
	}

	// ---- world phase boundaries

	@WrapOperation(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJFILnet/minecraft/world/phys/Vec3;Z)V")
	)
	private void mcupscaler$captureGlobals(
		GlobalSettingsUniform uniform,
		int width,
		int height,
		double glint,
		long gameTime,
		float partialTicks,
		int blur,
		Vec3 cameraPos,
		boolean rgss,
		Operation<Void> original
	) {
		WorldUpscaler.captureUniformUpdate((w, h) -> uniform.update(w, h, glint, gameTime, partialTicks, blur, cameraPos, rgss));
		original.call(uniform, width, height, glint, gameTime, partialTicks, blur, cameraPos, rgss);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
	private void mcupscaler$beginWorld(CallbackInfo ci) {
		WorldUpscaler.beginWorld(this.mainRenderTarget);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
	private void mcupscaler$endWorld(CallbackInfo ci) {
		WorldUpscaler.endWorld();
		DeferredCrosshair.draw(this.debugCrosshairRenderer, this.hudProjection);
	}

	/** The F3 axis crosshair is screen-space HUD: drawn after upscaling (see DeferredCrosshair). */
	@WrapOperation(
		method = "render3dHud",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/DebugCrosshairRenderer;render(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"
		)
	)
	private void mcupscaler$deferDebugCrosshair(
		DebugCrosshairRenderer renderer, CameraRenderState camera, int guiScale,
		GpuTextureView color, GpuTextureView depth, Operation<Void> original
	) {
		if (!DeferredCrosshair.defer(camera, guiScale)) {
			original.call(renderer, camera, guiScale, color, depth);
		}
	}

	// ---- temporal inputs: final world projection and pre-hand depth

	@WrapOperation(
		method = "renderLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;")
	)
	private GpuBufferSlice mcupscaler$captureProjection(ProjectionMatrixBuffer buffer, Matrix4f projection, Operation<GpuBufferSlice> original) {
		WorldUpscaler.captureLevelProjection(projection);
		return original.call(buffer, projection);
	}

	@WrapOperation(
		method = "render3dHud",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
		)
	)
	private GpuBufferSlice mcupscaler$jitterHand(ProjectionMatrixBuffer buffer, Projection projection, Operation<GpuBufferSlice> original) {
		if (!WorldUpscaler.isTemporalFrame()) {
			return original.call(buffer, projection);
		}
		return buffer.getBuffer(WorldUpscaler.handProjection(projection));
	}

	@WrapOperation(
		method = "render3dHud",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V")
	)
	private void mcupscaler$captureSceneDepth(CommandEncoder encoder, GpuTexture texture, double depth, Operation<Void> original) {
		WorldUpscaler.beforeHandDepthClear(texture);
		original.call(encoder, texture, depth);
	}

	// ---- redirect main target lookups to the world target during the world phase

	@ModifyReturnValue(method = "mainRenderTarget", at = @At("RETURN"))
	private RenderTarget mcupscaler$redirectAccessor(RenderTarget original) {
		return WorldUpscaler.redirect(original);
	}

	@ModifyExpressionValue(
		method = {"renderItemInHand", "render3dHud", "integrate3DHudDepth", "applyPostEffects"},
		at = @At(
			value = "FIELD",
			target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;",
			opcode = Opcodes.GETFIELD
		)
	)
	private RenderTarget mcupscaler$redirectField(RenderTarget original) {
		return WorldUpscaler.redirect(original);
	}
}
