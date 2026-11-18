package com.fireside.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fireside.event.Event;
import com.fireside.event.EventJson;
import com.fireside.event.Kc;
import com.fireside.event.UuidV7Generator;
import com.fireside.net.IngestClient;
import com.fireside.net.VerifyClient;
import com.fireside.queue.EventQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.runelite.client.util.Filepath;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The automatic connection check and the connection status, against a mock
 * server: start-up, config changes (debounced), login, "Test connection",
 * temporary failures and stops.
 */
public class EventSenderConnectionTest
{
	private static final Duration WAIT = Duration.ofSeconds(15);
	/** Longer than a few backoff periods of the test backoff (40 to 200 ms). */
	private static final long QUIET_MS = 600;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private final OkHttpClient http = new OkHttpClient();
	private final UuidV7Generator ids = new UuidV7Generator();
	private final LinkedBlockingQueue<SenderStatus> statuses = new LinkedBlockingQueue<>();
	private final List<ScheduledExecutorService> executors = new ArrayList<>();

	private FakeIngestServer server;
	private EventSender sender;

	@Before
	public void setUp() throws Exception
	{
		server = new FakeIngestServer().start();
	}

	@After
	public void tearDown() throws Exception
	{
		if (sender != null)
		{
			sender.shutdown();
		}
		for (ScheduledExecutorService executor : executors)
		{
			executor.shutdown();
		}
		server.stop();
	}

	private SenderSettings good()
	{
		return new SenderSettings(true, server.url(), FakeIngestServer.KEY);
	}

	private EventSender start(SenderSettings initial, Duration debounce) throws Exception
	{
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
		executor.setRemoveOnCancelPolicy(true);
		executors.add(executor);
		EventQueue queue = new EventQueue(Filepath.Unchecked.getRooted(folder.getRoot().toPath().resolve("queue")),
			RuneLiteAPI.GSON, Clock.systemUTC());
		sender = new EventSender(executor, queue, new EventJson(RuneLiteAPI.GSON),
			new IngestClient(http, RuneLiteAPI.GSON), new VerifyClient(http, RuneLiteAPI.GSON),
			new Backoff(Duration.ofMillis(40), Duration.ofMillis(200), new Random(1)), Clock.systemUTC(),
			Duration.ofHours(1), debounce, statuses::add);
		sender.start(initial);
		sender.flush().get();
		return sender;
	}

	private EventSender start(SenderSettings initial) throws Exception
	{
		return start(initial, Duration.ofMillis(50));
	}

	private void submit(int count)
	{
		String sessionId = ids.next();
		for (int i = 0; i < count; i++)
		{
			sender.submit(Event.create(ids, 1L, Instant.now(), sessionId,
				Kc.builder().boss("Vorkath").killCount(i + 1).build()));
		}
	}

	private SenderStatus await(String what, Predicate<SenderStatus> condition) throws Exception
	{
		sender.flush().get();
		long deadline = System.nanoTime() + WAIT.toNanos();
		SenderStatus current = sender.getStatus();
		while (!condition.test(current))
		{
			long left = deadline - System.nanoTime();
			if (left <= 0)
			{
				fail("Timed out waiting for " + what + "; last status " + current);
			}
			SenderStatus next = statuses.poll(Math.min(left, TimeUnit.MILLISECONDS.toNanos(50)),
				TimeUnit.NANOSECONDS);
			current = next != null ? sender.getStatus() : sender.getStatus();
		}
		return current;
	}

	private static ConnectionStatus.Health health(SenderStatus s)
	{
		return s.getConnection().health(Instant.now());
	}

	private SenderStatus awaitConnected() throws Exception
	{
		return await("connected", s -> health(s) == ConnectionStatus.Health.CONNECTED
			&& !s.getConnection().isChecking());
	}

	/** Lets timers (backoff, debounce) run for a while; test code only. */
	private void quiet() throws Exception
	{
		Thread.sleep(QUIET_MS);
		sender.flush().get();
	}

