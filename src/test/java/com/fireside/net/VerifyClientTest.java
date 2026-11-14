package com.fireside.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.fireside.Contract;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class VerifyClientTest
{
	static final String KEY = "ost_test_0123456789abcdef";
	static final Instant T0 = Instant.parse("2026-11-13T18:00:00Z");

	private final Gson gson = new Gson();
	private MockWebServer server;
	private StepClock clock;
	private VerifyClient client;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		// Request sent at T0, response at T0 + 4 s: midpoint T0 + 2 s.
		clock = new StepClock(T0, T0.plusSeconds(4));
		client = new VerifyClient(new OkHttpClient(), gson, clock, Duration.ofSeconds(2), Duration.ofMillis(300));
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	private String baseUrl()
	{
		return server.url("/").toString();
	}

	static String okBody(String contractVersion, Instant serverTime)
	{
		return "{\"ok\":true,\"keyName\":\"Gaming PC\",\"serverVersion\":\"0.1.0\",\"contractVersion\":\""
			+ contractVersion + "\",\"serverTime\":\"" + format(serverTime) + "\"}";
	}

	private static String format(Instant instant)
	{
		return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
			.withZone(ZoneOffset.UTC).format(instant);
	}

	static VerifyResult run(VerifyClient client, String url, String key) throws Exception
	{
		CompletableFuture<VerifyResult> result = new CompletableFuture<>();
		client.verify(url, key, result::complete);
		return result.get(10, TimeUnit.SECONDS);
	}

	private VerifyResult run() throws Exception
	{
		return run(client, baseUrl(), KEY);
	}

	private void enqueueOk(String contractVersion, Instant serverTime)
	{
		server.enqueue(new MockResponse().setResponseCode(200)
			.setHeader("Content-Type", "application/json")
			.setBody(okBody(contractVersion, serverTime)));
	}

	@Test
	public void okSendsBearerKeyToVerifyPath() throws Exception
	{
		enqueueOk(Contract.REQUIRED_VERSION, T0.plusSeconds(2));
		VerifyResult result = run();

		assertEquals(VerifyResult.Outcome.OK, result.getOutcome());
		assertTrue(result.getWarnings().isEmpty());
		assertFalse(result.isContractTooOld());
		assertNotNull(result.getResponse());
		assertEquals("Gaming PC", result.getResponse().getKeyName());
		assertEquals("0.1.0", result.getResponse().getServerVersion());
		assertTrue(result.getMessage().contains("Gaming PC"));

		RecordedRequest request = server.takeRequest();
		assertEquals("GET", request.getMethod());
		assertEquals("/api/v1/ingest/verify", request.getPath());
		assertEquals("Bearer " + KEY, request.getHeader("Authorization"));
	}

	@Test
	public void keyIsTrimmed() throws Exception
	{
		enqueueOk(Contract.REQUIRED_VERSION, T0.plusSeconds(2));
		assertTrue(run(client, baseUrl(), "  " + KEY + "\n").isOk());
		assertEquals("Bearer " + KEY, server.takeRequest().getHeader("Authorization"));
	}

	@Test
	public void clockSkewUsesMidpoint() throws Exception
	{
		// 61.5 s after the request started, but 59.5 s after the midpoint: no warning.
		enqueueOk(Contract.REQUIRED_VERSION, T0.plusMillis(61_500));
		VerifyResult result = run();
		assertTrue(result.isOk());
		assertEquals(Duration.ofMillis(59_500), result.getClockSkew());
		assertTrue(result.getWarnings().isEmpty());
	}

	@Test
	public void warnsWhenLocalClockIsBehind() throws Exception
	{
		enqueueOk(Contract.REQUIRED_VERSION, T0.plusSeconds(2 + 61));
		VerifyResult result = run();
		assertTrue(result.isOk());
		assertEquals(Duration.ofSeconds(61), result.getClockSkew());
		assertEquals(1, result.getWarnings().size());
		assertTrue(result.getWarnings().get(0), result.getWarnings().get(0).contains("1 min 1 s behind"));
	}

	@Test
	public void warnsWhenLocalClockIsAhead() throws Exception
	{
		enqueueOk(Contract.REQUIRED_VERSION, T0.plusSeconds(2).minusSeconds(120));
		VerifyResult result = run();
		assertEquals(1, result.getWarnings().size());
		assertTrue(result.getWarnings().get(0), result.getWarnings().get(0).contains("2 min 0 s ahead"));
	}

	@Test
	public void noWarningAtExactlySixtySeconds() throws Exception
	{
		enqueueOk(Contract.REQUIRED_VERSION, T0.plusSeconds(2 + 60));
		assertTrue(run().getWarnings().isEmpty());
	}

	@Test
	public void warnsWhenServerContractIsTooOld() throws Exception
	{
		enqueueOk("1.12.0", T0.plusSeconds(2));
		VerifyResult result = run();
		assertTrue(result.isOk());
		assertTrue(result.isContractTooOld());
		assertEquals(1, result.getWarnings().size());
		assertTrue(result.getWarnings().get(0).contains("too old"));
	}

	@Test
	public void newerMinorIsFine() throws Exception
	{
		enqueueOk("1.99.0", T0.plusSeconds(2));
		VerifyResult result = run();
		assertFalse(result.isContractTooOld());
		assertTrue(result.getWarnings().isEmpty());
	}

	@Test
	public void warnsWhenServerHasNewerMajor() throws Exception
	{
		enqueueOk("2.0.0", T0.plusSeconds(2));
		VerifyResult result = run();
		assertFalse(result.isContractTooOld());
		assertEquals(1, result.getWarnings().size());
		assertTrue(result.getWarnings().get(0).contains("Update the plugin"));
	}

	@Test
	public void unauthorized() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer")
			.setBody("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid API key\",\"details\":[]}}"));
		VerifyResult result = run();
		assertEquals(VerifyResult.Outcome.UNAUTHORIZED, result.getOutcome());
		assertTrue(result.getMessage().contains("invalid or revoked"));
	}

	@Test
	public void notFound() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(404));
		VerifyResult result = run();
		assertEquals(VerifyResult.Outcome.NOT_FOUND, result.getOutcome());
		assertTrue(result.getMessage().contains("without /api/v1"));
	}

	@Test
	public void rateLimitedWithRetryAfter() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "37"));
		VerifyResult result = run();
		assertEquals(VerifyResult.Outcome.RATE_LIMITED, result.getOutcome());
		assertTrue(result.getMessage().contains("37 s"));
	}

	@Test
	public void rateLimitedWithoutRetryAfter() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(429));
		VerifyResult result = run();
		assertEquals(VerifyResult.Outcome.RATE_LIMITED, result.getOutcome());
		assertTrue(result.getMessage().contains("a minute"));
	}

	@Test
	public void serverErrors() throws Exception
	{
		for (int code : new int[]{500, 502, 503})
		{
			server.enqueue(new MockResponse().setResponseCode(code));
			VerifyResult result = run(new VerifyClient(new OkHttpClient(), gson, new StepClock(T0, T0),
				Duration.ofSeconds(2), Duration.ofSeconds(2)), baseUrl(), KEY);
			assertEquals(VerifyResult.Outcome.SERVER_ERROR, result.getOutcome());
			assertTrue(result.getMessage().contains("HTTP " + code));
		}
	}

	@Test
	public void redirectIsNotFollowed() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(301).setHeader("Location", "https://elsewhere.example/"));
		VerifyResult result = run();
		assertEquals(VerifyResult.Outcome.UNEXPECTED_RESPONSE, result.getOutcome());
		assertTrue(result.getMessage().contains("https://elsewhere.example/"));
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void otherStatusIsUnexpected() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(418));
		VerifyResult result = run();
		assertEquals(VerifyResult.Outcome.UNEXPECTED_RESPONSE, result.getOutcome());
		assertTrue(result.getMessage().contains("HTTP 418"));
	}

	@Test
	public void htmlBodyIsUnexpected() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200).setBody("<html><body>Router login</body></html>"));
		assertEquals(VerifyResult.Outcome.UNEXPECTED_RESPONSE, run().getOutcome());
	}

	@Test
	public void okFalseIsUnexpected() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200).setBody(okBody("1.13.0", T0).replace("true", "false")));
		assertEquals(VerifyResult.Outcome.UNEXPECTED_RESPONSE, run().getOutcome());
	}

	@Test
	public void timeout() throws Exception
	{
		server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
		assertEquals(VerifyResult.Outcome.TIMEOUT, run().getOutcome());
	}

	@Test
	public void connectionRefused() throws Exception
	{
		int port;
		try (ServerSocket socket = new ServerSocket(0))
		{
			port = socket.getLocalPort();
		}
		VerifyResult result = run(client, "http://127.0.0.1:" + port, KEY);
		assertEquals(VerifyResult.Outcome.CONNECTION_REFUSED, result.getOutcome());
	}

	@Test
	public void unknownHost() throws Exception
	{
		OkHttpClient noDns = new OkHttpClient.Builder()
			.dns(host ->
			{
				throw new UnknownHostException(host);
			})
			.build();
		VerifyClient c = new VerifyClient(noDns, gson, clock, Duration.ofSeconds(2), Duration.ofSeconds(2));
		VerifyResult result = run(c, "http://tracker.invalid:3000", KEY);
		assertEquals(VerifyResult.Outcome.UNKNOWN_HOST, result.getOutcome());
		assertTrue(result.getMessage().contains("tracker.invalid"));
	}

	@Test
	public void httpsToPlainHttpServerIsTlsError() throws Exception
	{
		// A plain-HTTP server that answers right away, without waiting for a request.
		try (ServerSocket plain = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
		{
			CompletableFuture.runAsync(() ->
			{
				try (Socket socket = plain.accept())
				{
					socket.getOutputStream().write(
						"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
					socket.getOutputStream().flush();
				}
				catch (IOException ignored)
				{
					// The test fails on the result instead.
				}
			});
			VerifyResult result = run(client, "https://127.0.0.1:" + plain.getLocalPort(), KEY);
			assertEquals(VerifyResult.Outcome.TLS_ERROR, result.getOutcome());
		}
	}

	@Test
	public void missingConfigMakesNoRequest() throws Exception
	{
		assertEquals(VerifyResult.Outcome.NOT_CONFIGURED, run(client, "", KEY).getOutcome());
		assertEquals(VerifyResult.Outcome.NOT_CONFIGURED, run(client, null, KEY).getOutcome());
		assertEquals(VerifyResult.Outcome.NOT_CONFIGURED, run(client, baseUrl(), "  ").getOutcome());
		assertEquals(VerifyResult.Outcome.NOT_CONFIGURED, run(client, baseUrl(), null).getOutcome());
		assertEquals(0, server.getRequestCount());
	}

	@Test
	public void invalidUrlMakesNoRequest() throws Exception
	{
		assertEquals(VerifyResult.Outcome.INVALID_URL, run(client, "localhost:3000", KEY).getOutcome());
		assertEquals(0, server.getRequestCount());
	}

	@Test
	public void invalidKeyMakesNoRequest() throws Exception
	{
		VerifyResult result = run(client, baseUrl(), "ost_abc def");
		assertEquals(VerifyResult.Outcome.INVALID_KEY, result.getOutcome());
		assertEquals(VerifyResult.Outcome.INVALID_KEY, run(client, baseUrl(), "ost_æøå").getOutcome());
		assertEquals(0, server.getRequestCount());
	}

	@Test
	public void noCallbackAfterCancel() throws Exception
	{
		server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
		CompletableFuture<VerifyResult> result = new CompletableFuture<>();
		okhttp3.Call call = client.verify(baseUrl(), KEY, result::complete);
		assertNotNull(call);
		server.takeRequest();
		call.cancel();
		// The read timeout (300 ms) has long passed by now if the callback were still coming.
		assertNull(result.completeOnTimeout(null, 1, TimeUnit.SECONDS).get());
	}

	/** A clock that returns the given instants in order, then repeats the last one. */
	static final class StepClock extends Clock
	{
		private final Deque<Instant> instants = new ArrayDeque<>();
		private Instant last;

		StepClock(Instant... instants)
		{
			for (Instant i : instants)
			{
				this.instants.add(i);
			}
			last = instants[0];
		}

		@Override
		public synchronized Instant instant()
		{
			if (!instants.isEmpty())
			{
				last = instants.poll();
			}
			return last;
		}

		@Override
		public ZoneId getZone()
		{
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return this;
		}
	}
}
