package com.osrstracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

/**
 * Plugin settings. Key names are part of the saved user settings: never rename
 * one without a migration.
 */
@ConfigGroup(Branding.CONFIG_GROUP)
public interface OsrsTrackerConfig extends Config
{
	String SERVER_URL_KEY = "serverUrl";
	String API_KEY_KEY = "apiKey";
	String SENDING_ENABLED_KEY = "sendingEnabled";

	@ConfigSection(
		name = "Server",
		description = "Your self-hosted server. Nothing is sent until the URL and API key are set.",
		position = 0
	)
	String serverSection = "server";

	@ConfigSection(
		name = "Data",
		description = "What to send. Sessions, heartbeats and AFK periods are always sent while sending is on.",
		position = 1
	)
	String dataSection = "data";

	@ConfigItem(
		keyName = SERVER_URL_KEY,
		name = "Server URL",
		description = "Base URL of your own server, e.g. http://127.0.0.1:3000 (without /api/v1)",
		position = 0,
		section = serverSection
	)
	default String serverUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = API_KEY_KEY,
		name = "API key",
		description = "Ingest API key from your dashboard (Settings → API keys)",
		secret = true,
		position = 1,
		section = serverSection
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = SENDING_ENABLED_KEY,
		name = "Send data",
		description = "Send data to your server. Nothing is sent until the server URL and API key are set.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		position = 2,
		section = serverSection
	)
	default boolean sendingEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "sendXp",
		name = "XP",
		description = "Send XP snapshots",
		position = 0,
		section = dataSection
	)
	default boolean sendXp()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendLoot",
		name = "Loot",
		description = "Send loot (from RuneLite's Loot Tracker)",
		position = 1,
		section = dataSection
	)
	default boolean sendLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendKillCounts",
		name = "Kill counts",
		description = "Send boss and raid kill counts",
		position = 2,
		section = dataSection
	)
	default boolean sendKillCounts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendRaidPurples",
		name = "Raid purples",
		description = "Send raid unique drops for you and your team (never teammates' names)",
		position = 3,
		section = dataSection
	)
	default boolean sendRaidPurples()
	{
		return true;
	}

	@Range(min = 1, max = 60)
	@Units(Units.MINUTES)
	@ConfigItem(
		keyName = "afkThresholdMinutes",
		name = "AFK threshold",
		description = "Minutes without input or XP before time counts as AFK (statistics only)",
		position = 4,
		section = dataSection
	)
	default int afkThresholdMinutes()
	{
		return 5;
	}
}
