package com.fireside;

/**
 * The plugin version, sent as {@code pluginVersion} in {@code session.start}.
 * <p>
 * A constant instead of a value filled in by Gradle: the Plugin Hub builds
 * with {@code build=standard}, which replaces {@code build.gradle}, so no
 * build step of ours runs there. release-please updates it together with
 * {@code version} in {@code build.gradle}, {@code runelite-plugin.properties}
 * and {@code version.txt} (kept equal by a test). Never edit it by hand.
 */
public final class PluginVersion
{
	public static final String VERSION = "0.1.0"; // x-release-please-version

	private PluginVersion()
	{
	}
}
