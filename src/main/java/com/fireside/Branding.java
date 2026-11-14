package com.fireside;

/**
 * Project branding.
 * <p>
 * "OSRS Tracker" is a working title. The final name is chosen in task 8.0
 * (see ../osrs-tracker/docs/plan/07-byggeplan.md). Everything in the plugin that
 * shows or depends on the name must read it from here. This mirrors
 * {@code shared/src/branding.ts} in the server repo.
 */
public final class Branding
{
	/** Human-readable name, shown in the plugin list and UI. */
	public static final String DISPLAY_NAME = "OSRS Tracker";

	/**
	 * Plugin Hub internal name. It also names the plugin data folder
	 * ({@code .runelite/plugin-data/<internal name>}).
	 */
	public static final String INTERNAL_NAME = "osrs-tracker";

	/**
	 * RuneLite config group. Must never change after the first public release:
	 * users would lose their saved settings (server URL, API key).
	 */
	public static final String CONFIG_GROUP = "osrs-tracker";

	/** Folder for the on-disk event queue, inside the plugin data folder. */
	public static final String QUEUE_DIR_NAME = "queue";

	private Branding()
	{
	}
}
