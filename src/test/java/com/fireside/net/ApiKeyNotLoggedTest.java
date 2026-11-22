package com.fireside.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.Gson;
import com.fireside.Contract;
import java.time.Duration;
import java.time.Instant;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/** The API key never shows up in log output or in user-facing messages. */
public class ApiKeyNotLoggedTest
{
	private static final String KEY = "fs_secret_key_must_not_leak_42";

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private Logger root;
	private Level oldLevel;
	private MockWebServer server;
	private VerifyClient client;

	@Before
	public void setUp() throws Exception
	{
		root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		oldLevel = root.getLevel();
		root.setLevel(Level.ALL);
		appender.start();
		root.addAppender(appender);

		server = new MockWebServer();
		server.start();
		Instant t0 = Instant.parse("2026-11-13T18:00:00Z");
		client = new VerifyClient(new OkHttpClient(), new Gson(), new VerifyClientTest.StepClock(t0, t0),
			Duration.ofSeconds(2), Duration.ofSeconds(2));
	}

	@After
	public void tearDown() throws Exception
	{
		root.detachAppender(appender);
		root.setLevel(oldLevel);
		server.shutdown();
	}

	@Test
	public void keyIsNeverLoggedOrShown() throws Exception
	{
		String url = server.url("/").toString();

		server.enqueue(new MockResponse().setResponseCode(200)
			.setBody(VerifyClientTest.okBody(Contract.REQUIRED_VERSION, Instant.parse("2026-11-13T18:00:00Z"))));
		assertNoKey(VerifyClientTest.run(client, url, KEY));

		server.enqueue(new MockResponse().setResponseCode(401));
		assertNoKey(VerifyClientTest.run(client, url, KEY));

		// OkHttp would put an invalid header value in its exception message.
		assertNoKey(VerifyClientTest.run(client, url, KEY + "\n" + KEY));
		assertNoKey(VerifyClientTest.run(client, url, KEY + " x"));

		server.shutdown();
		assertNoKey(VerifyClientTest.run(client, url, KEY));

		assertFalse("Expected some debug logging", appender.list.isEmpty());
		for (ILoggingEvent event : appender.list)
		{
			assertFalse(event.getFormattedMessage(), event.getFormattedMessage().contains(KEY));
			for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause())
			{
				assertFalse(String.valueOf(t.getMessage()).contains(KEY));
			}
		}
	}

	private static void assertNoKey(VerifyResult result)
	{
		assertFalse(result.getMessage(), result.getMessage().contains(KEY));
		for (String warning : result.getWarnings())
		{
			assertFalse(warning, warning.contains(KEY));
		}
		assertEquals(false, result.getMessage().isEmpty());
	}
}
