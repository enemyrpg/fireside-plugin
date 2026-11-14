package com.fireside;

/**
 * The plugin version, sent as {@code pluginVersion} in {@code session.start}.
 * <p>
 * A constant instead of a value filled in by Gradle: the Plugin Hub builds
 * with {@code build=standard}, which replaces {@code build.gradle}, so no
 * build step of ours runs there. Keep it equal to {@code version} in
 * {@code build.gradle} and {@code runelite-plugin.properties} (checked by a
 * test).
 */
public final class PluginVersion
{
	public static final String VERSION = "0.1.0"; // x-release-please-version

	private PluginVersion()
	{
	}
}
