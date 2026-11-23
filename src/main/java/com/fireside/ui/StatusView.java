package com.fireside.ui;

import com.fireside.Contract;
import com.fireside.net.ServerUrl;
import com.fireside.net.VerifyClient;
import com.fireside.send.ConnectionStatus;
import com.fireside.send.EventSender;
import com.fireside.send.SenderStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * What the panel shows at the top, computed from the config and the sender's
 * connection status. Pure, so it can be tested without Swing. Depends on the
 * time ("Last contact: 12 s ago", and "Connected" turning into "retrying"), so
 * the panel recomputes it every second.
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

	static final String LOOT_TRACKER_OFF_WARNING = "RuneLite's Loot Tracker plugin is off, so no loot is sent. "
		+ "Turn it on to track loot.";

	static final String RESUME = " Fix it in the plugin settings, or press \"Test connection\" once it works.";

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

	/**
	 * @param sendingEnabled "Send data" in the config
	 * @param status         the sender's latest status
	 * @param now            the current time, for "Last contact" and the 2-minute rule
	 * @param lootTrackerOff sending loot is on, but RuneLite's Loot Tracker
	 *     plugin (which posts the loot events) is not running
	 * @param dataWarning a line about data that was not sent (e.g. a skipped
	 *     bank snapshot), or null
	 */
	public static StatusView compute(@Nullable String rawUrl, @Nullable String rawKey, boolean sendingEnabled,
		SenderStatus status, Instant now, ZoneId zone, boolean lootTrackerOff, @Nullable String dataWarning)
	{
		boolean urlSet = rawUrl != null && !rawUrl.trim().isEmpty();
		boolean keySet = rawKey != null && !rawKey.trim().isEmpty();
		List<String> warnings = new ArrayList<>();
		if (lootTrackerOff)
		{
			warnings.add(LOOT_TRACKER_OFF_WARNING);
		}
		if (dataWarning != null)
		{
			warnings.add(dataWarning);
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
				"Use the server's full base address, e.g. http://127.0.0.1:3100.", warnings);
		}
		if (url.isInsecure())
		{
			warnings.add(INSECURE_WARNING);
		}
		if (!VerifyClient.isValidKey(rawKey.trim()))
		{
			return new StatusView(Level.ERROR, "Invalid API key",
				"The API key contains spaces or other invalid characters. Copy it again from the dashboard.",
				warnings);
		}

		ConnectionStatus connection = status.getConnection();
		if (!sendingEnabled)
		{
			// A "Test connection" result is still shown in the details below.
			return new StatusView(warnings.isEmpty() ? Level.NEUTRAL : Level.WARNING, "Sending off",
				"Turn on \"Send data\" in the plugin settings. Nothing is sent or checked until then.", warnings);
		}

		SenderStatus.StopReason stop = status.getStopReason();
		if (stop == SenderStatus.StopReason.UNAUTHORIZED)
		{
			return new StatusView(Level.ERROR, "Invalid API key",
				"The server rejected the API key (invalid or revoked). Create a key in the dashboard under "
					+ "Settings → API keys." + RESUME, warnings);
		}
		if (stop == SenderStatus.StopReason.NOT_FOUND)
		{
			return new StatusView(Level.ERROR, "Wrong server URL",
				"The server answered 404 Not Found. Use the server's base URL, e.g. http://127.0.0.1:3100, "
					+ "without /api/v1." + RESUME, warnings);
		}
		if (stop == SenderStatus.StopReason.REDIRECT)
		{
			return new StatusView(Level.ERROR, "Wrong server URL",
				(status.getDetail() != null ? status.getDetail() : "The server redirected the request.") + RESUME,
				warnings);
		}

		warnings.addAll(connection.getVerifyWarnings());
		if (status.getServerContract() != null)
		{
			return new StatusView(Level.ERROR, "Server too old",
				"The server has contract " + status.getServerContract() + ", this plugin needs "
					+ Contract.REQUIRED_VERSION + ". Update the server. Events are kept and sent after that; "
					+ "the plugin checks again every " + EventSender.HELD_RECHECK.toMinutes() + " minutes.",
				// The too-old line from verify says the same as the message.
				withoutTooOld(warnings));
		}

		switch (connection.health(now))
		{
			case CONNECTED:
				return new StatusView(warnings.isEmpty() ? Level.OK : Level.WARNING,
					warnings.isEmpty() ? "Connected" : "Connected with warnings",
					"Last contact: " + ago(connection.getLastContactAt(), now) + ".", warnings);
			case RETRYING:
				return new StatusView(Level.WARNING, connection.isRateLimited()
					? "Server busy – retrying" : "Can't reach the server – retrying",
					retryMessage(connection, now, zone), warnings);
			case UNKNOWN:
			default:
				return new StatusView(Level.BUSY, "Checking…", "Contacting " + url + " …", warnings);
		}
	}

	private static List<String> withoutTooOld(List<String> warnings)
	{
		List<String> kept = new ArrayList<>();
		for (String w : warnings)
		{
			if (!w.startsWith("The server is too old"))
			{
				kept.add(w);
			}
		}
		return kept;
	}

	private static String retryMessage(ConnectionStatus connection, Instant now, ZoneId zone)
	{
		StringBuilder sb = new StringBuilder();
		if (connection.getFailureDetail() != null)
		{
			sb.append(connection.getFailureDetail()).append(' ');
		}
		Instant next = connection.getNextAttemptAt();
		if (next != null)
		{
			sb.append("Next attempt at ")
				.append(DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone).format(next)).append(". ");
		}
		if (connection.getLastContactAt() != null)
		{
			sb.append("Last contact: ").append(ago(connection.getLastContactAt(), now)).append(". ");
		}
		sb.append("Events are kept and sent when the server answers.");
		return sb.toString();
	}

	/** "12 s ago", "3 min ago", "2 h ago"; "–" when there was no contact. */
	public static String ago(@Nullable Instant at, Instant now)
	{
		if (at == null)
		{
			return "–";
		}
		long seconds = Math.max(0, Duration.between(at, now).getSeconds());
		if (seconds < 60)
		{
			return seconds + " s ago";
		}
		if (seconds < 3600)
		{
			return seconds / 60 + " min ago";
		}
		return seconds / 3600 + " h ago";
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
