package dev.dlssmc.test;

import dev.dlssmc.DlssConfig;
import dev.dlssmc.DlssMod;
import dev.dlssmc.WorldUpscaler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Creates a world and takes screenshots for comparing the DLSS modes (build/run/clientGameTest/screenshots).
 * DLSS_BENCH=1 runs a frame-time benchmark instead.
 */
public class DlssScreenshotTest implements FabricClientGameTest {
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
			mc.createWorldOpenFlows().openWorld(levelId, () -> DlssMod.LOGGER.warn("[test] opening {} was cancelled", levelId));
		});
		try {
			context.waitFor(mc -> mc.level != null && mc.player != null && mc.gui.screen() == null, 3000);
			context.runOnClient(mc -> DlssMod.LOGGER.info("[test] opened {} at {}, yaw {} pitch {}", levelId, mc.player.position(),
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
			// "user": the settings from config/dlssmc.properties as they are.
			Object[][] modes = {{"user", null, null}, {"native", false, DlssConfig.Quality.QUALITY},
				{"dlss_quality", true, DlssConfig.Quality.QUALITY}};
			for (Object[] mode : modes) {
				String name = (String) mode[0];
				if (mode[1] != null) {
					configure(context, name, (boolean) mode[1], DlssConfig.Upscaler.DLSS, (DlssConfig.Quality) mode[2]);
					context.runOnClient(mc -> DlssConfig.frameGeneration = false);
				} else {
					context.runOnClient(mc -> DlssMod.LOGGER.info("[test] user settings -> {}", DlssMod.statusLine()));
				}
				context.waitTicks(120);
				// What the window shows (_deps/wincapture.py grabs it), still and then turning very slowly (like a hand on the mouse).
				context.runOnClient(mc -> DlssMod.LOGGER.info("[win-capture] {}", name));
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
					DlssMod.LOGGER.info("[win-capture] {}_turn", name);
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
			// The vignette while turning (it should stay at the screen edges).
			configure(context, "v_turn", true, DlssConfig.Upscaler.DLSS, DlssConfig.Quality.QUALITY);
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
					DlssConfig.enabled = phase.startsWith("dlss");
					DlssConfig.reflex = phase.contains("off") ? DlssConfig.Reflex.OFF : phase.contains("boost") ? DlssConfig.Reflex.BOOST
						: DlssConfig.Reflex.ON;
					dev.dlssmc.Reflex.settingsChanged();
					DlssConfig.frameGeneration = phase.contains("fg");
				});
				context.waitTicks(100);
				context.runOnClient(mc -> {
					String fg = dev.dlssmc.FrameGen.statusLine();
					DlssMod.LOGGER.info("[reflex] {}: {} fps, {}; {}", phase, mc.getFps(), dev.dlssmc.Reflex.statusLine(),
						fg == null ? "frame generation not running" : fg);
				});
			}
			context.runOnClient(mc -> DlssConfig.frameGeneration = false);
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
		}, "dlssmc-test-exit-watchdog");
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
		try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(state -> state.setSeed("dlssmc")).create()) {
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
					context.runOnClient(mc -> DlssMod.LOGGER.info("[test] far view from {}, flying {}", mc.player.position(),
						mc.player.getAbilities().flying));
					pitch = 8.0F;
				}
				context.getInput().lookAt(YAW, pitch);
				context.waitTicks(System.getenv("DLSS_FAR") != null ? 1200 : 300);
				if (System.getenv("DLSS_BENCH") != null) {
					benchmark(context);
					return;
				}
				if (System.getenv("DLSS_FG") != null) {
					frameGen(context);
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
				DlssConfig.Upscaler D = DlssConfig.Upscaler.DLSS, B = DlssConfig.Upscaler.BILINEAR;
				still(context, "s_native", false, D, DlssConfig.Quality.QUALITY);
				still(context, "s_bilinear_50", true, B, DlssConfig.Quality.PERFORMANCE);
				still(context, "s_dlaa", true, D, DlssConfig.Quality.DLAA);
				still(context, "s_dlss_quality", true, D, DlssConfig.Quality.QUALITY);
				still(context, "s_dlss_performance", true, D, DlssConfig.Quality.PERFORMANCE);
				still(context, "s_dlss_ultra_performance", true, D, DlssConfig.Quality.ULTRA_PERFORMANCE);

				turn(context, "m_native", false, D, DlssConfig.Quality.PERFORMANCE);
				turn(context, "m_dlss_performance", true, D, DlssConfig.Quality.PERFORMANCE);
				turn(context, "m_bilinear_50", true, B, DlssConfig.Quality.PERFORMANCE);
			} finally {
				// Before the world closes: closing it is what hangs.
				startExitWatchdog();
			}
		}
	}

	private static void configure(ClientGameTestContext context, String name, boolean enabled, DlssConfig.Upscaler upscaler,
		DlssConfig.Quality quality) {
		context.runOnClient(mc -> {
			DlssConfig.enabled = enabled;
			DlssConfig.upscaler = upscaler;
			DlssConfig.quality = quality;
			DlssMod.LOGGER.info("[test] {} -> {}", name, DlssMod.statusLine());
		});
	}

	private static void still(ClientGameTestContext context, String name, boolean enabled, DlssConfig.Upscaler upscaler, DlssConfig.Quality quality) {
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
	private static void turn(ClientGameTestContext context, String name, boolean enabled, DlssConfig.Upscaler upscaler,
		DlssConfig.Quality quality) {
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
			DlssMod.LOGGER.info("[test] {} captured after {} turning frames, yaw {}", name, frame[0], mc.player.getYRot());
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
		configure(context, name, enabled, DlssConfig.Upscaler.DLSS, DlssConfig.Quality.QUALITY);
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
		Object[][] modes = {{"native", false, DlssConfig.Quality.QUALITY}, {"dlss_quality", true, DlssConfig.Quality.QUALITY},
			{"dlaa", true, DlssConfig.Quality.DLAA}};
		for (Object[] mode : modes) {
			String name = (String) mode[0];
			configure(context, name, (boolean) mode[1], DlssConfig.Upscaler.DLSS, (DlssConfig.Quality) mode[2]);
			server.runCommand("time set 1000");
			context.getInput().lookAt(YAW, pitch);
			context.waitTicks(80);
			for (int i = 0; i < 8; i++) {
				context.takeScreenshot("f_" + name + "_" + i);
				context.waitTicks(1);
			}
		}
	}

	/** Frame generation (DLSS_FG=1): rendered frame rates at a 60 fps cap and uncapped, with DLAA and without upscaling. */
	private static void frameGen(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			mc.options.renderDistance().set(24);
			mc.options.enableVsync().set(false);
			DlssConfig.upscaler = DlssConfig.Upscaler.DLSS;
			DlssConfig.quality = DlssConfig.Quality.DLAA;
		});
		context.waitTicks(300);
		String[] phases = {"dlaa, off, 60 fps cap", "dlaa, on, 60 fps cap", "dlaa, off, uncapped", "dlaa, on, uncapped",
			"native, off, uncapped", "native, on, uncapped"};
		for (String phase : phases) {
			context.runOnClient(mc -> {
				DlssConfig.enabled = phase.startsWith("dlaa");
				DlssConfig.frameGeneration = phase.contains(", on");
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
			context.runOnClient(mc -> DlssMod.LOGGER.info("[fg-capture] {}", phase));
			context.waitTicks(200);
			context.runOnClient(mc -> {
				WorldUpscaler.frameHook = null;
				double ms = (stats[2] - stats[1]) / 1.0e6 / Math.max(1, stats[0] - 1);
				String fg = dev.dlssmc.FrameGen.statusLine();
				DlssMod.LOGGER.info("[fg] {}: {} rendered fps; {}", phase, String.format("%.0f", 1000.0 / ms), fg == null ? "frame generation not running" : fg);
			});
		}
		context.runOnClient(mc -> DlssConfig.frameGeneration = false);
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
		record Mode(String name, boolean enabled, DlssConfig.Upscaler upscaler, DlssConfig.Quality quality, DlssConfig.Preset preset) {
		}
		DlssConfig.Upscaler D = DlssConfig.Upscaler.DLSS;
		Mode[] modes = {
			new Mode("native", false, D, DlssConfig.Quality.QUALITY, DlssConfig.Preset.AUTO),
			new Mode("dlaa", true, D, DlssConfig.Quality.DLAA, DlssConfig.Preset.AUTO),
			new Mode("quality", true, D, DlssConfig.Quality.QUALITY, DlssConfig.Preset.AUTO),
			new Mode("quality preset E", true, D, DlssConfig.Quality.QUALITY, DlssConfig.Preset.E),
			new Mode("performance", true, D, DlssConfig.Quality.PERFORMANCE, DlssConfig.Preset.AUTO),
			new Mode("performance preset K", true, D, DlssConfig.Quality.PERFORMANCE, DlssConfig.Preset.K),
			new Mode("performance preset E", true, D, DlssConfig.Quality.PERFORMANCE, DlssConfig.Preset.E),
			new Mode("ultra performance", true, D, DlssConfig.Quality.ULTRA_PERFORMANCE, DlssConfig.Preset.AUTO),
			new Mode("bilinear 50%", true, DlssConfig.Upscaler.BILINEAR, DlssConfig.Quality.PERFORMANCE, DlssConfig.Preset.AUTO),
			new Mode("native", false, D, DlssConfig.Quality.QUALITY, DlssConfig.Preset.AUTO)};
		for (Mode mode : modes) {
			context.runOnClient(mc -> {
				DlssConfig.enabled = mode.enabled;
				DlssConfig.upscaler = mode.upscaler;
				DlssConfig.quality = mode.quality;
				DlssConfig.preset = mode.preset;
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
				float[] gpu = dev.dlssmc.DlssNative.gpuTimes();
				String gpuText = gpu == null || !mode.enabled ? "" : String.format(java.util.Locale.ROOT, ", our GPU work %.2f ms (mv %.2f, dlss %.2f, copy %.2f)",
					gpu[0] + gpu[1] + gpu[2], gpu[0], gpu[1], gpu[2]);
				DlssMod.LOGGER.info("[bench] {}: {} frames, {} ms/frame, {} fps{} ({})", mode.name, stats[0], String.format("%.2f", ms),
					String.format("%.0f", 1000.0 / ms), gpuText, DlssMod.statusLine());
			});
		}
	}
}
