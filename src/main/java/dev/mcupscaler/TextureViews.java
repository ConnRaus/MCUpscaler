package dev.mcupscaler;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Views of textures that come without one (copies made by this mod, Minecraft's hand depth, Vitrail's far terrain depth),
 * for handing to the native side. A view keeps its texture's memory alive, so views of closed textures are released.
 */
public final class TextureViews {
	private static final Map<GpuTexture, GpuTextureView> VIEWS = new IdentityHashMap<>();

	private TextureViews() {
	}

	public static GpuTextureView of(GpuTexture texture) {
		GpuTextureView view = VIEWS.get(texture);
		if (view == null || view.isClosed()) {
			view = RenderSystem.getDevice().createTextureView(texture);
			VIEWS.put(texture, view);
		}
		return view;
	}

	/** Releases the views of textures that were closed. Called once per frame. */
	public static void prune() {
		Iterator<Map.Entry<GpuTexture, GpuTextureView>> it = VIEWS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<GpuTexture, GpuTextureView> entry = it.next();
			if (entry.getKey().isClosed()) {
				entry.getValue().close();
				it.remove();
			}
		}
	}
}
