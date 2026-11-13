package com.osrstracker.event;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Timestamps as the contract wants them: UTC, exactly three decimals and
 * {@code Z}, e.g. {@code 2026-11-08T19:42:13.512Z}. {@link Instant#toString()}
 * is not enough: it drops {@code .000} and prints micro- or nanoseconds.
 */
public final class Timestamps
{
	private static final DateTimeFormatter FORMAT = DateTimeFormatter
		.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
		.withZone(ZoneOffset.UTC);

	private Timestamps()
	{
	}

	/** Formats an instant, truncated (not rounded) to milliseconds. */
	public static String format(Instant instant)
	{
		return FORMAT.format(instant);
	}
}
