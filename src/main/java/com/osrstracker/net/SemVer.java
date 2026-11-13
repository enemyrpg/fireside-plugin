package com.osrstracker.net;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * A semantic version ({@code major.minor.patch}). Prerelease and build suffixes
 * are accepted (same pattern as the contract schema) but ignored when comparing.
 */
public final class SemVer implements Comparable<SemVer>
{
	private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)([-+][0-9A-Za-z.-]+)?$");

	private final int major;
	private final int minor;
	private final int patch;

	private SemVer(int major, int minor, int patch)
	{
		this.major = major;
		this.minor = minor;
		this.patch = patch;
	}

	/** Returns the parsed version, or {@code null} if the text is not a valid version. */
	@Nullable
	public static SemVer parse(@Nullable String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher m = PATTERN.matcher(text);
		if (!m.matches())
		{
			return null;
		}
		try
		{
			return new SemVer(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	public int getMajor()
	{
		return major;
	}

	@Override
	public int compareTo(SemVer o)
	{
		if (major != o.major)
		{
			return Integer.compare(major, o.major);
		}
		if (minor != o.minor)
		{
			return Integer.compare(minor, o.minor);
		}
		return Integer.compare(patch, o.patch);
	}

	@Override
	public boolean equals(Object o)
	{
		if (!(o instanceof SemVer))
		{
			return false;
		}
		SemVer v = (SemVer) o;
		return major == v.major && minor == v.minor && patch == v.patch;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(major, minor, patch);
	}

	@Override
	public String toString()
	{
		return major + "." + minor + "." + patch;
	}
}