	// ---- Automatic checks ----------------------------------------------------------

	@Test
	public void startUpChecksTheConnectionWithAnEmptyQueue() throws Exception
	{
		start(good());
		SenderStatus connected = awaitConnected();
		assertEquals(1, server.verifyCalls);
		assertEquals(0, connected.getQueueSize());
		assertNotNull(connected.getConnection().getLastContactAt());
		assertEquals("test", connected.getConnection().getVerifyResponse().getKeyName());
		assertEquals(0, server.batches.size());
	}

	@Test
	public void loginChecksOnce() throws Exception
	{
		start(good());
		awaitConnected();
		sender.onLogin();
		await("second verify", s -> server.verifyCalls == 2 && !s.getConnection().isChecking());
		quiet();
		assertEquals(2, server.verifyCalls);
	}

	@Test
	public void temporaryFailuresAreRetriedAlsoWithAnEmptyQueue() throws Exception
	{
		server.verifyOverride = new MockResponse().setResponseCode(503);
		start(good());
		SenderStatus retrying = await("retries", s -> server.verifyCalls >= 3);
		assertEquals(ConnectionStatus.Health.RETRYING, health(retrying));
		assertEquals(0, retrying.getQueueSize());

		server.verifyOverride = null;
		SenderStatus connected = awaitConnected();
		assertEquals(0, connected.getConnection().getConsecutiveFailures());
		assertNull(connected.getConnection().getNextAttemptAt());
	}

	@Test
	public void rateLimitedVerifyRespectsRetryAfterAndSaysSo() throws Exception
	{
		server.verifyOverride = new MockResponse().setResponseCode(429).setHeader("Retry-After", "2");
		Instant before = Instant.now();
		start(good());
		SenderStatus failed = await("429", s -> s.getConnection().getConsecutiveFailures() == 1);
		assertTrue(failed.getConnection().isRateLimited());
		Instant next = failed.getConnection().getNextAttemptAt();
		assertNotNull(next);
		assertTrue(next.toString(), !next.isBefore(before.plusSeconds(2)));
		quiet();
		assertEquals("no attempt before Retry-After", 1, server.verifyCalls);
	}

	// ---- Any answer counts -------------------------------------------------------

	@Test
	public void failedVerifyFollowedBySuccessfulIngestIsConnected() throws Exception
	{
		start(good());
		awaitConnected();

		// The login check fails...
		server.verifyOverride = new MockResponse().setResponseCode(503);
		sender.onLogin();
		await("one failure", s -> s.getConnection().getConsecutiveFailures() == 1);

		// ...and the next batch (a heartbeat, say) goes through while verify still fails.
		submit(1);
		sender.sendNow();
		SenderStatus after = await("sent", s -> server.stored.size() == 1 && s.getQueueSize() == 0
			&& !s.getConnection().isChecking());
		assertEquals(0, after.getConnection().getConsecutiveFailures());
		assertEquals(ConnectionStatus.Health.CONNECTED, health(after));
	}

	@Test
	public void oneFailureKeepsConnectedAndTwoGiveRetrying() throws Exception
	{
		start(good());
		awaitConnected();

		server.verifyOverride = new MockResponse().setResponseCode(503);
		sender.onLogin();
		SenderStatus one = await("one failure", s -> s.getConnection().getConsecutiveFailures() >= 1);
		if (one.getConnection().getConsecutiveFailures() == 1)
		{
			assertEquals(ConnectionStatus.Health.CONNECTED, health(one));
		}
		SenderStatus two = await("two failures", s -> s.getConnection().getConsecutiveFailures() >= 2);
		assertEquals(ConnectionStatus.Health.RETRYING, health(two));
		assertNotNull(two.getConnection().getNextAttemptAt());
	}

