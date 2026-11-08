package com.osrstracker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = Branding.DISPLAY_NAME,
	internalName = Branding.INTERNAL_NAME,
	description = "Sends XP, loot, kill counts and session data to your self-hosted tracker server",
	tags = {"tracker", "xp", "loot", "sessions", "self-hosted"}
)
public class OsrsTrackerPlugin extends Plugin
{
	@Override
	protected void startUp() throws Exception
	{
		log.debug("{} started", Branding.DISPLAY_NAME);
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("{} stopped", Branding.DISPLAY_NAME);
	}

	@Provides
	OsrsTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsTrackerConfig.class);
	}
}
