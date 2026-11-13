package com.osrstracker.event;

import java.security.SecureRandom;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.inject.Singleton;

/**
 * Monotonic UUIDv7 generator (RFC 9562, section 6.2, method 1: a dedicated
 * 12-bit counter in {@code rand_a}).
 * <pre>
 *  48 bits  unix_ts_ms
 *   4 bits  version (0111)
 *  12 bits  rand_a: counter
 *   2 bits  variant (10)
 *  62 bits  rand_b: random
 * </pre>
 * Every ID is strictly greater than the one before it, also within the same
 * millisecond, so the server's (occurredAt, eventId) order is the order the
 * events were created in. Call it when the event happens, in game order.
 * <ul>
 * <li>A new millisecond starts the counter at a random value in 0–2047, so it
 * has at least 2048 steps before it overflows.</li>
 * <li>Counter overflow: the timestamp moves one millisecond ahead of the clock
 * and the counter starts again.</li>
 * <li>Clock going backwards: the last timestamp is kept and the counter goes
 * on, until the clock catches up.</li>
 * </ul>
 * The ID's timestamp can therefore be slightly ahead of {@code occurredAt}.
 * The server does not compare them. Thread-safe.
 */
@Singleton
public class UuidV7Generator
{
	static final int COUNTER_MAX = 0xFFF;
	/** Counter starts below this, leaving room for at least 2048 IDs per millisecond. */
	static final int COUNTER_START_BOUND = 0x800;
	private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;

	private final LongSupplier clockMillis;
	private final Random random;

	private long lastMillis = Long.MIN_VALUE;
	private int counter;

	public UuidV7Generator()
	{
		this(System::currentTimeMillis, new SecureRandom());
	}

	UuidV7Generator(LongSupplier clockMillis, Random random)
	{
		this.clockMillis = clockMillis;
		this.random = random;
	}

	/** The next ID as a lowercase string. */
	public String next()
	{
		return nextUuid().toString();
	}

	public synchronized UUID nextUuid()
	{
		long now = clockMillis.getAsLong();
		if (now > lastMillis)
		{
			lastMillis = now;
			counter = random.nextInt(COUNTER_START_BOUND);
		}
		else if (counter < COUNTER_MAX)
		{
			// Same millisecond, or the clock went backwards: keep the timestamp.
			counter++;
		}
		else
		{
			// Counter overflow: borrow the next millisecond.
			lastMillis++;
			counter = random.nextInt(COUNTER_START_BOUND);
		}

		long msb = ((lastMillis & TIMESTAMP_MASK) << 16) | 0x7000L | counter;
		long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
		return new UUID(msb, lsb);
	}

	/** The 48-bit millisecond timestamp in a UUIDv7. */
	static long timestampOf(UUID uuid)
	{
		return uuid.getMostSignificantBits() >>> 16;
	}

	/** The 12-bit counter ({@code rand_a}) in a UUID from this generator. */
	static int counterOf(UUID uuid)
	{
		return (int) (uuid.getMostSignificantBits() & 0xFFF);
	}
}
