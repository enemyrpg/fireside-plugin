package com.fireside;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PluginVersionTest
{
	@Test
	public void buildVersionIsReadAndMatchesTheContract()
	{
		String version = PluginVersion.read();
		assertNotEquals("version.properties is expanded by Gradle", PluginVersion.UNKNOWN, version);
		assertTrue(version, PluginVersion.PATTERN.matcher(version).matches());
	}

	@Test
	public void unexpandedOrInvalidVersionFallsBack()
	{
		assertEquals(PluginVersion.UNKNOWN, PluginVersion.orUnknown("${version}"));
		assertEquals(PluginVersion.UNKNOWN, PluginVersion.orUnknown(null));
		assertEquals(PluginVersion.UNKNOWN, PluginVersion.orUnknown("1.0"));
		assertEquals("1.2.3-beta.1", PluginVersion.orUnknown(" 1.2.3-beta.1 "));
	}
}
