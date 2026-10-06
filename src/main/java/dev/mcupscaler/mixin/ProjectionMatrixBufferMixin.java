package dev.mcupscaler.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.mcupscaler.WorldUpscaler;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Projections as they are uploaded: Vitrail's for the hand it draws with a shader pack (see WorldUpscaler#projectionUploaded). */
@Mixin(ProjectionMatrixBuffer.class)
public abstract class ProjectionMatrixBufferMixin {
	@Inject(method = "getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;", at = @At("HEAD"))
	private void mcupscaler$uploaded(Matrix4f projection, CallbackInfoReturnable<GpuBufferSlice> cir) {
		WorldUpscaler.projectionUploaded(projection);
	}
}
