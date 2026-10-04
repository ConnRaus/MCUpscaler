package dev.dlssmc;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DebugCrosshairRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jspecify.annotations.Nullable;

/**
 * While the world renders at a lower resolution, the F3 axis crosshair (a screen-space gizmo drawn into the world image)
 * would be jittered and upscaled with the scene's motion vectors: it is drawn after upscaling instead, at the output
 * resolution.
 */
public final class DeferredCrosshair {
	@Nullable
	private static CameraRenderState camera;
	private static int guiScale;
	@Nullable
	private static GpuTexture depth;
	@Nullable
	private static GpuTextureView depthView;
	@Nullable
	private static ProjectionMatrixBuffer projection;

	private DeferredCrosshair() {
	}

	/** Returns true if the crosshair was deferred (instead of drawn now). */
	public static boolean defer(CameraRenderState camera, int guiScale) {
		if (!WorldUpscaler.inWorldPhase()) {
			return false;
		}
		DeferredCrosshair.camera = camera;
		DeferredCrosshair.guiScale = guiScale;
		return true;
	}

	/** Draws it at most once per frame, and only for a frame that deferred it. */
	public static void draw(DebugCrosshairRenderer renderer, Projection hud) {
		CameraRenderState deferred = camera;
		camera = null;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (deferred == null || main == null || main.getColorTextureView() == null) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		if (depth == null || depth.getWidth(0) != main.width || depth.getHeight(0) != main.height) {
			if (depthView != null) {
				depthView.close();
			}
			if (depth != null) {
				depth.close();
			}
			depth = device.createTexture("DLSS Debug Crosshair Depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
				GpuFormat.D32_FLOAT, main.width, main.height, 1, 1);
			depthView = device.createTextureView(depth);
		}
		if (projection == null) {
			projection = new ProjectionMatrixBuffer("DLSS debug crosshair");
		}
		device.createCommandEncoder().clearDepthTexture(depth, 0.0);
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(projection.getBuffer(hud), ProjectionType.PERSPECTIVE);
		renderer.render(deferred, guiScale, main.getColorTextureView(), depthView);
		RenderSystem.restoreProjectionMatrix();
	}
}
