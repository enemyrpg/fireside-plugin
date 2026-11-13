package com.osrstracker.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstracker.event.SessionAfk;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AfkDetectorTest
{
	private static final Instant START = Instant.parse("2026-11-08T19:00:00Z");
	private static final Duration TICK = Duration.ofMillis(600);
	private static final Duration FIVE_MIN = Duration.ofMinutes(5);

	private final AfkDetector afk = new AfkDetector(START);
	private Instant now = START;
	/** When the simulated player last touched mouse or keyboard. */
	private Instant lastInput = START;
	private final List<SessionAfk> periods = new ArrayList<>();

	@Test
	public void noAfkBelowTheThreshold()
	{
		idleFor(Duration.ofMinutes(4).plusSeconds(59));
		assertFalse(afk.isAfk());
		input();
		assertTrue(periods.isEmpty());
	}

	@Test
	public void periodStartsAtTheLastInputNotAtTheThreshold()
	{
		idleFor(Duration.ofSeconds(30));
		input();
		Instant leftAt = now;
		idleFor(Duration.ofMinutes(14));
		assertTrue(afk.isAfk());
		input();
		Instant backAt = now;

		assertEquals(1, periods.size());
		assertEquals(leftAt, periods.get(0).getStartedAt());
		assertEquals(backAt, periods.get(0).getEndedAt());
		assertEquals(Duration.ofMinutes(14), Duration.between(leftAt, backAt).withNanos(0));
		assertFalse(afk.isAfk());
	}

	@Test
	public void periodEndsAtTheInputNotAtTheTick()
	{
		idleFor(Duration.ofMinutes(6));
		// Input 200 ms before the next tick; the tick sees 10 idle client ticks.
		now = now.plus(TICK);
		Instant inputAt = now.minusMillis(200);
		lastInput = inputAt;
		tick();
		assertEquals(inputAt, periods.get(0).getEndedAt());
		assertEquals(START, periods.get(0).getStartedAt());
	}

	@Test
	public void xpGainsKeepItActive()
	{
		for (int i = 0; i < 20; i++)
		{
			idleFor(Duration.ofMinutes(1));
			afk.onXpGain(now);
		}
		assertFalse(afk.isAfk());
		assertTrue(periods.isEmpty());
	}

	@Test
	public void xpGainEndsAPeriod()
	{
		idleFor(Duration.ofMinutes(8));
		assertTrue(afk.isAfk());
		Instant xpAt = now.plusMillis(300);
		afk.onXpGain(xpAt);
		idleFor(TICK);
		assertEquals(1, periods.size());
		assertEquals(START, periods.get(0).getStartedAt());
		assertEquals(xpAt, periods.get(0).getEndedAt());
	}

	@Test
	public void closeEndsAnOpenPeriodAtTheGivenTime()
	{
		idleFor(Duration.ofMinutes(7));
		Instant logoutAt = now.plusSeconds(1);
		SessionAfk period = afk.close(logoutAt, FIVE_MIN);
		assertEquals(START, period.getStartedAt());
		assertEquals(logoutAt, period.getEndedAt());
		assertFalse(afk.isAfk());
	}

	@Test
	public void closeCatchesAThresholdCrossedSinceTheLastTick()
	{
		idleFor(Duration.ofMinutes(5).minusMillis(300));
		assertFalse(afk.isAfk());
		Instant logoutAt = START.plus(FIVE_MIN);
		SessionAfk period = afk.close(logoutAt, FIVE_MIN);
		assertEquals(START, period.getStartedAt());
		assertEquals(logoutAt, period.getEndedAt());
	}

	@Test
	public void closeWithoutAfkReturnsNothing()
	{
		idleFor(Duration.ofMinutes(2));
		assertNull(afk.close(now, FIVE_MIN));
	}

	@Test
	public void idleTimeBeforeTheSessionDoesNotCount()
	{
		// Ten minutes on the login screen without input, then logged in.
		lastInput = START.minus(Duration.ofMinutes(10));
		idleFor(Duration.ofMinutes(4));
		assertFalse(afk.isAfk());
		idleFor(Duration.ofMinutes(2));
		assertTrue(afk.isAfk());
		input();
		assertEquals(START, periods.get(0).getStartedAt());
	}

	@Test
	public void slowIdleCounterIsNotInput()
	{
		// The client's idle counter falls behind the wall clock (e.g. lag); it never resets.
		for (int i = 0; i < 1000; i++)
		{
			now = now.plus(TICK);
			afk.onTick(now, i * 10, FIVE_MIN);
		}
		assertTrue(afk.isAfk());
	}

	@Test
	public void constantInputKeepsItActive()
	{
		// Input every client cycle: the idle count stays at 0 and never drops.
		for (int i = 0; i < 1000; i++)
		{
			now = now.plus(TICK);
			afk.onTick(now, 0, FIVE_MIN);
		}
		assertFalse(afk.isAfk());
	}

	@Test
	public void lowerThresholdAppliesAtOnce()
	{
		idleFor(Duration.ofMinutes(3));
		assertFalse(afk.isAfk());
		now = now.plus(TICK);
		afk.onTick(now, idleTicks(), Duration.ofMinutes(2));
		assertTrue(afk.isAfk());
	}

	private void idleFor(Duration duration)
	{
		Instant until = now.plus(duration);
		while (now.plus(TICK).compareTo(until) <= 0)
		{
			now = now.plus(TICK);
			tick();
		}
		if (now.isBefore(until))
		{
			now = until;
			tick();
		}
	}

	private void input()
	{
		now = now.plus(TICK);
		lastInput = now;
		tick();
	}

	private void tick()
	{
		SessionAfk period = afk.onTick(now, idleTicks(), FIVE_MIN);
		if (period != null)
		{
			periods.add(period);
		}
	}

	private int idleTicks()
	{
		return (int) (Duration.between(lastInput, now).toMillis() / AfkDetector.CLIENT_TICK.toMillis());
	}
}
