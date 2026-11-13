package com.osrstracker;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * The plugin version from the build ({@code version} in {@code build.gradle},
 * written into {@code version.properties} by {@code processResources}). Sent
 * as {@code pluginVersion} in {@code session.start}.
 */
@Slf4j
public final class PluginVersion
{
	/** Used when the resource is missing or was not expanded (e.g. an IDE build without Gradle). */
	public static final String UNKNOWN = "0.0.0";
	static final String RESOURCE = "version.properties";
	/** Same pattern as {@code pluginVersion} in the contract. */
	static final Pattern PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$");

	private PluginVersion()
	{
	}

	public static String read()
	{
		try (InputStream in = PluginVersion.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.debug("No {} resource; plugin version is {}", RESOURCE, UNKNOWN);
				return UNKNOWN;
			}
			Properties properties = new Properties();
			properties.load(in);
			return orUnknown(properties.getProperty("version"));
		}
		catch (IOException e)
		{
			log.debug("Could not read {}", RESOURCE, e);
			return UNKNOWN;
		}
	}

	static String orUnknown(@Nullable String version)
	{
		if (version == null || !PATTERN.matcher(version.trim()).matches())
		{
			log.debug("Invalid plugin version {}; using {}", version, UNKNOWN);
			return UNKNOWN;
		}
		return version.trim();
	}
}
