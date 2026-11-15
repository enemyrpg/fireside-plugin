package com.fireside.bank;

import com.fireside.Branding;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfile;

/**
 * {@link HashStore} in RuneLite's RS profile config, under our config group.
 * The profile key is taken when the bank closes and passed back explicitly, so
 * a hash stored during logout (when RuneLite may already have switched
 * profile) still lands on the right account.
 */
@Singleton
public class RsProfileHashStore implements HashStore
{
	private final ConfigManager configManager;

	@Inject
	RsProfileHashStore(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	@Nullable
	@Override
	public String currentProfile()
	{
		return configManager.getRSProfileKey();
	}

	@Nullable
	@Override
	public String get(String profile, String key)
	{
		return configManager.getConfiguration(Branding.CONFIG_GROUP, profile, key);
	}

	@Override
	public void set(String profile, String key, String hash)
	{
		configManager.setConfiguration(Branding.CONFIG_GROUP, profile, key, hash);
	}

	@Override
	public void clearAll(String key)
	{
		for (RuneScapeProfile profile : configManager.getRSProfiles())
		{
			configManager.unsetConfiguration(Branding.CONFIG_GROUP, profile.getKey(), key);
		}
	}
}
