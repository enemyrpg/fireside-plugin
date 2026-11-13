package com.osrstracker.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.Test;

public class BackoffTest
{
	@Test
	public void doublesWithJitterUpToFiveMinutes()
	{
		for (int seed = 0; seed < 50; seed++)
		{
			Backoff backoff = new Backoff(Backoff.DEFAULT_BASE, Backoff.DEFAULT_MAX, new Random(seed));
			long ceiling = Backoff.DEFAULT_BASE.toMillis();
			for (int attempt = 0; attempt < 15; attempt++)
			{
				long wait = backoff.next(null).toMillis();
				long cap = Math.min(ceiling, Backoff.DEFAULT_MAX.toMillis());
				assertTrue(attempt + ": " + wait, wait >= cap / 2 && wait <= cap);
				ceiling *= 2;
			}
		}
	}

	@Test
	public void neverMoreThanFiveMinutesEvenAfterManyAttempts()
	{
		Backoff backoff = new Backoff(Backoff.DEFAULT_BASE, Backoff.DEFAULT_MAX, new Random(1));
		for (int i = 0; i < 100; i++)
		{
			assertTrue(backoff.next(null).compareTo(Backoff.DEFAULT_MAX) <= 0);
		}
	}

	@Test
	public void retryAfterIsALowerBound()
	{
		Backoff backoff = new Backoff(Backoff.DEFAULT_BASE, Backoff.DEFAULT_MAX, new Random(2));
		assertEquals(Duration.ofSeconds(90), backoff.next(Duration.ofSeconds(90)));
		// A Retry-After above the max is still respected, up to an hour.
		assertEquals(Duration.ofMinutes(20), backoff.next(Duration.ofMinutes(20)));
		assertEquals(Duration.ofHours(1), backoff.next(Duration.ofDays(3)));
		// Shorter than the backoff: the backoff wins.
		assertTrue(backoff.next(Duration.ofSeconds(1)).toMillis() >= Backoff.DEFAULT_BASE.toMillis() * 4);
	}

	@Test
	public void resetStartsOver()
	{
		Backoff backoff = new Backoff(Backoff.DEFAULT_BASE, Backoff.DEFAULT_MAX, new Random(3));
		for (int i = 0; i < 10; i++)
		{
			backoff.next(null);
		}
		backoff.reset();
		assertEquals(0, backoff.attempts());
		assertTrue(backoff.next(null).compareTo(Backoff.DEFAULT_BASE) <= 0);
	}
}
