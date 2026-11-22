package com.fireside;

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
public interface FiresideConfig extends Config
{
	String SERVER_URL_KEY = "serverUrl";
	String API_KEY_KEY = "apiKey";
	String SENDING_ENABLED_KEY = "sendingEnabled";
	String UPLOAD_ICONS_KEY = "uploadIcons";
	String SEND_BANK_KEY = "sendBank";
	/** Per RS profile (account), not shown in the panel: hash of the last queued bank snapshot. */
	String BANK_HASH_KEY = "bankContentHash";
	/** Per RS profile (account), not shown in the panel: hash of the last queued potion storage snapshot. */
	String POTION_STORAGE_HASH_KEY = "potionStorageContentHash";
	String SEND_QUESTS_KEY = "sendQuests";
	String SEND_PETS_KEY = "sendPets";
	/** Per RS profile (account), not shown in the panel: hash of the last queued quests snapshot. */
	String QUESTS_HASH_KEY = "questsContentHash";
	/** Per RS profile (account), not shown in the panel: hash of the last queued diaries snapshot. */
	String DIARIES_HASH_KEY = "diariesContentHash";

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
		description = "Base URL of your own server, e.g. http://127.0.0.1:3100 (without /api/v1)",
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
		description = "Send boss and raid kill counts and raid PBs",
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

	@ConfigItem(
		keyName = SEND_BANK_KEY,
		name = "Bank",
		description = "Send bank, potion storage, inventory and equipment when the bank closes and something changed",
		position = 4,
		section = dataSection
	)
	default boolean sendBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = SEND_QUESTS_KEY,
		name = "Quests and diaries",
		description = "Send quest states, quest points and completed achievement diary tiers after login and when they change",
		position = 5,
		section = dataSection
	)
	default boolean sendQuests()
	{
		return true;
	}

	@ConfigItem(
		keyName = SEND_PETS_KEY,
		name = "Pets",
		description = "Send pet drops: which pet message the game showed, and a guess at the pet and where it "
			+ "came from (never the chat text)",
		position = 6,
		section = dataSection
	)
	default boolean sendPets()
	{
		return true;
	}

	@ConfigItem(
		keyName = UPLOAD_ICONS_KEY,
		name = "Upload item icons",
		description = "Upload the icons of items in your loot and bank, and once the icons of the whole "
			+ "collection log, all skills and all bosses, so the dashboard can show them. "
			+ "Sent only to your own server above.",
		position = 7,
		section = dataSection
	)
	default boolean uploadIcons()
	{
		return true;
	}

	@Range(min = 1, max = 60)
	@Units(Units.MINUTES)
	@ConfigItem(
		keyName = "afkThresholdMinutes",
		name = "AFK threshold",
		description = "Minutes without input or XP before time counts as AFK (statistics only)",
		position = 8,
		section = dataSection
	)
	default int afkThresholdMinutes()
	{
		return 5;
	}
}
