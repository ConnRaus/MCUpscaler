package dev.metalfxmc.test;

import dev.metalfxmc.MetalFXConfig;
import dev.metalfxmc.MetalFXMod;
import dev.metalfxmc.WorldUpscaler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Creates a world and takes screenshots for comparing the upscalers (build/run/clientGameTest/screenshots).
 * Without settings: still and turning shots per upscaler. Other scenarios, by environment variable:
 * <ul>
 * <li>METALFX_BENCH (+ _HEAVY): frame time benchmark</li>
 * <li>METALFX_FRAMEGEN: frame generation (METALFX_FG_BACKEND=FSR|METALFX, _FG_WALK_ONLY, _FGBENCH_ONLY, _FG_SCENES,
 * _FG_MVSCALE); with the walk: METALFX_RPACK (resource pack), _HOLD (item), _F3, _F5 (+ _NO_PLAYERBOX), _PAUSE</li>
 * <li>METALFX_SCENE: built test scene (+ _FLY, _JITTER, _WAVE); METALFX_SCENE_DH: Distant Horizons flight</li>
 * <li>METALFX_MOTIONSHOTS, _MVSWEEP, _LOWSCALE, _OPTIONSHOT, _HIGH, _SEED: older single checks</li>
 * <li>MFX_FG_DUMP_DIR: the native side dumps frames and depth there (with the frame generation scenarios)</li>
 * </ul>
 */