	@Test
	public void iconAnswersUpdateTheStatus() throws Exception
	{
		start(good());
		awaitConnected();
		sender.onIconContact(false, true);
		sender.onIconContact(false, true);
		SenderStatus busy = await("two icon failures", s -> s.getConnection().getConsecutiveFailures() == 2);
		assertEquals(ConnectionStatus.Health.RETRYING, health(busy));
		assertTrue(busy.getConnection().isRateLimited());

		sender.onIconContact(true, false);
		awaitConnected();
	}

	// ---- Stops ----------------------------------------------------------------------

	@Test
	public void unauthorizedStopsChecksUntilTheConfigChanges() throws Exception
	{
		start(new SenderSettings(true, server.url(), "ost_revoked_key"));
		SenderStatus stopped = await("stopped", s -> s.getState() == SenderStatus.State.STOPPED);
		assertEquals(SenderStatus.StopReason.UNAUTHORIZED, stopped.getStopReason());
		int requests = server.server.getRequestCount();
		assertEquals(1, requests);

		// No retries, and login does not check either.
		sender.onLogin();
		submit(1);
		sender.sendNow();
		quiet();
		assertEquals(requests, server.server.getRequestCount());

		sender.updateSettings(good());
		awaitConnected();
		assertEquals(SenderStatus.State.IDLE, await("sent", s -> s.getQueueSize() == 0).getState());
	}

	@Test
	public void notFoundStopsChecksUntilTheConfigChanges() throws Exception
	{
		start(new SenderSettings(true, server.url() + "wrong/", FakeIngestServer.KEY));
		SenderStatus stopped = await("stopped", s -> s.getState() == SenderStatus.State.STOPPED);
		assertEquals(SenderStatus.StopReason.NOT_FOUND, stopped.getStopReason());
		int requests = server.server.getRequestCount();

		sender.onLogin();
		quiet();
		assertEquals(requests, server.server.getRequestCount());

		sender.updateSettings(good());
		awaitConnected();
		assertEquals(1, server.verifyCalls);
	}

	@Test
	public void testConnectionResumesAfterAStop() throws Exception
	{
		start(good());
		awaitConnected();
		submit(1);
		server.script.add(batch -> new MockResponse().setResponseCode(401));
		sender.sendNow();
		await("stopped", s -> s.getState() == SenderStatus.State.STOPPED);

		sender.testConnection();
		await("sent", s -> s.getQueueSize() == 0 && s.getState() == SenderStatus.State.IDLE);
		assertEquals(2, server.verifyCalls);
	}

	// ---- Debounce and config -------------------------------------------------------

	@Test
	public void quickConfigChangesGiveOneVerify() throws Exception
	{
		start(new SenderSettings(false, server.url(), FakeIngestServer.KEY), Duration.ofMillis(300));
		sender.updateSettings(new SenderSettings(true, server.url(), "ost_a"));
		sender.updateSettings(new SenderSettings(true, server.url(), "ost_ab"));
		sender.updateSettings(new SenderSettings(true, "http://127.0.0.1:1/", FakeIngestServer.KEY));
		sender.updateSettings(new SenderSettings(true, server.url(), "ost_abc"));
		sender.updateSettings(good());
		awaitConnected();
		quiet();
		assertEquals(1, server.verifyCalls);
		assertEquals(1, server.server.getRequestCount());
	}

	@Test
	public void nothingIsSentWithoutUrlKeyAndSendingOn() throws Exception
	{
		SenderSettings[] incomplete = {
			new SenderSettings(false, server.url(), FakeIngestServer.KEY),
			new SenderSettings(true, "", FakeIngestServer.KEY),
			new SenderSettings(true, server.url(), ""),
		};
		start(incomplete[0]);
		for (SenderSettings settings : incomplete)
		{
			sender.updateSettings(settings);
			sender.onLogin();
			sender.testConnection();
			submit(EventSender.SEND_THRESHOLD + 1);
			sender.sendNow();
			sender.onIconContact(true, false);
			quiet();
			SenderStatus status = sender.getStatus();
			assertNull(status.getConnection().getLastContactAt());
		}
		assertEquals(0, server.server.getRequestCount());
	}
}
