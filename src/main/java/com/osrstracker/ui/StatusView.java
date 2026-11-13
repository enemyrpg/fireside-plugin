package com.osrstracker.ui;

import com.osrstracker.net.ServerUrl;
import com.osrstracker.net.VerifyResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * What the panel shows at the top, computed from the config and the last
 * connection test. Pure, so it can be tested without Swing.
 */
public final class StatusView
{
	public enum Level
	{
		NEUTRAL,
		BUSY,
		OK,
		WARNING,
		ERROR,
	}

	static final String INSECURE_WARNING = "The server URL uses plain http to another machine, so your API key "
		+ "is sent unencrypted. Use https, or only use http on a network you trust.";

	private final Level level;
	private final String title;
	private final String message;
	private final List<String> warnings;

	private StatusView(Level level, String title, String message, List<String> warnings)
	{
		this.level = level;
		this.title = title;
		this.message = message;
		this.warnings = Collections.unmodifiableList(warnings);
	}

	static final String LOOT_TRACKER_OFF_WARNING = "RuneLite's Loot Tracker plugin is off, so no loot is sent. "
		+ "Turn it on to track loot.";

	public static StatusView compute(@Nullable String rawUrl, @Nullable String rawKey, boolean testing,
		@Nullable VerifyResult last)
	{
		return compute(rawUrl, rawKey, testing, last, false);
	}

	/**
	 * @param lootTrackerOff sending loot is on, but RuneLite's Loot Tracker
	 *     plugin (which posts the loot events) is not running
	 */
	public static StatusView compute(@Nullable String rawUrl, @Nullable String rawKey, boolean testing,
		@Nullable VerifyResult last, boolean lootTrackerOff)
	{
		boolean urlSet = rawUrl != null && !rawUrl.trim().isEmpty();
		boolean keySet = rawKey != null && !rawKey.trim().isEmpty();
		List<String> warnings = new ArrayList<>();
		if (lootTrackerOff)
		{
			warnings.add(LOOT_TRACKER_OFF_WARNING);
		}

		if (!urlSet || !keySet)
		{
			return new StatusView(Level.NEUTRAL, "Not configured",
				"Set the server URL and API key in the plugin settings. Nothing is sent until then.", warnings);
		}

		ServerUrl url = ServerUrl.parse(rawUrl);
		if (url == null)
		{
			return new StatusView(Level.ERROR, "Invalid server URL",
				"Use the server's full base address, e.g. http://127.0.0.1:3000.", warnings);
		}
		if (url.isInsecure())
		{
			warnings.add(INSECURE_WARNING);
		}

		if (testing)
		{
			return new StatusView(Level.BUSY, "Testing…", "Contacting " + url + " …", warnings);
		}
		if (last == null)
		{
			return new StatusView(warnings.isEmpty() ? Level.NEUTRAL : Level.WARNING, "Not tested",
				"Press \"Test connection\" to check the server URL and API key.", warnings);
		}
		if (!last.isOk())
		{
			return new StatusView(Level.ERROR, "Connection failed", last.getMessage(), warnings);
		}
		warnings.addAll(last.getWarnings());
		return new StatusView(warnings.isEmpty() ? Level.OK : Level.WARNING,
			warnings.isEmpty() ? "Connected" : "Connected with warnings", last.getMessage(), warnings);
	}

	/** For layout tests only. */
	static StatusView forTest(Level level, String title, String message, List<String> warnings)
	{
		return new StatusView(level, title, message, new ArrayList<>(warnings));
	}

	public Level getLevel()
	{
		return level;
	}

	public String getTitle()
	{
		return title;
	}

	public String getMessage()
	{
		return message;
	}

	public List<String> getWarnings()
	{
		return warnings;
	}
}
