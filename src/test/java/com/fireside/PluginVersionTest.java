package com.fireside;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public class PluginVersionTest
{
	/** Same pattern as {@code pluginVersion} in the contract. */
	private static final Pattern CONTRACT_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$");
	private static final Pattern GRADLE_VERSION = Pattern.compile("(?m)^version = '([^']*)'");

	@Test
	public void versionMatchesTheContractPattern()
	{
		assertTrue(PluginVersion.VERSION, CONTRACT_PATTERN.matcher(PluginVersion.VERSION).matches());
	}

	@Test
	public void versionMatchesBuildGradle() throws IOException
	{
		String gradle = new String(Files.readAllBytes(Paths.get("build.gradle")), StandardCharsets.UTF_8);
		Matcher m = GRADLE_VERSION.matcher(gradle);
		assertTrue("No version in build.gradle", m.find());
		assertEquals("build.gradle", PluginVersion.VERSION, m.group(1));
	}

	@Test
	public void versionMatchesRunelitePluginProperties() throws IOException
	{
		Properties properties = new Properties();
		try (Reader in = Files.newBufferedReader(Paths.get("runelite-plugin.properties"), StandardCharsets.UTF_8))
		{
			properties.load(in);
		}
		assertEquals("runelite-plugin.properties", PluginVersion.VERSION, properties.getProperty("version"));
	}

	/** release-please's {@code simple} release type keeps the version here too. */
	@Test
	public void versionMatchesVersionTxt() throws IOException
	{
		String text = new String(Files.readAllBytes(Paths.get("version.txt")), StandardCharsets.UTF_8);
		assertEquals("version.txt", PluginVersion.VERSION, text.trim());
	}
}
