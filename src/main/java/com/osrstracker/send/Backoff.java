package com.osrstracker.send;

import java.time.Duration;
import java.util.Random;
import javax.annotation.Nullable;

/**
 * Exponential backoff with "equal jitter": the n-th wait is between half and
 * all of {@code min(max, base * 2^n)}. A server's {@code Retry-After} is a
 * lower bound (capped at {@link #MAX_RETRY_AFTER} in case it is nonsense).
 * Not thread-safe.
 */
public class Backoff
{
	public static final Duration DEFAULT_BASE = Duration.ofSeconds(5);
	public static final Duration DEFAULT_MAX = Duration.ofMinutes(5);
	static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

	private final long baseMillis;
	private final long maxMillis;
	private final Random random;
	private int attempts;

	public Backoff(Duration base, Duration max, Random random)
	{
		this.baseMillis = base.toMillis();
		this.maxMillis = max.toMillis();
		this.random = random;
	}

	/** The next wait. Each call doubles the ceiling until it reaches the maximum. */
	public Duration next(@Nullable Duration retryAfter)
	{
		long ceiling = maxMillis;
		if (attempts < 30)
		{
			ceiling = Math.min(maxMillis, baseMillis << attempts);
		}
		attempts++;
		long half = ceiling / 2;
		long wait = half + (long) (random.nextDouble() * (ceiling - half));
		if (retryAfter != null)
		{
			long server = Math.min(retryAfter.toMillis(), MAX_RETRY_AFTER.toMillis());
			wait = Math.max(wait, server);
		}
		return Duration.ofMillis(wait);
	}

	public void reset()
	{
		attempts = 0;
	}

	public int attempts()
	{
		return attempts;
	}
}
