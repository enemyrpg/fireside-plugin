package com.fireside.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.Test;

public class UuidV7GeneratorTest
{
	/** Same pattern as {@code eventId} in the contract's schema/event.json. */
	private static final Pattern CONTRACT_UUID_V7 =
		Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
	private static final long T0 = 1_731_094_933_512L; // 2024-11-08T19:42:13.512Z

	@Test
	public void manyIdsInTheSameMillisecondAreStrictlyIncreasing()
	{
		UuidV7Generator ids = new UuidV7Generator(() -> T0, new Random(1));
		assertStrictlyIncreasing(generate(ids, 100_000));
	}

	@Test
	public void idsMatchTheContractFormatAndCarryTheTimestamp()
	{
		AtomicLong now = new AtomicLong(T0);
		UuidV7Generator ids = new UuidV7Generator(now::get, new Random(2));
		for (int i = 0; i < 1000; i++)
		{
			now.addAndGet(7);
			UUID uuid = ids.nextUuid();
			String s = uuid.toString();
			assertTrue(s, CONTRACT_UUID_V7.matcher(s).matches());
			assertEquals(7, uuid.version());
			assertEquals(2, uuid.variant());
			assertEquals(now.get(), UuidV7Generator.timestampOf(uuid));
		}
	}

	@Test
	public void counterStartsLowEnoughToLeaveRoom()
	{
		AtomicLong now = new AtomicLong(T0);
		UuidV7Generator ids = new UuidV7Generator(now::get, new Random(3));
		for (int i = 0; i < 10_000; i++)
		{
			now.incrementAndGet();
			assertTrue(UuidV7Generator.counterOf(ids.nextUuid()) < UuidV7Generator.COUNTER_START_BOUND);
		}
	}

	@Test
	public void counterOverflowBorrowsTheNextMillisecond()
	{
		// Random always picks the highest start value, so overflow comes after 2048 IDs.
		Random highStart = new Random(4)
		{
			@Override
			public int nextInt(int bound)
			{
				return bound - 1;
			}
		};
		UuidV7Generator ids = new UuidV7Generator(() -> T0, highStart);
		List<String> generated = new ArrayList<>();
		long lastTimestamp = T0;
		for (int i = 0; i < 10_000; i++)
		{
			UUID uuid = ids.nextUuid();
			generated.add(uuid.toString());
			lastTimestamp = UuidV7Generator.timestampOf(uuid);
		}
		assertStrictlyIncreasing(generated);
		// 10 000 IDs with 2048 per millisecond need 5 milliseconds, the first being T0.
		assertEquals(T0 + 4, lastTimestamp);
	}

	@Test
	public void clockGoingBackwardsKeepsIdsIncreasing()
	{
		AtomicLong now = new AtomicLong(T0);
		UuidV7Generator ids = new UuidV7Generator(now::get, new Random(5));
		List<String> generated = new ArrayList<>();
		generated.addAll(generate(ids, 10));
		now.set(T0 - 60_000); // NTP stepped the clock back a minute
		generated.addAll(generate(ids, 5000));
		now.set(T0 - 1);
		generated.addAll(generate(ids, 10));
		now.set(T0 + 10_000); // caught up
		UUID caughtUp = ids.nextUuid();
		generated.add(caughtUp.toString());
		assertStrictlyIncreasing(generated);
		assertEquals(T0 + 10_000, UuidV7Generator.timestampOf(caughtUp));
	}

	@Test
	public void concurrentCallersGetUniqueIdsInIssueOrder() throws Exception
	{
		UuidV7Generator ids = new UuidV7Generator(() -> T0, new Random(6));
		List<String> all = Collections.synchronizedList(new ArrayList<>());
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		for (int t = 0; t < 8; t++)
		{
			futures.add(CompletableFuture.runAsync(() ->
			{
				List<String> mine = generate(ids, 300);
				assertStrictlyIncreasing(mine);
				all.addAll(mine);
			}));
		}
		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
		Set<String> unique = new HashSet<>(all);
		assertEquals(all.size(), unique.size());
	}

	@Test
	public void defaultGeneratorUsesTheSystemClock()
	{
		long before = System.currentTimeMillis();
		UUID uuid = new UuidV7Generator().nextUuid();
		long after = System.currentTimeMillis();
		long ts = UuidV7Generator.timestampOf(uuid);
		assertTrue(ts >= before && ts <= after);
	}

	private static List<String> generate(UuidV7Generator ids, int count)
	{
		List<String> list = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			list.add(ids.next());
		}
		return list;
	}

	/** As strings, which is how the server compares them (fixed width, lowercase hex). */
	static void assertStrictlyIncreasing(List<String> ids)
	{
		for (int i = 1; i < ids.size(); i++)
		{
			assertTrue(ids.get(i - 1) + " !< " + ids.get(i), ids.get(i - 1).compareTo(ids.get(i)) < 0);
		}
	}
}
