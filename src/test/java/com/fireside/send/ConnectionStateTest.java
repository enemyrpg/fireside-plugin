package com.fireside.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fireside.send.ConnectionStatus.Health;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;

/** The connection model on its own: which answers count, and when "Connected" turns into "retrying". */
public class ConnectionStateTest
{
	private static final Instant T0 = Instant.parse("2026-11-18T18:00:00Z");

	private final ConnectionState state = new ConnectionState();

	private Health health(Instant now)
	{
		return state.snapshot(false, false, null).health(now);
	}

	@Test
	public void unknownUntilTheFirstAnswer()
	{
		assertEquals(Health.UNKNOWN, health(T0));
		state.success(T0);
		assertEquals(Health.CONNECTED, health(T0));
	}

	@Test
	public void failureThenSuccessIsConnected()
	{
		state.failure("down", false);
		assertEquals(Health.RETRYING, health(T0));
		state.success(T0.plusSeconds(5));
		assertEquals(Health.CONNECTED, health(T0.plusSeconds(5)));
		assertEquals(0, state.snapshot(false, false, null).getConsecutiveFailures());
		assertNull(state.snapshot(false, false, null).getFailureDetail());
	}

	@Test
	public void oneFailureKeepsConnectedTwoDoNot()
	{
		state.success(T0);
		state.failure("down", false);
		assertEquals(Health.CONNECTED, health(T0.plusSeconds(10)));
		state.failure("down", false);
		assertEquals(Health.RETRYING, health(T0.plusSeconds(10)));
	}

	@Test
	public void oneFailureAndAnOldContactIsRetrying()
	{
		state.success(T0);
		state.failure("down", false);
		Instant edge = T0.plus(ConnectionStatus.STALE_AFTER);
		assertEquals(Health.CONNECTED, health(edge));
		assertEquals(Health.RETRYING, health(edge.plusMillis(1)));
	}

	@Test
	public void anOldContactWithoutFailuresStaysConnected()
	{
		state.success(T0);
		assertEquals(Health.CONNECTED, health(T0.plus(Duration.ofHours(5))));
	}

	@Test
	public void rateLimitIsRememberedUntilTheNextAnswer()
	{
		state.failure("429", true);
		assertTrue(state.snapshot(false, false, null).isRateLimited());
		state.failure("503", false);
		assertFalse(state.snapshot(false, false, null).isRateLimited());
	}

	@Test
	public void resetForgetsEverything()
	{
		state.success(T0);
		state.failure("down", false);
		state.reset();
		ConnectionStatus s = state.snapshot(false, false, null);
		assertNull(s.getLastContactAt());
		assertEquals(0, s.getConsecutiveFailures());
		assertNull(s.getVerifyResponse());
		assertTrue(s.getVerifyWarnings().isEmpty());
		assertEquals(Health.UNKNOWN, s.health(T0));
	}
}
