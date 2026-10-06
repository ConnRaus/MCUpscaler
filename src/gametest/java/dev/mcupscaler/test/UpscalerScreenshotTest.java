package dev.mcupscaler.test;

import dev.mcupscaler.UpscalerConfig;
import dev.mcupscaler.UpscalerMod;
import dev.mcupscaler.WorldUpscaler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Creates a world and takes screenshots for comparing the DLSS modes (build/run/clientGameTest/screenshots).
 * DLSS_BENCH=1 runs a frame-time benchmark instead.
 */
public class UpscalerScreenshotTest implements FabricClientGameTest {
	private static final float YAW = 30.0F;
	private static final float PITCH = 20.0F;
	private static TestServerContext server;
	private static float pitch = PITCH;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (System.getenv("DLSS_SAVE") != null) {
			savedWorld(context, System.getenv("DLSS_SAVE"));
			return;
		}
		runShots(context);
	}

	/**
	 * DLSS_SAVE=<world folder>: opens a copy of a real save (put in saves/ through -PtestRunFiles) where it was left, and
	 * takes the flicker shots there (still camera, consecutive screenshots per mode; compare with _deps/flicker.py), then
	 * checks Reflex and frame generation with and without upscaling.
	 */
	private static void savedWorld(ClientGameTestContext context, String levelId) {
		context.runOnClient(mc -> {
			mc.options.pauseOnLostFocus = false;
			mc.options.enableVsync().set(false);
			mc.options.framerateLimit().set(260);
			mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
			mc.createWorldOpenFlows().openWorld(levelId, () -> UpscalerMod.LOGGER.warn("[test] opening {} was cancelled", levelId));
		});
		try {
			context.waitFor(mc -> mc.level != null && mc.player != null && mc.gui.screen() == null, 3000);
			context.runOnClient(mc -> UpscalerMod.LOGGER.info("[test] opened {} at {}, yaw {} pitch {}", levelId, mc.player.position(),
				mc.player.getYRot(), mc.player.getXRot()));
			// Same light in every phase: time frozen (at the save's time, or DLSS_TIME).
			context.runOnClient(mc -> {
				var server = mc.getSingleplayerServer();
				server.execute(() -> {
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamerule advance_time false");
					if (System.getenv("DLSS_TIME") != null) {
						server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set " + System.getenv("DLSS_TIME"));
					}
					// DLSS_TP="x y z yaw pitch": a fixed view.
					if (System.getenv("DLSS_TP") != null) {
						server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "tp @a " + System.getenv("DLSS_TP"));
					}
				});
			});
			context.waitTicks(600);
			// "user": the settings from config/mcupscaler.properties as they are.
			UpscalerConfig.Upscaler dlss = UpscalerConfig.Upscaler.DLSS;
			Object[][] modes = {{"user", null, null, null}, {"native", false, dlss, UpscalerConfig.Quality.QUALITY},
				{"dlss_quality", true, dlss, UpscalerConfig.Quality.QUALITY},
				{"fsr_quality", true, UpscalerConfig.Upscaler.FSR, UpscalerConfig.Quality.QUALITY}};
			for (Object[] mode : modes) {
				String name = (String) mode[0];
				if (mode[1] != null) {
					configure(context, name, (boolean) mode[1], (UpscalerConfig.Upscaler) mode[2], (UpscalerConfig.Quality) mode[3]);
					context.runOnClient(mc -> UpscalerConfig.frameGeneration = UpscalerConfig.FrameGeneration.OFF);
				} else {
					context.runOnClient(mc -> UpscalerMod.LOGGER.info("[test] user settings -> {}", UpscalerMod.statusLine()));
				}
				context.waitTicks(120);
				// What the window shows (_deps/wincapture.py grabs it), still and then turning very slowly (like a hand on the mouse).
				context.runOnClient(mc -> UpscalerMod.LOGGER.info("[win-capture] {}", name));
				context.waitTicks(100);
				float[] yaw0 = new float[1];
				int[] turned = {0};
				context.runOnClient(mc -> {
					yaw0[0] = mc.player.getYRot();
					WorldUpscaler.frameHook = () -> {
						float yaw = yaw0[0] + 0.03F * turned[0]++;
						mc.player.setYRot(yaw);
						mc.player.yRotO = yaw;
					};
					UpscalerMod.LOGGER.info("[win-capture] {}_turn", name);
				});
				context.waitTicks(100);
				context.runOnClient(mc -> {
					WorldUpscaler.frameHook = null;
					mc.player.setYRot(yaw0[0]);
					mc.player.yRotO = yaw0[0];
				});
				context.waitTicks(20);
				// Spread over ~2.5 s: slow flicker (a few times a second) shows up too.
				for (int i = 0; i < 16; i++) {
					context.takeScreenshot("f_" + name + "_" + i);
					context.waitTicks(3);
				}
			}
			// DLSS_SAVE_QUICK: only the shots above.
			if (System.getenv("DLSS_SAVE_QUICK") != null) {
				return;
			}
			// The vignette while turning (it should stay at the screen edges).
			configure(context, "v_turn", true, UpscalerConfig.Upscaler.DLSS, UpscalerConfig.Quality.QUALITY);
			float[] start = new float[1];
			context.runOnClient(mc -> start[0] = mc.player.getYRot());
			int[] frame = {0};
			context.runOnClient(mc -> WorldUpscaler.frameHook = () -> {
				float yaw = start[0] + 1.5F * frame[0]++;
				mc.player.setYRot(yaw);
				mc.player.yRotO = yaw;
			});
			context.waitTicks(20);
			context.takeScreenshot("v_turn");
			context.runOnClient(mc -> WorldUpscaler.frameHook = null);
			// Reflex and frame generation, with DLSS and without upscaling (both independent of it).
			String[] phases = {"dlss, reflex off", "dlss, reflex on", "dlss, reflex boost", "dlss, fg + reflex on", "dlss, fg + reflex off",
				"native, reflex on", "native, fg + reflex on", "native, fg + reflex off"};
			for (String phase : phases) {
				context.runOnClient(mc -> {
					UpscalerConfig.enabled = phase.startsWith("dlss");
					UpscalerConfig.reflex = phase.contains("off") ? UpscalerConfig.Reflex.OFF : phase.contains("boost") ? UpscalerConfig.Reflex.BOOST
						: UpscalerConfig.Reflex.ON;
					dev.mcupscaler.Reflex.settingsChanged();
					UpscalerConfig.frameGeneration = phase.contains("fg") ? UpscalerConfig.FrameGeneration.DLSS : UpscalerConfig.FrameGeneration.OFF;
				});
				context.waitTicks(100);
				context.runOnClient(mc -> {
					String fg = dev.mcupscaler.UpscalerDebugEntry.frameGenLine();
					UpscalerMod.LOGGER.info("[reflex] {}: {} fps, {}; {}", phase, mc.getFps(), dev.mcupscaler.Reflex.statusLine(),
						fg);
				});
			}
			context.runOnClient(mc -> UpscalerConfig.frameGeneration = UpscalerConfig.FrameGeneration.OFF);
		} finally {
			startExitWatchdog();
		}
	}

	/**
	 * Leaving the test world can hang (Distant Horizons closing its databases): if the game is still running 20 s after
	 * the shots, writes every thread's stack to exit-hang-threads.txt (in the run directory) and ends the process.
	 */
	private static void startExitWatchdog() {
		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(20_000L);
			} catch (InterruptedException e) {
				return;
			}
			StringBuilder dump = new StringBuilder();
			for (var entry : Thread.getAllStackTraces().entrySet()) {
				dump.append('"').append(entry.getKey().getName()).append("\" ").append(entry.getKey().getState()).append('\n');
				for (StackTraceElement element : entry.getValue()) {
					dump.append("    at ").append(element).append('\n');
				}
				dump.append('\n');
			}
			try {
				java.nio.file.Files.writeString(java.nio.file.Path.of("exit-hang-threads.txt"), dump);
			} catch (java.io.IOException ignored) {
			}
			System.err.println("[test] game still running 20 s after the test: thread dump in exit-hang-threads.txt, halting");
			Runtime.getRuntime().halt(0);
		}, "mcupscaler-test-exit-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
	}

	private static void runShots(ClientGameTestContext context) {
		// DLSS_WINDOW=WxH resizes the window (default: keep the size it opened with).
		String size = System.getenv("DLSS_WINDOW");
		if (size != null) {
			String[] window = size.split("x");
			context.getInput().resizeWindow(Integer.parseInt(window[0]), Integer.parseInt(window[1]));
		}
		// MAC_WORLD=<save folder>: opens that saved world (copied in with -PtestRunFiles) instead of a fresh one.
		String savedWorld = System.getenv("MAC_WORLD");
		if (savedWorld != null) {
			context.runOnClient(mc -> mc.createWorldOpenFlows().openWorld(savedWorld, () -> {}));
			context.waitFor(mc -> mc.level != null && mc.player != null && mc.gui.screen() == null, 3000);
			// MAC_ROOM=1: a closed room lit by lamps around the player (in the test's copy of the world), like the
			// player's storage room: dim, so Minecraft's vignette is strong.
			if (System.getenv("MAC_ROOM") != null) {
				context.runOnClient(mc -> {
					var server = mc.getSingleplayerServer();
					net.minecraft.core.BlockPos p = mc.player.blockPosition();
					String at = p.getX() + " " + p.getY() + " " + p.getZ();
					server.execute(() -> {
						var source = server.createCommandSourceStack().withSuppressedOutput();
						java.util.function.Consumer<String> run = command -> server.getCommands().performPrefixedCommand(source, "execute positioned " + at + " run " + command);
						run.accept("fill ~-20 ~-2 ~-20 ~20 ~7 ~20 minecraft:dark_oak_planks hollow");
						for (int x = -19; x <= 19; x += 2) for (int z = -19; z <= 19; z += 2) {
							// A checkered floor of light and dark wood, and lamps in the ceiling every 4 blocks.
							run.accept("fill ~" + x + " ~-2 ~" + z + " ~" + (x + 1) + " ~-2 ~" + (z + 1) + " minecraft:" + ((x + z) % 4 == 0 ? "birch_planks" : "spruce_planks"));
						}
						for (int x = -18; x <= 18; x += 4) for (int z = -18; z <= 18; z += 4) {
							run.accept("setblock ~" + x + " ~7 ~" + z + " minecraft:glowstone");
						}
						for (int x = -18; x <= 18; x += 6) {
							run.accept("fill ~" + x + " ~-1 ~-19 ~" + x + " ~4 ~-19 minecraft:chest");
							run.accept("fill ~" + x + " ~-1 ~19 ~" + x + " ~4 ~19 minecraft:chest");
						}
					});
				});
			}
			// Distant Horizons loads its saved terrain in the background.
			context.waitTicks(600);
			macPerf(context);
			// The world stays open: closing it hangs on Distant Horizons saving, so the run ends with the game test's
			// "finished while a server is still running" failure, which is expected here.
			return;
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(state -> state.setSeed("mcupscaler")).create()) {
			try {
				server = singleplayer.getServer();
				// No mobs: they wander into shots and push the player around.
				server.runCommand("gamerule spawn_mobs false");
				server.runCommand("kill @e[type=!player]");
				context.runOnClient(mc -> {
					mc.options.renderDistance().set(12);
					mc.options.framerateLimit().set(260);
					mc.options.enableVsync().set(false);
					mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
				});
				if (System.getenv("DLSS_FAR") != null) {
					// High above the terrain looking at the horizon: far chunks and Distant Horizons terrain fill the view.
					server.runCommand("gamemode creative @a");
					context.waitTicks(20);
					server.runCommand("tp @a ~ 200 ~");
					context.waitTicks(20);
					context.runOnClient(mc -> {
						mc.player.getAbilities().flying = true;
						mc.options.renderDistance().set(16);
					});
					context.waitTicks(20);
					context.runOnClient(mc -> UpscalerMod.LOGGER.info("[test] far view from {}, flying {}", mc.player.position(),
						mc.player.getAbilities().flying));
					pitch = 8.0F;
				}
				context.getInput().lookAt(YAW, pitch);
				context.waitTicks(System.getenv("DLSS_FAR") != null ? 1200 : 300);
				if (System.getenv("MAC_PERF") != null) {
					macPerf(context);
					return;
				}
				if (System.getenv("DLSS_BENCH") != null) {
					benchmark(context);
					return;
				}
				if (System.getenv("DLSS_SHAKE") != null) {
					shake(context);
					return;
				}
				if (System.getenv("DLSS_FG") != null) {
					frameGen(context);
					return;
				}
				if (System.getenv("DLSS_FSR") != null) {
					fsr(context);
					return;
				}
				if (System.getenv("DLSS_MOBS") != null) {
					mobs(context);
					return;
				}
				if (System.getenv("DLSS_FLICKER") != null) {
					flicker(context);
					return;
				}
				UpscalerConfig.Upscaler D = UpscalerConfig.Upscaler.DLSS, B = UpscalerConfig.Upscaler.BILINEAR;
				still(context, "s_native", false, D, UpscalerConfig.Quality.QUALITY);
				still(context, "s_bilinear_50", true, B, UpscalerConfig.Quality.PERFORMANCE);
				still(context, "s_dlaa", true, D, UpscalerConfig.Quality.DLAA);
				still(context, "s_dlss_quality", true, D, UpscalerConfig.Quality.QUALITY);
				still(context, "s_dlss_performance", true, D, UpscalerConfig.Quality.PERFORMANCE);
				still(context, "s_dlss_ultra_performance", true, D, UpscalerConfig.Quality.ULTRA_PERFORMANCE);

				turn(context, "m_native", false, D, UpscalerConfig.Quality.PERFORMANCE);
				turn(context, "m_dlss_performance", true, D, UpscalerConfig.Quality.PERFORMANCE);
				turn(context, "m_bilinear_50", true, B, UpscalerConfig.Quality.PERFORMANCE);
			} finally {
				// Before the world closes: closing it is what hangs.
				startExitWatchdog();
			}
		}
	}

	private static void configure(ClientGameTestContext context, String name, boolean enabled, UpscalerConfig.Upscaler upscaler,
		UpscalerConfig.Quality quality) {
		context.runOnClient(mc -> {
			UpscalerConfig.enabled = enabled;
			UpscalerConfig.upscaler = upscaler;
			UpscalerConfig.quality = quality;
			UpscalerMod.LOGGER.info("[test] {} -> {}", name, UpscalerMod.statusLine());
		});
	}

	private static void still(ClientGameTestContext context, String name, boolean enabled, UpscalerConfig.Upscaler upscaler, UpscalerConfig.Quality quality) {
		configure(context, name, enabled, upscaler, quality);
		// Same time of day for every shot (the sun keeps moving otherwise, which skews the comparisons).
		server.runCommand("time set 1000");
		context.getInput().lookAt(YAW, pitch);
		context.waitTicks(60);
		context.takeScreenshot(name);
	}

	/**
	 * Turns the camera by a fixed amount every rendered frame (not every tick), so the screenshot frame itself is
	 * mid-motion. Frames are counted from a fresh start, so every mode ends at the same yaw.
	 */
	private static void turn(ClientGameTestContext context, String name, boolean enabled, UpscalerConfig.Upscaler upscaler,
		UpscalerConfig.Quality quality) {
		configure(context, name, enabled, upscaler, quality);
		server.runCommand("time set 1000");
		context.getInput().lookAt(YAW - 20.0F, pitch);
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
			UpscalerMod.LOGGER.info("[test] {} captured after {} turning frames, yaw {}", name, frame[0], mc.player.getYRot());
		});
	}

	/**
	 * Pigs sliding sideways in front of the camera (DLSS_MOBS=1): ghosting compared with native.
	 */
	private static void mobs(ClientGameTestContext context) {
		server.runCommand("time set 1000");
		context.getInput().lookAt(YAW, 5.0F);
		context.waitTicks(400);
		mobShot(context, "e_native", false);
		mobShot(context, "e_dlss_quality", true);
	}

	private static void mobShot(ClientGameTestContext context, String name, boolean enabled) {
		configure(context, name, enabled, UpscalerConfig.Upscaler.DLSS, UpscalerConfig.Quality.QUALITY);
		// Five pigs in a row 7 blocks ahead, moving to the camera's right (yaw YAW: forward = (-sin, cos), right = (-cos, -sin)).
		double yaw = Math.toRadians(YAW);
		double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		// Out of sight first: killing them in place leaves death particles in the next shot.
		server.runCommand("tp @e[type=pig] ~ -400 ~");
		server.runCommand("kill @e[type=pig]");
		context.waitTicks(40);
		for (int i = 0; i < 5; i++) {
			double side = -6.0 + 1.5 * i;
			server.runCommand(String.format(java.util.Locale.ROOT, "execute as @p at @s run summon pig ~%.2f ~ ~%.2f {NoAI:1b,NoGravity:1b,Silent:1b}",
				fx * (7.0 + i) + rx * side, fz * (7.0 + i) + rz * side));
		}
		context.waitTicks(5);
		double step = 0.2;
		for (int t = 0; t < 30; t++) {
			server.runCommand(String.format(java.util.Locale.ROOT, "execute as @e[type=pig] at @s run tp @s ~%.3f ~ ~%.3f", rx * step, rz * step));
			context.waitTick();
		}
		context.takeScreenshot(name);
	}

	/**
	 * Still camera, consecutive screenshots per mode (DLSS_FLICKER=1, with DLSS_FAR=1): flicker shows up as pixels that
	 * change between frames although nothing moves. Compare with _deps/flicker.py.
	 */
	private static void flicker(ClientGameTestContext context) {
		server.runCommand("weather clear 100000");
		Object[][] modes = {{"native", false, UpscalerConfig.Quality.QUALITY}, {"dlss_quality", true, UpscalerConfig.Quality.QUALITY},
			{"dlaa", true, UpscalerConfig.Quality.DLAA}};
		for (Object[] mode : modes) {
			String name = (String) mode[0];
			configure(context, name, (boolean) mode[1], UpscalerConfig.Upscaler.DLSS, (UpscalerConfig.Quality) mode[2]);
			server.runCommand("time set 1000");
			context.getInput().lookAt(YAW, pitch);
			context.waitTicks(80);
			for (int i = 0; i < 8; i++) {
				context.takeScreenshot("f_" + name + "_" + i);
				context.waitTicks(1);
			}
		}
	}

	/**
	 * macOS (MAC_PERF=1, best with MFX_PROFILE=1): rendered FPS and GPU time per upscaler while the camera turns, then FSR and
	 * MetalFX frame generation. Judge frame generation from a screen recording of the run (macOS screencapture -v), not from
	 * the game's own images: each phase logs "[phase] start ... at <wall clock ms>" to line the recording up. Run at the
	 * display's size with the player's settings (DLSS_WINDOW=3024x1964 MAC_FULLSCREEN=1 MAC_VSYNC=1; the test window is
	 * 854x480 otherwise). MAC_FG_CAP=a,b picks the frame generation phases, MAC_MODES=a,b the upscaler modes, MAC_FG_ONLY=1
	 * skips the upscaler modes.
	 */
	private static void macPerf(ClientGameTestContext context) {
		boolean savedWorld = System.getenv("MAC_WORLD") != null;
		float[] baseYaw = {YAW};
		// Where every frame generation phase starts (one block up, flying): each one is moved back here first, so the
		// phases don't wander off (through the room's walls) one after another.
		double[] home = new double[4];
		context.runOnClient(mc -> {
			if (savedWorld) {
				baseYaw[0] = mc.player.getYRot();
			}
			home[0] = mc.player.getX();
			home[1] = mc.player.getY() + 1.0;
			home[2] = mc.player.getZ();
			home[3] = mc.player.getXRot();
			// Creative, so the player can fly and stays put between phases instead of falling (the test's own copy of
			// the world).
			if (mc.getSingleplayerServer() != null) {
				var server = mc.getSingleplayerServer();
				server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "gamemode creative @a"));
			}
			// macOS throttles windows that aren't in front: bring the game's to the front.
			org.lwjgl.sdl.SDLHints.SDL_SetHint(org.lwjgl.sdl.SDLHints.SDL_HINT_FORCE_RAISEWINDOW, "1");
			org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow(mc.getWindow().handle());
			if (!savedWorld) {
				mc.options.renderDistance().set(16);
			}
			mc.options.framerateLimit().set(260);
			mc.options.enableVsync().set(false);
			// No input during the test: the default "afk" limit would hold the game at 30 fps.
			mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
			UpscalerConfig.frameGeneration = UpscalerConfig.FrameGeneration.OFF;
		});
		context.waitTicks(300);
		record Mode(String name, boolean enabled, UpscalerConfig.Upscaler upscaler, UpscalerConfig.Quality quality) {
		}
		UpscalerConfig.Quality perf = UpscalerConfig.Quality.PERFORMANCE;
		Mode[] modes = {
			new Mode("native", false, UpscalerConfig.Upscaler.METALFX, perf),
			new Mode("metalfx 50%", true, UpscalerConfig.Upscaler.METALFX, perf),
			new Mode("fsr 50%", true, UpscalerConfig.Upscaler.FSR, perf),
			new Mode("metalfx spatial 50%", true, UpscalerConfig.Upscaler.METALFX_SPATIAL, perf),
			new Mode("bilinear 50%", true, UpscalerConfig.Upscaler.BILINEAR, perf),
			new Mode("native", false, UpscalerConfig.Upscaler.METALFX, perf),
			new Mode("metalfx 50% sharpness 0", true, UpscalerConfig.Upscaler.METALFX, perf),
			new Mode("fsr 50% sharpness 0", true, UpscalerConfig.Upscaler.FSR, perf)};
		for (Mode mode : System.getenv("MAC_FG_ONLY") != null ? new Mode[0] : modes) {
			if (System.getenv("MAC_MODES") != null && java.util.Arrays.stream(System.getenv("MAC_MODES").split(",")).noneMatch(mode.name::startsWith)) {
				continue;
			}
			context.runOnClient(mc -> {
				UpscalerConfig.enabled = mode.enabled;
				UpscalerConfig.upscaler = mode.upscaler;
				UpscalerConfig.quality = mode.quality;
				UpscalerConfig.sharpness = mode.name.contains("sharpness 0") ? 0.0F : 1.0F;
			});
			context.waitTicks(100);
			long[] stats = new long[3];
			context.runOnClient(mc -> {
				WorldUpscaler.upscaleGpuMillis();
				for (int stage = 0; stage < 3; stage++) {
					dev.mcupscaler.NativeBridge.takeStageMicros(stage);
				}
				WorldUpscaler.frameHook = () -> turn(mc, stats, 0.05F);
			});
			context.waitTicks(200);
			context.runOnClient(mc -> {
				WorldUpscaler.frameHook = null;
				double ms = (stats[2] - stats[1]) / 1.0e6 / Math.max(1, stats[0] - 1);
				UpscalerMod.LOGGER.info("[macperf] window {}x{}, vsync {}, limit {}, focused {}", mc.getWindow().getWidth(), mc.getWindow().getHeight(),
					mc.options.enableVsync().get(), mc.options.framerateLimit().get(), mc.getWindow().isFocused());
				UpscalerMod.LOGGER.info("[macperf] {}: {} fps ({} ms/frame), upscale GPU {} ms, stages us {}/{}/{}", mode.name,
					String.format("%.1f", 1000.0 / ms), String.format("%.2f", ms), String.format("%.2f", WorldUpscaler.upscaleGpuMillis()),
					dev.mcupscaler.NativeBridge.takeStageMicros(0), dev.mcupscaler.NativeBridge.takeStageMicros(1),
					dev.mcupscaler.NativeBridge.takeStageMicros(2));
			});
		}

		// Frame generation phases:
		// - "uncapped": the camera turns at 40 degrees per second, no frame rate cap.
		// - "look": like a player: walks back and forth (8 blocks either way, flying so it never falls) while looking left
		//   and right (35 degrees either way, every 2.5 s). "30 look" at a 30 fps cap; "30 jitter look" adds 0-15 ms of CPU
		//   time per frame on top, for uneven frame times like on a server.
		// - "shake": shaking the mouse while standing still (25 degrees left and right 3 times a second, 10 up and down).
		for (UpscalerConfig.Upscaler upscaler : savedWorld ? new UpscalerConfig.Upscaler[] {UpscalerConfig.Upscaler.FSR}
			: new UpscalerConfig.Upscaler[] {UpscalerConfig.Upscaler.FSR, UpscalerConfig.Upscaler.METALFX})
		for (UpscalerConfig.FrameGeneration fg : new UpscalerConfig.FrameGeneration[] {UpscalerConfig.FrameGeneration.OFF,
			UpscalerConfig.FrameGeneration.FSR, UpscalerConfig.FrameGeneration.METALFX}) {
			for (String phase : fg == UpscalerConfig.FrameGeneration.OFF ? new String[] {"uncapped"} : new String[] {"uncapped", "30 look", "30 jitter look", "look", "shake"}) {
				if (System.getenv("MAC_FG_CAP") != null && !java.util.List.of(System.getenv("MAC_FG_CAP").split(",")).contains(phase)) {
					continue;
				}
				context.runOnClient(mc -> {
					UpscalerConfig.enabled = true;
					UpscalerConfig.upscaler = upscaler;
					UpscalerConfig.quality = perf;
					UpscalerConfig.frameGeneration = fg;
					mc.options.framerateLimit().set(phase.startsWith("30 ") ? 30 : 260);
					mc.options.enableVsync().set(System.getenv("MAC_VSYNC") != null);
					// The option alone can already be true (copied from the player's options) while the test window is
					// still the 854x480 default: always switch the window itself.
					if (System.getenv("MAC_FULLSCREEN") != null && mc.getWindow().getWidth() < 1600) {
						mc.options.fullscreen().set(true);
						mc.getWindow().setFullscreen(false);
						mc.getWindow().setFullscreen(true);
					}
				});
				context.waitTicks(100);
				long[] stats = new long[3];
				long start = System.nanoTime();
				boolean look = phase.endsWith("look");
				boolean shake = phase.equals("shake");
				boolean jitter = phase.contains("jitter");
				java.util.Random random = new java.util.Random(1);
				context.runOnClient(mc -> {
					dev.mcupscaler.NativeBridge.takeStageMicros(3);
					mc.player.getAbilities().flying = true;
					mc.player.snapTo(home[0], home[1], home[2], baseYaw[0], (float)home[3]);
					mc.player.setDeltaMovement(0.0, 0.0, 0.0);
					UpscalerMod.LOGGER.info("[phase] start {} upscaler, fg {} {} at {}", upscaler, fg, phase, System.currentTimeMillis());
					WorldUpscaler.frameHook = () -> {
						long now = System.nanoTime();
						if (stats[0]++ == 0) {
							stats[1] = now;
						}
						stats[2] = now;
						double seconds = (now - start) / 1.0e9;
						if (jitter) {
							java.util.concurrent.locks.LockSupport.parkNanos(random.nextInt(15_000_000));
						}
						float yaw;
						if (shake) {
							yaw = baseYaw[0] + 25.0F * (float)Math.sin(seconds * 2.0 * Math.PI * 3.0);
							float pitch = (float)home[3] + 10.0F * (float)Math.sin(seconds * 2.0 * Math.PI * 2.3);
							mc.player.setXRot(pitch);
							mc.player.xRotO = pitch;
						} else if (look) {
							double sideways = Math.toRadians(baseYaw[0] + 90.0), d = 8.0 * Math.sin(seconds * 2.0 * Math.PI / 12.0);
							double x = home[0] + Math.cos(sideways) * d, z = home[2] + Math.sin(sideways) * d;
							mc.player.setPos(x, home[1], z);
							mc.player.xo = x;
							mc.player.yo = home[1];
							mc.player.zo = z;
							mc.player.setDeltaMovement(0.0, 0.0, 0.0);
							yaw = baseYaw[0] + 35.0F * (float)Math.sin(seconds * 2.0 * Math.PI / 2.5);
						} else {
							yaw = baseYaw[0] + 40.0F * (float)seconds;
						}
						mc.player.setYRot(yaw);
						mc.player.yRotO = yaw;
					};
				});
				context.waitTicks(160);
				context.runOnClient(mc -> {
					WorldUpscaler.frameHook = null;
					double ms = (stats[2] - stats[1]) / 1.0e6 / Math.max(1, stats[0] - 1);
					UpscalerMod.LOGGER.info("[macperf] {} upscaler, fg {} {}: {} rendered fps; {}; frame gen GPU {} us; focused {}", upscaler, fg, phase,
						String.format("%.1f", 1000.0 / ms), dev.mcupscaler.UpscalerDebugEntry.frameGenLine(),
						dev.mcupscaler.NativeBridge.takeStageMicros(3), mc.getWindow().isFocused());
				});
				context.waitTicks(40);
			}
		}
		context.runOnClient(mc -> UpscalerConfig.frameGeneration = UpscalerConfig.FrameGeneration.OFF);
		context.waitTicks(20);
	}

	private static void turn(net.minecraft.client.Minecraft mc, long[] stats, float degreesPerFrame) {
		long now = System.nanoTime();
		if (stats[0]++ == 0) {
			stats[1] = now;
		}
		stats[2] = now;
		float yaw = YAW + degreesPerFrame * stats[0];
		mc.player.setYRot(yaw);
		mc.player.yRotO = yaw;
	}

	/** Frame generation (DLSS_FG=1): rendered frame rates at a 60 fps cap and uncapped, with DLAA and without upscaling. */
	private static void frameGen(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			mc.options.renderDistance().set(24);
			mc.options.enableVsync().set(false);
			UpscalerConfig.upscaler = UpscalerConfig.Upscaler.DLSS;
			UpscalerConfig.quality = UpscalerConfig.Quality.DLAA;
		});
		context.waitTicks(300);
		String[] phases = {"dlaa, off, 60 fps cap", "dlaa, on, 60 fps cap", "dlaa, off, uncapped", "dlaa, on, uncapped",
			"native, off, uncapped", "native, on, uncapped"};
		for (String phase : phases) {
			context.runOnClient(mc -> {
				UpscalerConfig.enabled = phase.startsWith("dlaa");
				UpscalerConfig.frameGeneration = phase.contains(", on") ? UpscalerConfig.FrameGeneration.DLSS : UpscalerConfig.FrameGeneration.OFF;
				mc.options.framerateLimit().set(phase.contains("60") ? 60 : 260);
			});
			context.waitTicks(100);
			long[] stats = new long[3];
			context.runOnClient(mc -> WorldUpscaler.frameHook = () -> {
				long now = System.nanoTime();
				if (stats[0]++ == 0) {
					stats[1] = now;
				}
				stats[2] = now;
				float yaw = YAW + 0.4F * stats[0];
				mc.player.setYRot(yaw);
				mc.player.yRotO = yaw;
			});
			context.runOnClient(mc -> UpscalerMod.LOGGER.info("[fg-capture] {}", phase));
			context.waitTicks(200);
			context.runOnClient(mc -> {
				WorldUpscaler.frameHook = null;
				double ms = (stats[2] - stats[1]) / 1.0e6 / Math.max(1, stats[0] - 1);
				String fg = dev.mcupscaler.UpscalerDebugEntry.frameGenLine();
				UpscalerMod.LOGGER.info("[fg] {}: {} rendered fps; {}", phase, String.format("%.0f", 1000.0 / ms), fg);
			});
		}
		context.runOnClient(mc -> UpscalerConfig.frameGeneration = UpscalerConfig.FrameGeneration.OFF);
		context.waitTicks(20);
	}

	/**
	 * Frame generation under a shaking camera (DLSS_SHAKE=1): 60 fps cap (DLSS_SHAKE_FPS), the player walking back and forth (hand bobbing)
	 * with a sword, each phase recorded off the screen with ffmpeg at 144 fps (build/run/clientGameTest/shake_*.mp4) so
	 * the generated frames are in the video. DLSS_SHAKE=fsr,dlss picks the frame generators (default: fsr).
	 */
	/** DLSS_SHAKE_FPS: the frame cap while shaking (default 60; lower makes frame generation's artifacts bigger). */
	private static final int SHAKE_FPS = System.getenv("DLSS_SHAKE_FPS") != null ? Integer.parseInt(System.getenv("DLSS_SHAKE_FPS")) : 60;

	private static void shake(ClientGameTestContext context) {
		// A flat snow field: the hand and the screen edges against bright white, nothing next to the camera.
		// (fill is limited to 32768 blocks a command.)
		for (int y = 0; y < 32; y += 4) {
			server.runCommand("execute at @p run fill ~-40 ~" + y + " ~-40 ~40 ~" + (y + 3) + " ~40 minecraft:air");
		}
		server.runCommand("execute at @p run fill ~-40 ~-1 ~-40 ~40 ~-1 ~40 minecraft:snow_block");
		// The fill breaks trees and grass: their drops (leaf litter etc.) go, and nothing picked up stays in the hand.
		server.runCommand("kill @e[type=!player]");
		server.runCommand("clear @a");
		// DLSS_SHAKE_ITEM / DLSS_SHAKE_OFFHAND: what the player holds (default: nothing, the bare arm).
		Runnable give = () -> {
			for (String[] slot : new String[][] {{"DLSS_SHAKE_ITEM", "mainhand"}, {"DLSS_SHAKE_OFFHAND", "offhand"}}) {
				String item = System.getenv(slot[0]);
				if (item != null && !item.equals("air")) {
					server.runCommand("item replace entity @a weapon." + slot[1] + " with minecraft:" + item);
				}
			}
		};
		give.run();
		server.runCommand("time set 6000");
		server.runCommand("gamerule advance_time false");
		context.runOnClient(mc -> {
			mc.options.enableVsync().set(false);
			mc.options.framerateLimit().set(SHAKE_FPS);
			mc.options.bobView().set(true);
		});
		context.waitTicks(100);
		// Leaves left without their logs decay meanwhile and drop saplings: cleared again.
		server.runCommand("kill @e[type=!player]");
		server.runCommand("clear @a");
		give.run();
		String which = System.getenv("DLSS_SHAKE");
		java.util.List<String> phases = new java.util.ArrayList<>();
		for (String fg : (which.equals("1") ? "fsr" : which).split(",")) {
			phases.add("fg only, " + fg + " fg");
			phases.add("fsr quality, " + fg + " fg");
		}
		for (String phase : phases) {
			context.runOnClient(mc -> {
				UpscalerConfig.enabled = !phase.startsWith("fg only");
				UpscalerConfig.upscaler = UpscalerConfig.Upscaler.FSR;
				UpscalerConfig.quality = UpscalerConfig.Quality.QUALITY;
				UpscalerConfig.frameGeneration = phase.endsWith("dlss fg") ? UpscalerConfig.FrameGeneration.DLSS : UpscalerConfig.FrameGeneration.FSR;
			});
			context.waitTicks(60);
			long[] frames = new long[1];
			context.runOnClient(mc -> WorldUpscaler.frameHook = () -> {
				// A shake: fast yaw and pitch swings (tens of degrees), as when whipping the mouse around.
				double t = frames[0]++ / (double)SHAKE_FPS;
				float yaw = YAW + (float)(30.0 * Math.sin(t * 2.0 * Math.PI * 1.3) + 10.0 * Math.sin(t * 2.0 * Math.PI * 3.1));
				float pitchNow = pitch + (float)(12.0 * Math.sin(t * 2.0 * Math.PI * 1.7) + 5.0 * Math.sin(t * 2.0 * Math.PI * 4.3));
				mc.player.setYRot(yaw);
				mc.player.setXRot(pitchNow);
				// DLSS_SHAKE_SWING: swing the arm a couple of times a second as well.
				if (System.getenv("DLSS_SHAKE_SWING") != null && frames[0] % (SHAKE_FPS / 2) == 0) {
					mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
				}
			});
			Process ffmpeg = context.computeOnClient(mc -> startRecording(mc, "shake_" + phase.replace(", ", "_").replace(' ', '_')));
			for (int i = 0; i < 4; i++) {
				context.getInput().holdKeyFor(options -> options.keyUp, 20);
				context.getInput().holdKeyFor(options -> options.keyDown, 20);
			}
			context.runOnClient(mc -> {
				WorldUpscaler.frameHook = null;
				UpscalerMod.LOGGER.info("[shake] {}: {} fps; {}; {}", phase, mc.getFps(), UpscalerMod.statusLine(),
					dev.mcupscaler.UpscalerDebugEntry.frameGenLine());
			});
			if (ffmpeg != null) {
				try {
					ffmpeg.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
		context.runOnClient(mc -> UpscalerConfig.frameGeneration = UpscalerConfig.FrameGeneration.OFF);
		context.waitTicks(20);
	}

	/** Records the game window's area of the screen for 7 s (ffmpeg's Desktop Duplication grabber), or null. */
	private static Process startRecording(net.minecraft.client.Minecraft mc, String name) {
		int[] x = new int[1], y = new int[1];
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			java.nio.IntBuffer px = stack.mallocInt(1), py = stack.mallocInt(1);
			org.lwjgl.sdl.SDLVideo.SDL_GetWindowPosition(mc.getWindow().handle(), px, py);
			x[0] = px.get(0);
			y[0] = py.get(0);
		}
		int w = mc.getWindow().getWidth() & ~1, h = mc.getWindow().getHeight() & ~1;
		java.io.File out = new java.io.File(name + ".mp4").getAbsoluteFile();
		String grab = "ddagrab=output_idx=0:framerate=144:draw_mouse=0:video_size=" + w + "x" + h + ":offset_x=" + x[0] + ":offset_y=" + y[0];
		try {
			Process process = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", grab, "-t", "7",
				"-c:v", "h264_nvenc", "-rc", "constqp", "-qp", "14", out.getPath())
				.redirectErrorStream(true).redirectOutput(new java.io.File(name + ".ffmpeg.log")).start();
			UpscalerMod.LOGGER.info("[shake] recording {} at {},{} {}x{}", out, x[0], y[0], w, h);
			return process;
		} catch (java.io.IOException e) {
			UpscalerMod.LOGGER.warn("[shake] could not start ffmpeg", e);
			return null;
		}
	}

	/** AMD FSR upscaling and frame generation (DLSS_FSR=1): stills, a turn, then FSR frame generation with and without upscaling. */
	private static void fsr(ClientGameTestContext context) {
		UpscalerConfig.Upscaler F = UpscalerConfig.Upscaler.FSR, D = UpscalerConfig.Upscaler.DLSS;
		still(context, "f_native", false, F, UpscalerConfig.Quality.QUALITY);
		still(context, "f_dlss_quality", true, D, UpscalerConfig.Quality.QUALITY);
		still(context, "f_fsr_native_aa", true, F, UpscalerConfig.Quality.DLAA);
		still(context, "f_fsr_quality", true, F, UpscalerConfig.Quality.QUALITY);
		context.runOnClient(mc -> UpscalerConfig.sharpness = 0.4F);
		still(context, "f_fsr_quality_sharp40", true, F, UpscalerConfig.Quality.QUALITY);
		context.runOnClient(mc -> UpscalerConfig.sharpness = 0.0F);
		still(context, "f_fsr_performance", true, F, UpscalerConfig.Quality.PERFORMANCE);
		turn(context, "m_fsr_performance", true, F, UpscalerConfig.Quality.PERFORMANCE);
		context.runOnClient(mc -> {
			mc.options.enableVsync().set(false);
			mc.options.framerateLimit().set(60);
			UpscalerMod.LOGGER.info("[fsr] version {}", dev.mcupscaler.DlssNative.fsrVersion());
		});
		String[] phases = {"fsr quality, fg off", "fsr quality, fsr fg", "native, fsr fg", "dlss quality, fsr fg", "fsr quality, dlss fg"};
		for (String phase : phases) {
			context.runOnClient(mc -> {
				UpscalerConfig.enabled = !phase.startsWith("native");
				UpscalerConfig.upscaler = phase.startsWith("dlss") ? D : F;
				UpscalerConfig.quality = UpscalerConfig.Quality.QUALITY;
				UpscalerConfig.frameGeneration = phase.endsWith("fsr fg") ? UpscalerConfig.FrameGeneration.FSR
					: phase.endsWith("dlss fg") ? UpscalerConfig.FrameGeneration.DLSS : UpscalerConfig.FrameGeneration.OFF;
			});
			context.waitTicks(100);
			context.runOnClient(mc -> {
				String fg = dev.mcupscaler.UpscalerDebugEntry.frameGenLine();
				UpscalerMod.LOGGER.info("[fsr] {}: {} fps; {}; {}", phase, mc.getFps(), UpscalerMod.statusLine(),
					fg);
			});
		}
		context.runOnClient(mc -> UpscalerConfig.frameGeneration = UpscalerConfig.FrameGeneration.OFF);
		context.waitTicks(20);
	}

	/** Average frame time per mode (DLSS_BENCH=1), camera slowly turning, no frame cap. */
	private static void benchmark(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			if (System.getenv("DLSS_FAR") == null) {
				mc.options.renderDistance().set(24);
			}
			mc.options.framerateLimit().set(260);
			mc.options.enableVsync().set(false);
		});
		context.waitTicks(400);
		record Mode(String name, boolean enabled, UpscalerConfig.Upscaler upscaler, UpscalerConfig.Quality quality, UpscalerConfig.Preset preset) {
		}
		UpscalerConfig.Upscaler D = UpscalerConfig.Upscaler.DLSS;
		Mode[] modes = {
			new Mode("native", false, D, UpscalerConfig.Quality.QUALITY, UpscalerConfig.Preset.AUTO),
			new Mode("dlaa", true, D, UpscalerConfig.Quality.DLAA, UpscalerConfig.Preset.AUTO),
			new Mode("quality", true, D, UpscalerConfig.Quality.QUALITY, UpscalerConfig.Preset.AUTO),
			new Mode("quality preset E", true, D, UpscalerConfig.Quality.QUALITY, UpscalerConfig.Preset.E),
			new Mode("performance", true, D, UpscalerConfig.Quality.PERFORMANCE, UpscalerConfig.Preset.AUTO),
			new Mode("performance preset K", true, D, UpscalerConfig.Quality.PERFORMANCE, UpscalerConfig.Preset.K),
			new Mode("performance preset E", true, D, UpscalerConfig.Quality.PERFORMANCE, UpscalerConfig.Preset.E),
			new Mode("ultra performance", true, D, UpscalerConfig.Quality.ULTRA_PERFORMANCE, UpscalerConfig.Preset.AUTO),
			new Mode("bilinear 50%", true, UpscalerConfig.Upscaler.BILINEAR, UpscalerConfig.Quality.PERFORMANCE, UpscalerConfig.Preset.AUTO),
			new Mode("native", false, D, UpscalerConfig.Quality.QUALITY, UpscalerConfig.Preset.AUTO)};
		for (Mode mode : modes) {
			context.runOnClient(mc -> {
				UpscalerConfig.enabled = mode.enabled;
				UpscalerConfig.upscaler = mode.upscaler;
				UpscalerConfig.quality = mode.quality;
				UpscalerConfig.preset = mode.preset;
			});
			context.waitTicks(100);
			long[] stats = new long[3]; // frames, first ns, last ns
			context.runOnClient(mc -> WorldUpscaler.frameHook = () -> {
				long now = System.nanoTime();
				if (stats[0]++ == 0) {
					stats[1] = now;
				}
				stats[2] = now;
				float yaw = YAW + 0.05F * stats[0];
				mc.player.setYRot(yaw);
				mc.player.yRotO = yaw;
			});
			context.waitTicks(200);
			context.runOnClient(mc -> {
				WorldUpscaler.frameHook = null;
				double ms = (stats[2] - stats[1]) / 1.0e6 / Math.max(1, stats[0] - 1);
				float[] gpu = dev.mcupscaler.DlssNative.gpuTimes();
				String gpuText = gpu == null || !mode.enabled ? "" : String.format(java.util.Locale.ROOT, ", our GPU work %.2f ms (mv %.2f, dlss %.2f, copy %.2f)",
					gpu[0] + gpu[1] + gpu[2], gpu[0], gpu[1], gpu[2]);
				UpscalerMod.LOGGER.info("[bench] {}: {} frames, {} ms/frame, {} fps{} ({})", mode.name, stats[0], String.format("%.2f", ms),
					String.format("%.0f", 1000.0 / ms), gpuText, UpscalerMod.statusLine());
			});
		}
	}
}