public class MetalFXScreenshotTest implements FabricClientGameTest {
	private static final float YAW = 30.0F;
	private static final float PITCH = 20.0F;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (System.getenv("METALFX_BENCH") != null) {
			benchmark(context);
			return;
		}
		boolean motionShots = System.getenv("METALFX_MOTIONSHOTS") != null;
		if (motionShots || System.getenv("METALFX_FRAMEGEN") != null) {
			context.getInput().resizeWindow(3024, 1898);
		} else {
			context.getInput().resizeWindow(1728, 1080);
		}
		String seed = System.getenv("METALFX_SEED");
		try (TestSingleplayerContext singleplayer = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> { if (seed != null) s.setSeed(seed); })
			.create()) {
			server = singleplayer.getServer();
			// No mobs: they wander into shots and push the player around.
			server.runCommand("gamerule spawn_mobs false");
			server.runCommand("kill @e[type=!player]");
			context.runOnClient(mc -> {
				mc.options.renderDistance().set(8);
				mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
			});
			context.getInput().lookAt(YAW, PITCH);
			// Let chunks load and build
			context.waitTicks(200);

			if (System.getenv("METALFX_FRAMEGEN") != null) {
				frameGenTest(context);
				return;
			}
			if (System.getenv("METALFX_SCENE_DH") != null) {
				distantTest(context);
				return;
			}
			if (System.getenv("METALFX_SCENE") != null) {
				sceneTest(context);
				return;
			}
			if (System.getenv("METALFX_LOWSCALE") != null) {
				// 25% render scale with every upscaler (MetalFX temporal maxes out at 3x and must clamp, not fail).
				for (MetalFXConfig.Upscaler mode : MetalFXConfig.Upscaler.values()) {
					context.runOnClient(mc -> {
						MetalFXConfig.renderScale = 0.25F;
						MetalFXConfig.upscaler = mode;
					});
					context.waitTicks(60);
					context.runOnClient(mc -> MetalFXMod.LOGGER.info("[lowscale] {} -> {}", mode, MetalFXMod.statusLine()));
					context.takeScreenshot("lowscale_" + mode.name().toLowerCase(java.util.Locale.ROOT));
				}
				return;
			}
			if (System.getenv("METALFX_OPTIONSHOT") != null) {
				// Sodium's Video Settings opened on the MetalFX page (reflection: Sodium is only on the runtime classpath).
				context.setScreen(() -> {
					try {
						Class<?> manager = Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager");
						Object config = manager.getField("CONFIG").get(null);
						java.lang.reflect.Field modOptionsField = config.getClass().getDeclaredField("modOptions");
						modOptionsField.setAccessible(true);
						Object page = null;
						for (Object mod : (java.util.List<?>)modOptionsField.get(config)) {
							if ("metalfx".equals(mod.getClass().getMethod("configId").invoke(mod))) {
								page = ((java.util.List<?>)mod.getClass().getMethod("pages").invoke(mod)).get(0);
							}
						}
						Class<?> pageClass = Class.forName("net.caffeinemc.mods.sodium.client.config.structure.OptionPage");
						Object screen = Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen")
							.getMethod("createScreen", net.minecraft.client.gui.screens.Screen.class, pageClass)
							.invoke(null, null, page);
						return (net.minecraft.client.gui.screens.Screen)screen;
					} catch (ReflectiveOperationException e) {
						throw new RuntimeException(e);
					}
				});
				context.waitTicks(20);
				context.takeScreenshot("sodium_metalfx_page");
				context.setScreen(() -> null);
				return;
			}
			if (motionShots) {
				boolean high = System.getenv("METALFX_HIGH") != null;
				shotPitch = high ? 12.0F : PITCH;
				context.runOnClient(mc -> mc.options.renderDistance().set(high ? 16 : 12));
				if (high) {
					// Landscape view: fly ~40 blocks above the ground.
					// Invisible barrier platform 40 blocks up, then stand on it.
					server.runCommand("execute as @p at @p run fill ~-1 ~39 ~-1 ~1 ~39 ~1 barrier");
					server.runCommand("execute as @p at @p run tp @p ~ ~40 ~");
					context.waitTicks(400);
				}
				context.waitTicks(200);
				MetalFXConfig.Upscaler B = MetalFXConfig.Upscaler.BILINEAR;
				motionShot(context, "motion_native_1898p", false, B, 40);
				motionShot(context, "motion_spatial_50", true, MetalFXConfig.Upscaler.SPATIAL, 40);
				// Temporal modes change the texture LOD bias: give the shader pack time to reload.
				motionShot(context, "motion_fsr_50", true, MetalFXConfig.Upscaler.FSR, 300);
				context.runOnClient(mc -> MetalFXConfig.sharpness = 1.0F);
				motionShot(context, "motion_fsr_50_sharp1", true, MetalFXConfig.Upscaler.FSR, 60);
				context.runOnClient(mc -> MetalFXConfig.sharpness = 0.0F);
				motionShot(context, "motion_fsr_50_sharp0", true, MetalFXConfig.Upscaler.FSR, 60);
				context.runOnClient(mc -> MetalFXConfig.sharpness = 0.5F);
				motionShot(context, "motion_metalfx_temporal_50", true, MetalFXConfig.Upscaler.TEMPORAL, 60);
				motionShot(context, "motion_bilinear_50", true, B, 300);
				return;
			}
			still(context, "s_native", false, MetalFXConfig.Upscaler.BILINEAR, 1, 1);
			still(context, "s_bilinear", true, MetalFXConfig.Upscaler.BILINEAR, 1, 1);
			still(context, "s_spatial", true, MetalFXConfig.Upscaler.SPATIAL, 1, 1);
			still(context, "s_temporal_pp", true, MetalFXConfig.Upscaler.TEMPORAL, 1, 1);
			still(context, "s_temporal_pn", true, MetalFXConfig.Upscaler.TEMPORAL, 1, -1);
			still(context, "s_temporal_np", true, MetalFXConfig.Upscaler.TEMPORAL, -1, 1);
			still(context, "s_temporal_nn", true, MetalFXConfig.Upscaler.TEMPORAL, -1, -1);

			String signs = System.getProperty("metalfx.test.signs", "pp");
			int sx = signs.charAt(0) == 'n' ? -1 : 1;
			int sy = signs.charAt(1) == 'n' ? -1 : 1;
			turn(context, "m_native", false, MetalFXConfig.Upscaler.BILINEAR, sx, sy, false);
			turn(context, "m_spatial", true, MetalFXConfig.Upscaler.SPATIAL, sx, sy, false);
			turn(context, "m_temporal", true, MetalFXConfig.Upscaler.TEMPORAL, sx, sy, false);
			turn(context, "m_motion_vectors", true, MetalFXConfig.Upscaler.TEMPORAL, sx, sy, true);
			if (System.getenv("METALFX_MVSWEEP") != null) {
				float[][] scales = {{-1, -1}, {0, 0}, {1, -1}};
				for (float[] sc : scales) {
					context.runOnClient(mc -> { MetalFXConfig.motionScaleX = sc[0]; MetalFXConfig.motionScaleY = sc[1]; });
					turn(context, "m_temporal_mv" + (int)sc[0] + "_" + (int)sc[1], true, MetalFXConfig.Upscaler.TEMPORAL, sx, sy, false);
				}
				context.runOnClient(mc -> { MetalFXConfig.motionScaleX = 1; MetalFXConfig.motionScaleY = 1; });
			}
		}
	}

	private static void configure(
		ClientGameTestContext context, String name, boolean enabled, MetalFXConfig.Upscaler upscaler, int sx, int sy, boolean debugMotion
	) {
		context.runOnClient(mc -> {
			MetalFXConfig.enabled = enabled;
			MetalFXConfig.renderScale = 0.5F;
			MetalFXConfig.upscaler = upscaler;
			MetalFXConfig.jitterSignX = sx;
			MetalFXConfig.jitterSignY = sy;
			MetalFXConfig.debugMotion = debugMotion;
			MetalFXMod.LOGGER.info("[test] {} -> {} (jitter signs {}, {})", name, MetalFXMod.statusLine(), sx, sy);
		});
	}

	private static TestServerContext server;
	private static float shotPitch = PITCH;

	/** Frame generation: generated vs real frames mid-turn, then real/generated frame rates with and without it. */
	private static void frameGenTest(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.options.renderDistance().set(12));
		context.waitTicks(200);
		MetalFXConfig.Upscaler R = MetalFXConfig.Upscaler.FSR;
		String backend = System.getenv("METALFX_FG_BACKEND");
		MetalFXConfig.frameGenBackend = backend != null ? MetalFXConfig.FrameGenBackend.valueOf(backend) : MetalFXConfig.FrameGenBackend.FSR;
		MetalFXMod.LOGGER.info("[fgbench] frame generator {}", WorldUpscaler.frameGenBackend());
		if (System.getenv("METALFX_FG_WALK_ONLY") != null) {
			fgWalkShots(context, R);
			return;
		}
		if (System.getenv("METALFX_FGBENCH_ONLY") == null) {
			fgShot(context, "fg_real_fsr", true, R, false, 1.0F, false);
			fgShot(context, "fg_generated_fsr", true, R, true, 1.0F, false);
			fgShot(context, "fg_generated_fsr_mv_neg", true, R, true, -1.0F, false);
			fgShot(context, "fg_pitch_real_fsr", true, R, false, 1.0F, true);
			fgShot(context, "fg_pitch_generated_fsr", true, R, true, 1.0F, true);
			fgShot(context, "fg_real_native", false, R, false, 1.0F, false);
			fgShot(context, "fg_generated_native", false, R, true, 1.0F, false);
			fgWalkShots(context, R);
		}
		context.runOnClient(mc -> {
			WorldUpscaler.debugShowInterpolated = false;
			MetalFXConfig.frameGenMotionScale = 1.0F;
			mc.options.framerateLimit().set(260);
			mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
		});
		// Light = FSR at 50%; heavy = FSR at 95% and 24 chunks (GPU bound, ~60 fps); native = no upscaling, 24 chunks.
		String[] scenes = System.getenv("METALFX_FG_SCENES") != null ? System.getenv("METALFX_FG_SCENES").split(",") : new String[] {"light", "heavy", "native"};
		for (String scene : scenes) {
			context.runOnClient(mc -> {
				mc.options.renderDistance().set(scene.equals("light") ? 12 : 24);
				MetalFXConfig.enabled = !scene.equals("native");
				MetalFXConfig.upscaler = R;
				MetalFXConfig.renderScale = scene.equals("heavy") ? 0.95F : 0.5F;
			});
			context.waitTicks(scene.equals("light") ? 20 : 300);
			// Frame generation off (VSync off and on), then on (Minecraft's VSync no longer matters: the presenter paces).
			for (int v = 0; v < 3; v++) {
				boolean frameGen = v == 2;
				boolean vsync = v != 0;
				context.runOnClient(mc -> {
					mc.options.enableVsync().set(vsync);
					MetalFXConfig.frameGeneration = frameGen;
					org.lwjgl.sdl.SDLHints.SDL_SetHint("SDL_FORCE_RAISEWINDOW", "1");
					org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow(mc.getWindow().handle());
				});
				context.waitTicks(40);
				long[] stats = new long[4];
				context.runOnClient(mc -> {
					stats[3] = WorldUpscaler.generatedShown;
					NativeBridgeAccess.resetGpu();
					WorldUpscaler.frameHook = () -> {
						long now = System.nanoTime();
						if (stats[0]++ == 0) {
							stats[1] = now;
						}
						stats[2] = now;
						float yaw = YAW + 0.1F * stats[0];
						mc.player.setYRot(yaw);
						mc.player.yRotO = yaw;
					};
				});
				context.waitTicks(80);
				context.runOnClient(mc -> {
					WorldUpscaler.frameHook = null;
					double seconds = (stats[2] - stats[1]) / 1e9;
					long generated = WorldUpscaler.generatedShown - stats[3];
					MetalFXMod.LOGGER.info(
						"[fgbench] {} vsync {} frame gen {}: {} real fps + {} generated fps = {} shown, MetalFX GPU {} us/buffer, frame gen {} us",
						scene, vsync, frameGen ? "on" : "off",
						String.format("%.0f", (stats[0] - 1) / seconds), String.format("%.0f", generated / seconds),
						String.format("%.0f", (stats[0] - 1 + generated) / seconds), NativeBridgeAccess.gpuMicros(),
						dev.metalfxmc.NativeBridge.takeStageMicros(3)
					);
				});
			}
		}
		context.runOnClient(mc -> {
			MetalFXConfig.frameGeneration = false;
			MetalFXConfig.enabled = true;
		});
	}

	/**
	 * Generated frames while walking forward and strafing left (view bobbing and sideways motion move the world past the
	 * hand) and turning right, at a few bob phases.
	 */
	private static void fgWalkShots(ClientGameTestContext context, MetalFXConfig.Upscaler upscaler) {
		configure(context, "fg_walk", true, upscaler, 1, 1, false);
		String mvScale = System.getenv("METALFX_FG_MVSCALE");
		context.runOnClient(mc -> {
			MetalFXConfig.frameGeneration = true;
			MetalFXConfig.frameGenMotionScale = mvScale != null ? Float.parseFloat(mvScale) : 1.0F;
			WorldUpscaler.debugShowInterpolated = true;
		});
		if (System.getenv("MFX_FG_DUMP_DIR") != null) {
			// Open flat ground to walk on (in slices: /fill has a block limit).
			for (int x = -40; x < 40; x += 10) {
				server.runCommand("execute at @p run fill ~" + x + " ~-1 ~-40 ~" + (x + 9) + " ~-1 ~40 grass_block");
				server.runCommand("execute at @p run fill ~" + x + " ~ ~-40 ~" + (x + 9) + " ~15 ~40 air");
			}
			server.runCommand("kill @e[type=item]");
			server.runCommand("clear @p");
			String rpack = System.getenv("METALFX_RPACK");
			if (rpack != null) {
				context.runOnClient(mc -> {
					mc.getResourcePackRepository().reload();
					MetalFXMod.LOGGER.info("[test] resource pack {} added: {}", rpack, mc.getResourcePackRepository().addPack("file/" + rpack));
					mc.reloadResourcePacks();
				});
				context.waitTicks(100);
			}
			String hold = System.getenv("METALFX_HOLD");
			if (hold != null) {
				server.runCommand("give @p " + hold);
				for (int x = -6; x <= 6; x += 3) {
					server.runCommand("execute at @p run setblock ~" + x + " ~ ~4 " + hold);
				}
			}
			if (System.getenv("METALFX_F3") != null) {
				context.runOnClient(mc -> mc.debugEntries.setOverlayVisible(true));
			}
			if (System.getenv("METALFX_F5") != null) {
				context.runOnClient(mc -> {
					mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
					WorldUpscaler.debugNoPlayerBox = System.getenv("METALFX_NO_PLAYERBOX") != null;
				});
			}
			context.waitTicks(60);
		}
		context.getInput().lookAt(YAW, shotPitch);
		context.waitTicks(40);
		long[] start = {0};
		context.runOnClient(mc -> WorldUpscaler.frameHook = () -> {
			long now = System.nanoTime();
			if (start[0] == 0) {
				start[0] = now;
			}
			float yaw = YAW + 40.0F * (now - start[0]) / 1e9F;
			mc.player.setYRot(yaw);
			mc.player.yRotO = yaw;
		});
		context.getInput().holdKey(options -> options.keyUp);
		context.getInput().holdKey(options -> options.keyLeft);
		for (int i = 0; i < 4; i++) {
			context.waitTicks(5);
			context.takeScreenshot("fg_walk_generated_" + i);
		}
		if (System.getenv("MFX_FG_DUMP_DIR") != null) {
			// The frames exactly as the presenter shows them (no debug copy: the normal two-queue path).
			context.runOnClient(mc -> WorldUpscaler.debugShowInterpolated = false);
			context.waitTicks(10);
			context.runOnClient(mc -> dev.metalfxmc.NativeBridge.fgDump(24));
			context.waitTicks(20);
		}
		context.getInput().releaseKey(options -> options.keyUp);
		context.getInput().releaseKey(options -> options.keyLeft);
		if (System.getenv("METALFX_PAUSE") != null) {
			context.runOnClient(mc -> {
				WorldUpscaler.frameHook = null;
				mc.pauseGame(false);
			});
			context.waitTicks(40);
			context.runOnClient(mc -> dev.metalfxmc.NativeBridge.fgDump(12));
			context.waitTicks(20);
			context.runOnClient(mc -> MetalFXConfig.frameGeneration = false);
			context.waitTicks(40);
			for (int i = 0; i < 4; i++) {
				context.takeScreenshot("pause_upscale_" + i);
			}
			context.runOnClient(mc -> mc.gui.setScreen(null));
			return;
		}
		context.runOnClient(mc -> {
			WorldUpscaler.frameHook = null;
			WorldUpscaler.debugShowInterpolated = false;
			mc.debugEntries.setOverlayVisible(true);
		});
		context.waitTicks(30);
		context.takeScreenshot("fg_f3");
		context.runOnClient(mc -> mc.debugEntries.setOverlayVisible(false));
	}

	private static void fgShot(
		ClientGameTestContext context, String name, boolean upscale, MetalFXConfig.Upscaler upscaler, boolean generated, float mvScale, boolean pitchTurn
	) {
		configure(context, name, upscale, upscaler, 1, 1, false);
		server.runCommand("time set 1000");
		context.runOnClient(mc -> {
			MetalFXConfig.frameGeneration = true;
			MetalFXConfig.frameGenMotionScale = mvScale;
			WorldUpscaler.debugShowInterpolated = generated;
			org.lwjgl.sdl.SDLHints.SDL_SetHint("SDL_FORCE_RAISEWINDOW", "1");
			org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow(mc.getWindow().handle());
		});
		context.getInput().lookAt(pitchTurn ? YAW : YAW - 30.0F, pitchTurn ? shotPitch - 20.0F : shotPitch);
		context.waitTicks(60);
		long[] start = {0};
		// Fast turn (60 degrees/s) so interpolation errors are visible.
		context.runOnClient(mc -> WorldUpscaler.frameHook = () -> {
			long now = System.nanoTime();
			if (start[0] == 0) {
				start[0] = now;
			}
			float t = (now - start[0]) / 1e9F;
			if (pitchTurn) {
				// Steady 30 degrees/s downward tilt, starting 20 degrees above the shot pitch.
				float pitch = shotPitch - 20.0F + 30.0F * t;
				mc.player.setXRot(pitch);
				mc.player.xRotO = pitch;
			} else {
				float yaw = YAW - 30.0F + 60.0F * t;
				mc.player.setYRot(yaw);
				mc.player.yRotO = yaw;
			}
		});
		context.waitTicks(20);
		context.takeScreenshot(name);
		context.runOnClient(mc -> {
			WorldUpscaler.frameHook = null;
			WorldUpscaler.debugShowInterpolated = false;
		});
	}

	/** Camera turning at a constant 15 degrees per second (by wall-clock time, so every mode ends near the same yaw). */
	/**
	 * A built scene with what temporal upscaling finds hard: a water pool, bushes, short grass and the block outline
	 * (crosshair on a grass tuft). Per mode: consecutive still frames (outline stability), then frames while strafing.
	 */
	private static void sceneTest(ClientGameTestContext context) {
		server.runCommand("time set 1000");
		server.runCommand("weather clear");
		server.runCommand("gamerule advance_time false");
		server.runCommand("gamerule advance_weather false");
		// A floating island 60 blocks up (built before the player is moved onto it).
		String at = "execute at @p positioned ~ ~60 ~ run ";
		server.runCommand(at + "fill ~-12 ~-2 ~-6 ~12 ~-2 ~24 stone");
		server.runCommand(at + "fill ~-12 ~-1 ~-6 ~12 ~-1 ~24 grass_block");
		server.runCommand(at + "fill ~-6 ~-2 ~8 ~6 ~-2 ~18 sand");
		server.runCommand(at + "fill ~-6 ~-1 ~8 ~6 ~-1 ~18 water");
		for (int x = -4; x <= 4; x += 2) {
			server.runCommand(at + "setblock ~" + x + " ~ ~5 bush");
			server.runCommand(at + "setblock ~" + (x + 1) + " ~ ~5 short_grass");
		}
		server.runCommand(at + "setblock ~ ~ ~2 short_grass");
		if (System.getenv("METALFX_SCENE_FLY") != null) {
			// Field: bushes on the left half, short grass on the right half, in front of the pool.
			server.runCommand(at + "fill ~-12 ~ ~-5 ~-1 ~ ~4 bush");
			server.runCommand(at + "fill ~1 ~ ~-5 ~12 ~ ~4 short_grass");
			// Invisible floor 5 blocks up, to look at the field from above.
			server.runCommand(at + "fill ~-12 ~5 ~-6 ~12 ~5 ~24 barrier");
		}
		server.runCommand(System.getenv("METALFX_SCENE_FLY") != null ? "execute as @p at @p run tp @p ~ ~66.2 ~-2 0 0" : "execute as @p at @p run tp @p ~ ~60.2 ~ 0 0");
		context.waitTicks(100);
		server.runCommand("kill @e[type=item]");
		server.runCommand("execute as @p at @p run tp @p ~ ~ ~ 0 0");
		context.runOnClient(mc -> mc.options.renderDistance().set(8));
		context.waitTicks(100);
		if (System.getenv("MFX_FG_DUMP_DIR") != null) {
			// Depth inputs in this scene (frame generation path dumps them).
			context.runOnClient(mc -> {
				MetalFXConfig.enabled = true;
				MetalFXConfig.upscaler = MetalFXConfig.Upscaler.FSR;
				MetalFXConfig.frameGeneration = true;
			});
			context.getInput().lookAt(0.0F, 25.0F);
			context.waitTicks(300);
			context.runOnClient(mc -> dev.metalfxmc.NativeBridge.fgDump(4));
			context.waitTicks(20);
			context.takeScreenshot("scene_depthdump");
			return;
		}
		// Same spot for every mode (strafing drifts the player).
		net.minecraft.world.phys.Vec3 home = context.computeOnClient(mc -> mc.player.position());
		String homeTp = String.format(java.util.Locale.ROOT, "tp @p %.3f %.3f %.3f", home.x, home.y, home.z);
		String[][] modes = {{"native", "BILINEAR", "0", "1", "1"}, {"fsr", "FSR", "1", "1", "1"}, {"metalfx", "TEMPORAL", "1", "1", "1"}};
		if (System.getenv("METALFX_SCENE_JITTER") != null) {
			modes = new String[][] {
				{"metalfx_pp", "TEMPORAL", "1", "1", "1"}, {"metalfx_np", "TEMPORAL", "1", "-1", "1"},
				{"metalfx_pn", "TEMPORAL", "1", "1", "-1"}, {"metalfx_nn", "TEMPORAL", "1", "-1", "-1"}
			};
		}
		if (System.getenv("METALFX_SCENE_WAVE") != null) {
			modes = new String[][] {
				{"native", "BILINEAR", "0", "1", "1"}, {"metalfx_wave", "TEMPORAL", "1", "1", "1"}, {"metalfx_still", "TEMPORAL", "1", "1", "1"},
				{"fsr_wave", "FSR", "1", "1", "1"}, {"fsr_still", "FSR", "1", "1", "1"}
			};
		}
		for (String[] mode : modes) {
			server.runCommand(homeTp);
			context.waitTicks(10);
			context.runOnClient(mc -> {
				MetalFXConfig.jitterSignX = Integer.parseInt(mode[3]);
				MetalFXConfig.jitterSignY = Integer.parseInt(mode[4]);
				MetalFXConfig.enabled = mode[2].equals("1");
				MetalFXConfig.stillPackFoliage = !mode[0].endsWith("_wave");
				MetalFXConfig.renderScale = 0.5F;
				MetalFXConfig.upscaler = MetalFXConfig.Upscaler.valueOf(mode[1]);
				org.lwjgl.sdl.SDLHints.SDL_SetHint("SDL_FORCE_RAISEWINDOW", "1");
				org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow(mc.getWindow().handle());
			});
			context.getInput().lookAt(0.0F, 38.0F);
			// Temporal modes reload the shader pack (LOD bias, pack anti-aliasing off).
			context.waitTicks(mode[2].equals("1") && !mode[0].matches("metalfx_[np][np]") || mode[0].equals("metalfx_pp") ? 300 : 100);
			for (int i = 0; i < 3; i++) {
				context.takeScreenshot("scene_" + mode[0] + "_still" + i);
			}
			if (System.getenv("METALFX_SCENE_FLY") != null) {
				// Walk backwards over the field, looking down at it.
				context.getInput().lookAt(0.0F, 45.0F);
				context.waitTicks(20);
				context.getInput().holdKey(options -> options.keyDown);
				context.waitTicks(8);
				context.takeScreenshot("scene_" + mode[0] + "_move");
				context.getInput().releaseKey(options -> options.keyDown);
				context.waitTicks(10);
				continue;
			}
			context.getInput().lookAt(0.0F, 15.0F);
			context.waitTicks(20);
			context.getInput().holdKey(options -> options.keyLeft);
			context.waitTicks(12);
			context.takeScreenshot("scene_" + mode[0] + "_strafe");
			context.getInput().releaseKey(options -> options.keyLeft);
			context.getInput().holdKey(options -> options.keyRight);
			context.waitTicks(12);
			context.getInput().releaseKey(options -> options.keyRight);
			context.waitTicks(20);
		}
	}

	/** Distant Horizons far terrain (needs DH in the test mods): high up, short render distance, flying forward. */
	private static void distantTest(ClientGameTestContext context) {
		server.runCommand("time set 1000");
		server.runCommand("weather clear");
		server.runCommand("gamerule advance_time false");
		server.runCommand("gamerule advance_weather false");
		server.runCommand("gamemode creative @a");
		context.waitTicks(5);
		server.runCommand("execute as @p at @p run tp @p ~ 160 ~ 0 0");
		context.runOnClient(mc -> {
			mc.options.renderDistance().set(4);
			mc.player.getAbilities().flying = true;
			mc.player.onUpdateAbilities();
		});
		// Distant Horizons builds its far terrain in the background.
		context.waitTicks(Integer.parseInt(System.getenv().getOrDefault("METALFX_DH_WAIT", "1200")));
		server.runCommand("execute as @p at @p run tp @p ~ 160 ~ 0 0");
		context.runOnClient(mc -> {
			mc.player.getAbilities().flying = true;
			mc.player.onUpdateAbilities();
		});
		context.waitTicks(40);
		net.minecraft.world.phys.Vec3 home = context.computeOnClient(mc -> mc.player.position());
		String homeTp = String.format(java.util.Locale.ROOT, "tp @p %.3f %.3f %.3f", home.x, home.y, home.z);
		if (System.getenv("MFX_FG_DUMP_DIR") != null) {
			context.runOnClient(mc -> {
				MetalFXConfig.enabled = true;
				MetalFXConfig.renderScale = 0.5F;
				MetalFXConfig.upscaler = MetalFXConfig.Upscaler.FSR;
				MetalFXConfig.frameGeneration = true;
			});
			context.getInput().lookAt(0.0F, 20.0F);
			context.waitTicks(300);
			context.runOnClient(mc -> dev.metalfxmc.NativeBridge.fgDump(4));
			context.waitTicks(20);
			context.takeScreenshot("dh_depthdump");
			return;
		}
		String[][] modes = {{"native", "BILINEAR", "0"}, {"fsr", "FSR", "1"}, {"metalfx", "TEMPORAL", "1"}};
		for (String[] mode : modes) {
			server.runCommand(homeTp);
			context.runOnClient(mc -> {
				MetalFXConfig.enabled = mode[2].equals("1");
				MetalFXConfig.renderScale = 0.5F;
				MetalFXConfig.upscaler = MetalFXConfig.Upscaler.valueOf(mode[1]);
				mc.player.getAbilities().flying = true;
				org.lwjgl.sdl.SDLHints.SDL_SetHint("SDL_FORCE_RAISEWINDOW", "1");
				org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow(mc.getWindow().handle());
			});
			context.getInput().lookAt(0.0F, 20.0F);
			context.waitTicks(mode[2].equals("1") ? 300 : 100);
			context.takeScreenshot("dh_" + mode[0] + "_still");
			context.getInput().holdKey(options -> options.keyUp);
			context.getInput().holdKey(options -> options.keySprint);
			context.waitTicks(30);
			for (int i = 0; i < 3; i++) {
				context.takeScreenshot("dh_" + mode[0] + "_fly" + i);
			}
			context.getInput().releaseKey(options -> options.keySprint);
			context.getInput().releaseKey(options -> options.keyUp);
			context.waitTicks(10);
		}
		// Pause menu over the far terrain: upscaling only, then with frame generation.
		for (String fg : new String[] {"0", "1"}) {
			context.runOnClient(mc -> {
				MetalFXConfig.enabled = true;
				MetalFXConfig.upscaler = MetalFXConfig.Upscaler.TEMPORAL;
				MetalFXConfig.frameGeneration = fg.equals("1");
			});
			context.waitTicks(100);
			context.runOnClient(mc -> mc.pauseGame(false));
			context.waitTicks(40);
			for (int i = 0; i < 4; i++) {
				context.takeScreenshot("dh_pause_fg" + fg + "_" + i);
			}
			context.runOnClient(mc -> mc.gui.setScreen(null));
			context.waitTicks(20);
		}
	}

	private static void motionShot(ClientGameTestContext context, String name, boolean enabled, MetalFXConfig.Upscaler upscaler, int settleTicks) {
		configure(context, name, enabled, upscaler, 1, 1, false);
		server.runCommand("time set 1000");
		context.runOnClient(mc -> {
			org.lwjgl.sdl.SDLHints.SDL_SetHint("SDL_FORCE_RAISEWINDOW", "1");
			org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow(mc.getWindow().handle());
		});
		context.getInput().lookAt(YAW - 30.0F, shotPitch);
		context.waitTicks(settleTicks);
		long[] start = {0};
		context.runOnClient(mc -> WorldUpscaler.frameHook = () -> {
			long now = System.nanoTime();
			if (start[0] == 0) {
				start[0] = now;
			}
			float yaw = YAW - 30.0F + 15.0F * (now - start[0]) / 1e9F;
			mc.player.setYRot(yaw);
			mc.player.yRotO = yaw;
		});
		context.waitTicks(40);
		context.takeScreenshot(name);
		context.runOnClient(mc -> WorldUpscaler.frameHook = null);
	}

	private static void still(ClientGameTestContext context, String name, boolean enabled, MetalFXConfig.Upscaler upscaler, int sx, int sy) {
		configure(context, name, enabled, upscaler, sx, sy, false);
		// Same time of day for every shot (the sun keeps moving otherwise, which skews the comparisons).
		server.runCommand("time set 1000");
		context.getInput().lookAt(YAW, PITCH);
		context.waitTicks(60);
		context.takeScreenshot(name);
	}

	/**
	 * Turns the camera by a fixed amount every rendered frame (not every tick), so the screenshot frame itself is
	 * mid-motion. Frames are counted from a fresh start, so every mode ends at the same yaw.
	 */
	private static void turn(
		ClientGameTestContext context, String name, boolean enabled, MetalFXConfig.Upscaler upscaler, int sx, int sy, boolean debugMotion
	) {
		configure(context, name, enabled, upscaler, sx, sy, debugMotion);
		server.runCommand("time set 1000");
		context.getInput().lookAt(YAW - 20.0F, PITCH);
		context.waitTicks(20);
		int[] frame = {0};
		context.runOnClient(mc -> WorldUpscaler.frameHook = () -> {
			float yaw = YAW - 20.0F + 0.5F * frame[0]++;
			mc.player.setYRot(yaw);
			mc.player.yRotO = yaw;
		});
		context.waitTicks(30);
		context.takeScreenshot(name);
		context.runOnClient(mc -> {
			WorldUpscaler.frameHook = null;
			MetalFXMod.LOGGER.info("[test] {} captured after {} turning frames, yaw {}", name, frame[0], mc.player.getYRot());
		});
	}

	/** Frame-time benchmark at the MacBook Pro 14" native resolution, camera slowly turning. */
	private static void benchmark(ClientGameTestContext context) {
		boolean heavy = System.getenv("METALFX_BENCH_HEAVY") != null;
		context.getInput().resizeWindow(3024, 1898);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false).create()) {
			context.runOnClient(mc -> {
				mc.options.renderDistance().set(heavy ? 32 : 12);
				mc.options.framerateLimit().set(260);
				mc.options.enableVsync().set(false);
				// No input arrives during the benchmark; don't let the AFK limiter drop to 30 fps.
				mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
				if (heavy) {
					// GPU-bound scene: far view, fabulous-style transparency, clouds.
					mc.options.improvedTransparency().set(true);
					mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.FANCY);
				}
			});
			context.getInput().lookAt(YAW, PITCH);
			context.waitTicks(heavy ? 900 : 300);
			String[] names = {"native", "spatial 50%", "fsr 50%", "metalfx temporal 50%", "native", "fsr 50%", "metalfx temporal 50%", "spatial 50%"};
			MetalFXConfig.Upscaler B = MetalFXConfig.Upscaler.BILINEAR, S = MetalFXConfig.Upscaler.SPATIAL;
			MetalFXConfig.Upscaler R = MetalFXConfig.Upscaler.FSR, T = MetalFXConfig.Upscaler.TEMPORAL;
			MetalFXConfig.Upscaler[] modes = {B, S, R, T, B, R, T, S};
			float[] scales = {1.0F, 0.5F, 0.5F, 0.5F, 1.0F, 0.5F, 0.5F, 0.5F};
			for (int m = 0; m < names.length; m++) {
				int mode = m;
				context.runOnClient(mc -> {
					// Background windows get throttled by macOS; pull the game to the front for every run.
					org.lwjgl.sdl.SDLHints.SDL_SetHint("SDL_FORCE_RAISEWINDOW", "1");
					org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow(mc.getWindow().handle());
					MetalFXConfig.renderScale = scales[mode];
					MetalFXConfig.upscaler = modes[mode];
					MetalFXConfig.debugNoJitter = names[mode].contains("no-jitter");
					MetalFXConfig.debugSkipScaler = names[mode].contains("no-scaler");
				});
				context.waitTicks(40);
				long[] stats = new long[3]; // frames, first ns, last ns
				context.runOnClient(mc -> {
					NativeBridgeAccess.resetGpu();
					WorldUpscaler.frameHook = () -> {
						long now = System.nanoTime();
						if (stats[0]++ == 0) {
							stats[1] = now;
						}
						stats[2] = now;
						float yaw = YAW + 0.1F * stats[0];
						mc.player.setYRot(yaw);
						mc.player.yRotO = yaw;
					};
				});
				context.waitTicks(60);
				context.runOnClient(mc -> {
					WorldUpscaler.frameHook = null;
					double ms = (stats[2] - stats[1]) / 1e6 / Math.max(1, stats[0] - 1);
					MetalFXMod.LOGGER.info(
						"[bench] {} (focused {}): {} frames, {} ms/frame ({} fps), MetalFX GPU {} us/frame",
						names[mode], mc.isWindowActive(), stats[0], String.format("%.2f", ms), String.format("%.0f", 1000.0 / ms), NativeBridgeAccess.gpuMicros()
					);
				});
			}
		}
	}

	private static final class NativeBridgeAccess {
		static void resetGpu() {
			if (WorldUpscaler.isMetalFxReady()) {
				dev.metalfxmc.NativeBridge.takeGpuMicros();
				for (int i = 0; i < 4; i++) {
					dev.metalfxmc.NativeBridge.takeStageMicros(i);
				}
			}
		}

		static String gpuMicros() {
			if (!WorldUpscaler.isMetalFxReady()) {
				return "-";
			}
			return dev.metalfxmc.NativeBridge.takeGpuMicros() + " (stages motion " + dev.metalfxmc.NativeBridge.takeStageMicros(0)
				+ ", scaler " + dev.metalfxmc.NativeBridge.takeStageMicros(1) + ", output " + dev.metalfxmc.NativeBridge.takeStageMicros(2) + ", texels " + dev.metalfxmc.NativeBridge.takeStageMicros(3) + ")";
		}
	}
}
