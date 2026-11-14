package com.fireside;

/**
 * Project branding. Everything in the plugin that shows or depends on the name
 * must read it from here. This mirrors {@code shared/src/branding.ts} in the
 * server repo.
 * <p>
 * Files that cannot read this class must be kept consistent with it by hand:
 * {@code runelite-plugin.properties}, {@code settings.gradle}, {@code group} in
 * {@code build.gradle}, the Java package and class names, and {@code README.md}.
 */
public final class Branding
{
	/** Human-readable name, shown in the plugin list and UI. */
	public static final String DISPLAY_NAME = "Fireside";

	/**
	 * Plugin Hub internal name. Must match the plugin's file name in
	 * {@code runelite/plugin-hub}. It also names the plugin data folder
	 * ({@code .runelite/plugin-data/<internal name>}).
	 */
	public static final String INTERNAL_NAME = "fireside";

	/**
	 * RuneLite config group. FINAL: it must never change, not even in a later
	 * release. Changing it silently resets every user's saved settings
	 * (server URL, API key).
	 */
	public static final String CONFIG_GROUP = "fireside";

	/** Folder for the on-disk event queue, inside the plugin data folder. */
	public static final String QUEUE_DIR_NAME = "queue";

	private Branding()
	{
	}
}
