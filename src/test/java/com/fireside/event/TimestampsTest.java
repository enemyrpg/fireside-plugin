package com.fireside.event;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.Test;

public class TimestampsTest
{
	@Test
	public void alwaysThreeDecimalsAndZ()
	{
		assertEquals("2026-11-08T19:42:13.000Z", Timestamps.format(Instant.parse("2026-11-08T19:42:13Z")));
		assertEquals("2026-11-08T19:42:13.500Z", Timestamps.format(Instant.parse("2026-11-08T19:42:13.5Z")));
		assertEquals("2026-11-08T19:42:13.512Z", Timestamps.format(Instant.parse("2026-11-08T19:42:13.512Z")));
	}

	@Test
	public void truncatesBelowMilliseconds()
	{
		assertEquals("2026-11-08T19:42:13.999Z",
			Timestamps.format(Instant.parse("2026-11-08T19:42:13.999999999Z")));
		assertEquals("2026-11-08T23:59:59.999Z",
			Timestamps.format(Instant.parse("2026-11-08T23:59:59.999500Z")));
	}

	@Test
	public void convertsToUtc()
	{
		Instant oslo = OffsetDateTime.of(2026, 11, 9, 0, 30, 0, 0, ZoneOffset.ofHours(1)).toInstant();
		assertEquals("2026-11-08T23:30:00.000Z", Timestamps.format(oslo));
	}

	@Test
	public void epoch()
	{
		assertEquals("1970-01-01T00:00:00.000Z", Timestamps.format(Instant.EPOCH));
	}
}
