package com.fireside.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.networknt.schema.JsonSchema;
import com.fireside.Contract;
import com.fireside.contract.ContractPackage;
import com.fireside.event.Event;
import com.fireside.event.EventJson;
import com.fireside.event.Kc;
import com.fireside.event.UuidV7Generator;
import com.fireside.net.IngestClient;
import com.fireside.net.VerifyClient;
import com.fireside.net.VerifyResult;
import com.fireside.queue.EventQueue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.runelite.client.util.Filepath;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The sender against a mock server: every status code, backoff, the contract
 * check, and the server going away and coming back without losing events.
 */
public class EventSenderTest
{
	private static final Duration WAIT = Duration.ofSeconds(15);

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private final OkHttpClient http = new OkHttpClient();
	private final EventJson json = new EventJson(RuneLiteAPI.GSON);
	private final UuidV7Generator ids = new UuidV7Generator();
	private final String sessionId = ids.next();
	private final LinkedBlockingQueue<SenderStatus> statuses = new LinkedBlockingQueue<>();
	private final List<ScheduledExecutorService> executors = new ArrayList<>();

	private FakeIngestServer server;
	private EventSender sender;
	private SenderSettings settings;

	@Before
	public void setUp() throws Exception
	{
		// An explicit port, so MockWebServer sets SO_REUSEADDR and a restart can bind it again.
		server = new FakeIngestServer().start(freePort());
		settings = new SenderSettings(true, server.url(), FakeIngestServer.KEY);
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

	private static int freePort() throws Exception
	{
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0))
		{
			return socket.getLocalPort();
		}
	}

	private Path queueDir()
	{
		return folder.getRoot().toPath().resolve("queue");
	}

	/** A sender with a short backoff and no 30 s timer (tests trigger sends themselves). */
	private EventSender newSender(SenderSettings initial)
	{
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
		executor.setRemoveOnCancelPolicy(true);
		executors.add(executor);
		EventQueue queue = new EventQueue(Filepath.Unchecked.getRooted(queueDir()), RuneLiteAPI.GSON,
			Clock.systemUTC());
		EventSender s = new EventSender(executor, queue, json, new IngestClient(http, RuneLiteAPI.GSON),
			new VerifyClient(http, RuneLiteAPI.GSON),
			new Backoff(Duration.ofMillis(40), Duration.ofMillis(200), new Random(1)),
			Clock.systemUTC(), Duration.ofHours(1), statuses::add);
		s.start(initial);
		return s;
	}

	private EventSender startSender() throws Exception
	{
		sender = newSender(settings);
		sender.flush().get();
		return sender;
	}

	private List<String> submit(EventSender s, int count)
	{
		List<String> submitted = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			Event event = Event.create(ids, 1234567890123456789L, Instant.now(), sessionId,
				Kc.builder().boss("Vorkath").killCount(i + 1).build());
			submitted.add(event.getEventId());
			s.submit(event);
		}
		return submitted;
	}

	private SenderStatus await(String what, Predicate<SenderStatus> condition) throws Exception
	{
		// Runs after every task posted so far, so the status reflects them.
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
			SenderStatus next = statuses.poll(left, TimeUnit.NANOSECONDS);
			current = next != null ? sender.getStatus() : current;
		}
		return current;
	}

	private SenderStatus awaitEmptyQueue() throws Exception
	{
		return await("empty queue", s -> s.getState() == SenderStatus.State.IDLE && s.getQueueSize() == 0);
	}

	private List<JsonObject> errorLog() throws Exception
	{
		Path file = queueDir().resolve(EventQueue.REJECTED_FILE);
		if (!Files.exists(file))
		{
			return Collections.emptyList();
		}
		return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
			.map(l -> new JsonParser().parse(l).getAsJsonObject())
			.collect(Collectors.toList());
	}

	private List<String> pendingLines() throws Exception
	{
		Path file = queueDir().resolve(EventQueue.PENDING_FILE);
		return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : Collections.emptyList();
	}

	private VerifyResult testConnection(String url, String key) throws Exception
	{
		CompletableFuture<VerifyResult> result = new CompletableFuture<>();
		new VerifyClient(http, RuneLiteAPI.GSON).verify(url, key, result::complete);
		return result.get(10, TimeUnit.SECONDS);
	}

	// ---- 200 ------------------------------------------------------------------

	@Test
	public void sendsValidBatchesAndRemovesConfirmedEvents() throws Exception
	{
		startSender();
		List<String> submitted = submit(sender, 3);
		sender.sendNow();
		SenderStatus done = awaitEmptyQueue();

		assertEquals(submitted, server.stored);
		assertEquals(1, server.batches.size());
		assertEquals(3, done.getLastSentCount());
		assertNotNull(done.getLastSentAt());
		assertEquals(1, server.verifyCalls);
		assertTrue(pendingLines().isEmpty());

		FakeIngestServer.Batch batch = server.batches.get(0);
		assertEquals("Bearer " + FakeIngestServer.KEY, batch.authorization);
		assertTrue(batch.contentType, batch.contentType.startsWith("application/json"));
		JsonSchema request = ContractPackage.schema("schema/api/ingest-request.json");
		assertTrue(ContractPackage.validate(request, batch.body).isEmpty());
		JsonSchema event = ContractPackage.schema("schema/event.json");
		new JsonParser().parse(batch.body).getAsJsonObject().getAsJsonArray("events").forEach(e ->
			assertTrue(ContractPackage.validate(event, e.toString()).isEmpty()));
	}

	@Test
	public void sendsWhenTheQueueReachesTheThreshold() throws Exception
	{
		startSender();
		submit(sender, EventSender.SEND_THRESHOLD - 1);
		sender.flush().get();
		assertEquals(0, server.batches.size());

		submit(sender, 1);
		awaitEmptyQueue();
		assertEquals(EventSender.SEND_THRESHOLD, server.stored.size());
	}

	@Test
	public void logoutSends() throws Exception
	{
		startSender();
		submit(sender, 2);
		sender.onLogout();
		awaitEmptyQueue();
		assertEquals(2, server.stored.size());
	}

	@Test
	public void largeQueueGoesInBatchesOfAtMost500() throws Exception
	{
		startSender();
		List<String> submitted = submit(sender, 1201);
		sender.sendNow();
		awaitEmptyQueue();
		List<Integer> sizes = server.batches.stream().map(b -> b.eventIds.size()).collect(Collectors.toList());
		assertEquals(Arrays.asList(500, 500, 201), sizes);
		assertEquals(submitted, server.stored);
		for (FakeIngestServer.Batch b : server.batches)
		{
			assertTrue(b.body.getBytes(StandardCharsets.UTF_8).length <= EventSender.MAX_BATCH_BYTES);
		}
	}

	@Test
	public void mixedResponseRemovesConfirmedLogsRejectedAndKeepsTheRest() throws Exception
	{
		startSender();
		List<String> submitted = submit(sender, 5);
		server.script.add(batch ->
		{
			String body = "{\"accepted\":[\"" + batch.eventIds.get(0) + "\"],"
				+ "\"duplicates\":[\"" + batch.eventIds.get(1).toUpperCase(Locale.ROOT) + "\"],"
				+ "\"rejected\":[{\"index\":2,\"eventId\":\"" + batch.eventIds.get(2) + "\",\"error\":\"payload.boss: Too small\"},"
				+ "{\"index\":3,\"eventId\":null,\"error\":\"eventId: Invalid input\"}]}";
			return FakeIngestServer.json(200, body);
		});
		sender.sendNow();
		SenderStatus status = await("one left", s -> s.getQueueSize() == 1 && s.getRejectedCount() == 2
			&& s.getState() == SenderStatus.State.IDLE);
		assertEquals(2, status.getLastSentCount());

		List<JsonObject> log = errorLog();
		assertEquals(2, log.size());
		assertEquals(submitted.get(2), log.get(0).getAsJsonObject("event").get("eventId").getAsString());
		assertEquals("payload.boss: Too small", log.get(0).get("error").getAsString());
		assertEquals(200, log.get(0).get("httpStatus").getAsInt());
		assertTrue(log.get(0).has("loggedAt"));
		assertEquals(submitted.get(3), log.get(1).getAsJsonObject("event").get("eventId").getAsString());
		assertEquals("eventId: Invalid input", log.get(1).get("error").getAsString());

		// The event the server didn't mention is still queued and goes next time.
		sender.sendNow();
		awaitEmptyQueue();
		assertEquals(submitted.get(4), server.batches.get(1).eventIds.get(0));
	}

	@Test
	public void unreadable200IsTemporary() throws Exception
	{
		startSender();
		List<String> submitted = submit(sender, 2);
		server.script.add(batch -> FakeIngestServer.json(200, "<html>proxy</html>"));
		sender.sendNow();
		awaitEmptyQueue();
		assertEquals(2, server.batches.size());
		assertEquals(submitted, server.stored);
		assertTrue(errorLog().isEmpty());
	}

	// ---- 400, 413 --------------------------------------------------------------

	@Test
	public void badRequestMovesTheBatchToTheErrorLog() throws Exception
	{
		startSender();
		List<String> submitted = submit(sender, 3);
		server.script.add(batch -> FakeIngestServer.json(400,
			"{\"error\":{\"code\":\"BAD_REQUEST\",\"message\":\"Invalid JSON\",\"details\":[]}}"));
		sender.sendNow();
		SenderStatus status = await("error log", s -> s.getRejectedCount() == 3 && s.getQueueSize() == 0
			&& s.getState() == SenderStatus.State.IDLE);
		assertNull(status.getLastSentAt());
		List<JsonObject> log = errorLog();
		assertEquals(submitted, log.stream().map(e -> e.getAsJsonObject("event").get("eventId").getAsString())
			.collect(Collectors.toList()));
		assertEquals("Invalid JSON", log.get(0).get("error").getAsString());
		assertEquals(400, log.get(0).get("httpStatus").getAsInt());
	}

	@Test
	public void tooLargeSplitsTheBatch() throws Exception
	{
		startSender();
		List<String> submitted = submit(sender, 10);
		server.script.add(batch -> new MockResponse().setResponseCode(413));
		sender.sendNow();
		awaitEmptyQueue();
		List<Integer> sizes = server.batches.stream().map(b -> b.eventIds.size()).collect(Collectors.toList());
		assertEquals(Arrays.asList(10, 5, 5), sizes);
		assertEquals(submitted, server.stored);
		assertTrue(errorLog().isEmpty());
	}

	@Test
	public void singleEventTooLargeGoesToTheErrorLog() throws Exception
	{
		startSender();
		List<String> submitted = submit(sender, 1);
		server.script.add(batch -> new MockResponse().setResponseCode(413));
		sender.sendNow();
		await("error log", s -> s.getRejectedCount() == 1 && s.getQueueSize() == 0);
		JsonObject entry = errorLog().get(0);
		assertEquals(413, entry.get("httpStatus").getAsInt());
		assertEquals(submitted.get(0), entry.getAsJsonObject("event").get("eventId").getAsString());
	}

	// ---- 401, 404, redirect ------------------------------------------------------

	@Test
	public void unauthorizedStopsUntilTheKeyChanges() throws Exception
	{
		startSender();
		submit(sender, 2);
		server.script.add(batch -> new MockResponse().setResponseCode(401));
		sender.sendNow();
		SenderStatus stopped = await("stopped", s -> s.getState() == SenderStatus.State.STOPPED);
		assertEquals(SenderStatus.StopReason.UNAUTHORIZED, stopped.getStopReason());
		assertEquals(2, stopped.getQueueSize());

		// Send now, the threshold and logout do nothing while stopped.
		submit(sender, EventSender.SEND_THRESHOLD);
		sender.sendNow();
		sender.onLogout();
		sender.flush().get();
		assertEquals(1, server.batches.size());

		// A new key resumes sending.
		sender.updateSettings(new SenderSettings(true, server.url(), "ost_other_key"));
		sender.updateSettings(settings);
		awaitEmptyQueue();
		assertEquals(EventSender.SEND_THRESHOLD + 2, server.stored.size());
	}

	@Test
	public void notFoundStopsUntilTestConnectionSucceeds() throws Exception
	{
		startSender();
		submit(sender, 2);
		server.script.add(batch -> new MockResponse().setResponseCode(404));
		sender.sendNow();
		SenderStatus stopped = await("stopped", s -> s.getState() == SenderStatus.State.STOPPED);
		assertEquals(SenderStatus.StopReason.NOT_FOUND, stopped.getStopReason());
		assertTrue(stopped.getDetail().contains("404"));

		// A failed test does not resume sending.
		sender.onConnectionTested(testConnection(server.url(), "wrong_key"));
		sender.flush().get();
		assertEquals(SenderStatus.State.STOPPED, sender.getStatus().getState());

		sender.onConnectionTested(testConnection(server.url(), FakeIngestServer.KEY));
		awaitEmptyQueue();
		assertEquals(2, server.stored.size());
	}

	@Test
	public void unauthorizedVerifyStops() throws Exception
	{
		settings = new SenderSettings(true, server.url(), "ost_revoked_key");
		startSender();
		submit(sender, 1);
		sender.sendNow();
		SenderStatus stopped = await("stopped", s -> s.getState() == SenderStatus.State.STOPPED);
		assertEquals(SenderStatus.StopReason.UNAUTHORIZED, stopped.getStopReason());
		assertEquals(0, server.batches.size());
	}

	@Test
	public void redirectStopsAndIsNotFollowed() throws Exception
	{
		startSender();
		submit(sender, 1);
		server.script.add(batch -> new MockResponse().setResponseCode(301)
			.setHeader("Location", "https://example.invalid/"));
		sender.sendNow();
		SenderStatus stopped = await("stopped", s -> s.getState() == SenderStatus.State.STOPPED);
		assertEquals(SenderStatus.StopReason.REDIRECT, stopped.getStopReason());
		assertEquals(1, server.batches.size());
		assertEquals(1, stopped.getQueueSize());
	}

	// ---- 429, 5xx, network ------------------------------------------------------

	@Test
	public void rateLimitedRespectsRetryAfter() throws Exception
	{
		startSender();
		List<String> submitted = submit(sender, 1);
		server.script.add(batch -> new MockResponse().setResponseCode(429).setHeader("Retry-After", "1"));
		sender.sendNow();
		await("retrying", s -> s.getState() == SenderStatus.State.RETRYING && s.getRetryAt() != null);
		awaitEmptyQueue();
		assertEquals(submitted, server.stored);
		long gap = server.batches.get(1).receivedAtNanos - server.batches.get(0).receivedAtNanos;
		assertTrue("waited " + gap / 1_000_000 + " ms", gap >= TimeUnit.MILLISECONDS.toNanos(950));
	}

	@Test
	public void serverErrorsBackOffAndRetry() throws Exception
	{
		startSender();
		List<String> submitted = submit(sender, 3);
		server.script.add(batch -> new MockResponse().setResponseCode(500));
		server.script.add(batch -> new MockResponse().setResponseCode(503));
		server.script.add(batch -> new MockResponse().setResponseCode(502));
		sender.sendNow();
		SenderStatus retrying = await("retrying", s -> s.getState() == SenderStatus.State.RETRYING);
		assertTrue(retrying.getDetail(), retrying.getDetail().contains("HTTP 50"));
		awaitEmptyQueue();
		assertEquals(4, server.batches.size());
		assertEquals(submitted, server.stored);
	}

	@Test
	public void responseLostAfterTheServerStoredTheBatchLosesNothing() throws Exception
	{
		startSender();
		List<String> submitted = submit(sender, 4);
		// The server stores the events, then the connection drops before the answer.
		server.script.add(batch ->
		{
			server.acceptAll(batch);
			return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
		});
		sender.sendNow();
		awaitEmptyQueue();
		assertEquals(submitted, server.stored);
		assertEquals(2, server.batches.size());
		assertEquals(submitted, server.batches.get(1).eventIds);
	}

	@Test
	public void serverGoesDownAndComesBackWithoutLosingEvents() throws Exception
	{
		int port = server.server.getPort();
		startSender();
		List<String> submitted = new ArrayList<>(submit(sender, 30));
		sender.sendNow();
		awaitEmptyQueue();

		server.stop();
		submitted.addAll(submit(sender, 700));
		sender.sendNow();
		await("retrying while down", s -> s.getState() == SenderStatus.State.RETRYING);
		submitted.addAll(submit(sender, 20)); // still collecting while the server is away

		FakeIngestServer restarted = new FakeIngestServer();
		restarted.stored.addAll(server.stored);
		server = restarted.start(port);
		awaitEmptyQueue();

		assertEquals(submitted, server.stored);
		assertEquals(new HashSet<>(submitted).size(), server.stored.size());
		assertTrue(pendingLines().isEmpty());
		assertTrue(errorLog().isEmpty());
	}

	// ---- Contract check ----------------------------------------------------------

	@Test
	public void tooOldServerHoldsTheQueue() throws Exception
	{
		server.contractVersion = "1.13.0";
		startSender();
		List<String> submitted = submit(sender, 60);
		sender.sendNow();
		SenderStatus held = await("held", s -> s.getState() == SenderStatus.State.HELD);
		assertEquals("1.13.0", held.getServerContract());
		assertEquals(60, held.getQueueSize());
		assertEquals(0, held.getRejectedCount());
		assertEquals(0, server.batches.size());

		// The server is updated; "Send now" checks again.
		server.contractVersion = Contract.REQUIRED_VERSION;
		sender.sendNow();
		awaitEmptyQueue();
		assertEquals(submitted, server.stored);
	}

	@Test
	public void verifyNetworkErrorRetries() throws Exception
	{
		server.verifyOverride = new MockResponse().setResponseCode(503);
		startSender();
		submit(sender, 1);
		sender.sendNow();
		await("retrying", s -> s.getState() == SenderStatus.State.RETRYING);
		server.verifyOverride = null;
		awaitEmptyQueue();
		assertEquals(1, server.stored.size());
	}

	// ---- Config, disk and shutdown -----------------------------------------------

	@Test
	public void nothingIsSentWhileOffOrNotConfigured() throws Exception
	{
		settings = new SenderSettings(false, server.url(), FakeIngestServer.KEY);
		startSender();
		submit(sender, EventSender.SEND_THRESHOLD + 1);
		sender.sendNow();
		sender.flush().get();
		assertEquals(SenderStatus.State.OFF, sender.getStatus().getState());

		sender.updateSettings(new SenderSettings(true, "", FakeIngestServer.KEY));
		sender.sendNow();
		sender.flush().get();
		assertEquals(SenderStatus.State.NOT_CONFIGURED, sender.getStatus().getState());
		assertEquals(0, server.verifyCalls);
		assertEquals(0, server.batches.size());
		assertEquals(EventSender.SEND_THRESHOLD + 1, sender.getStatus().getQueueSize());

		sender.updateSettings(new SenderSettings(true, server.url(), FakeIngestServer.KEY));
		awaitEmptyQueue();
	}

	@Test
	public void queueSurvivesARestartOfTheSender() throws Exception
	{
		settings = new SenderSettings(false, server.url(), FakeIngestServer.KEY);
		startSender();
		List<String> submitted = submit(sender, 5);
		sender.flush().get();
		sender.shutdown();

		sender = newSender(new SenderSettings(true, server.url(), FakeIngestServer.KEY));
		awaitEmptyQueue();
		assertEquals(submitted, server.stored);
	}

	@Test
	public void flushWritesToDiskWithoutWaitingForTheNetwork() throws Exception
	{
		startSender();
		// The server takes the request and never answers.
		server.script.add(batch -> new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
		submit(sender, 2);
		sender.sendNow();
		await("sending", s -> s.getState() == SenderStatus.State.SENDING);

		submit(sender, 100);
		sender.flush().get(2, TimeUnit.SECONDS);
		assertEquals(102, pendingLines().size());
		assertEquals(SenderStatus.State.SENDING, sender.getStatus().getState());
	}

	@Test
	public void logsNeverContainTheKeyOrEventContents() throws Exception
	{
		ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
			org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ch.qos.logback.classic.Level oldLevel = root.getLevel();
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
			new ch.qos.logback.core.read.ListAppender<>();
		appender.start();
		root.addAppender(appender);
		root.setLevel(ch.qos.logback.classic.Level.ALL);
		try
		{
			startSender();
			submit(sender, 3);
			server.script.add(batch -> new MockResponse().setResponseCode(503));
			server.script.add(batch -> FakeIngestServer.json(400, "{\"error\":{\"message\":\"bad\"}}"));
			server.script.add(batch -> new MockResponse().setResponseCode(401));
			sender.sendNow();
			await("error log", s -> s.getRejectedCount() == 3);
			submit(sender, 1);
			sender.sendNow();
			await("stopped", s -> s.getState() == SenderStatus.State.STOPPED);
		}
		finally
		{
			root.detachAppender(appender);
			root.setLevel(oldLevel);
		}
		assertFalse(appender.list.isEmpty());
		for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list)
		{
			String message = event.getFormattedMessage();
			assertFalse(message, message.contains(FakeIngestServer.KEY));
			assertFalse(message, message.contains("Vorkath"));
		}
	}

	@Test
	public void flushAfterShutdownReturnsAtOnce() throws Exception
	{
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		executor.shutdown();
		EventSender s = new EventSender(executor, new EventQueue(Filepath.Unchecked.getRooted(queueDir()),
			RuneLiteAPI.GSON, Clock.systemUTC()), json, new IngestClient(http, RuneLiteAPI.GSON),
			new VerifyClient(http, RuneLiteAPI.GSON), new Backoff(Duration.ofMillis(1), Duration.ofMillis(1),
			new Random()), Clock.systemUTC(), Duration.ofHours(1), status -> {});
		assertTrue(s.flush().isDone());
		s.submit(Event.create(ids, 1L, Instant.now(), sessionId, Kc.builder().boss("x").killCount(1).build()));
		assertFalse(Files.exists(queueDir().resolve(EventQueue.PENDING_FILE)));
	}
}
