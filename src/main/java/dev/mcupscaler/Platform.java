package dev.mcupscaler;

import java.util.Locale;

/** The operating system decides the native side: DLSS, FSR and Reflex on Windows, MetalFX and FSR (on Metal) on macOS. */
public final class Platform {
	private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	public static final boolean MAC = OS.startsWith("mac");
	public static final boolean WINDOWS = OS.startsWith("windows");

	private Platform() {
	}
}
