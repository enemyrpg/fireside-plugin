package com.fireside.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fireside.send.SenderStatus;
import com.fireside.send.SenderStatus.State;
import com.fireside.send.SenderStatus.StopReason;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.annotation.Nullable;
import org.junit.Test;

public class QueueViewTest
{
	private static final Instant AT = Instant.parse("2026-11-13T18:04:12.512Z");

	private static SenderStatus status(State state, int queued, long rejected, @Nullable StopReason stop)
	{
		return new SenderStatus(state, queued, rejected, null, 0, null, null, stop, null, null);
	}

	private static QueueView view(SenderStatus status)
	{
		return QueueView.compute(status, ZoneOffset.UTC);
	}

	@Test
	public void stoppedSaysWhyAndHowToResumeAndDisablesSendNow()
	{
		QueueView key = view(status(State.STOPPED, 3, 0, StopReason.UNAUTHORIZED));
		assertEquals(StatusView.Level.ERROR, key.getLevel());
		assertEquals("Sending stopped", key.getTitle());
		assertTrue(key.getMessage(), key.getMessage().startsWith("Invalid API key"));
		assertTrue(key.getMessage().contains("Test connection"));
		assertTrue(key.getMessage().contains("sending resumes"));
		assertFalse(key.isSendNowEnabled());
		assertTrue(key.getSendNowTooltip().contains("stopped"));

		QueueView url = view(status(State.STOPPED, 3, 0, StopReason.NOT_FOUND));
		assertTrue(url.getMessage(), url.getMessage().startsWith("Wrong server URL"));
		assertTrue(url.getMessage().contains("404"));
		assertTrue(url.getMessage().contains("Test connection"));
		assertFalse(url.isSendNowEnabled());

		QueueView redirect = view(new SenderStatus(State.STOPPED, 1, 0, null, 0, null,
			"The server redirected (HTTP 301) to https://x/.", StopReason.REDIRECT, null, null));
		assertTrue(redirect.getMessage().startsWith("The server redirected (HTTP 301)"));
		assertTrue(redirect.getMessage().contains("Test connection"));
	}

	@Test
	public void sendNowOnlyWhenItCanDoSomething()
	{
		assertTrue(view(status(State.IDLE, 5, 0, null)).isSendNowEnabled());
		assertTrue(view(status(State.RETRYING, 5, 0, null)).isSendNowEnabled());
		assertTrue(view(status(State.HELD, 5, 0, null)).isSendNowEnabled());
		for (State s : new State[]{State.STARTING, State.OFF, State.NOT_CONFIGURED, State.CHECKING, State.SENDING})
		{
			assertFalse(s.toString(), view(status(s, 5, 0, null)).isSendNowEnabled());
		}
	}

	@Test
	public void rejectedCountOnlyWhenAboveZero()
	{
		QueueView none = view(status(State.IDLE, 0, 0, null));
		assertNull(none.getRejected());
		assertTrue(none.getWarnings().isEmpty());
		assertEquals("Up to date", none.getTitle());

		QueueView some = view(status(State.IDLE, 0, 2, null));
		assertEquals("2", some.getRejected());
		assertEquals(1, some.getWarnings().size());
		assertTrue(some.getWarnings().get(0).contains("rejected.jsonl"));
		assertTrue(some.getWarnings().get(0).contains("Open folder"));
	}

	@Test
	public void warnsAboveTenThousandQueued()
	{
		assertTrue(view(status(State.IDLE, QueueView.LARGE_QUEUE, 0, null)).getWarnings().isEmpty());
		QueueView large = view(status(State.RETRYING, QueueView.LARGE_QUEUE + 1, 0, null));
		assertEquals(1, large.getWarnings().size());
		assertTrue(large.getWarnings().get(0).contains("are kept"));
	}

	@Test
	public void retryingShowsWhenAndWhy()
	{
		QueueView v = view(new SenderStatus(State.RETRYING, 4, 0, null, 0, AT, "The server had an error (HTTP 503).",
			null, null, null));
		assertEquals(StatusView.Level.WARNING, v.getLevel());
		assertEquals("Retrying at 18:04:12", v.getTitle());
		assertTrue(v.getMessage().startsWith("The server had an error (HTTP 503)."));
		assertTrue(v.getMessage().contains("kept"));
	}

	@Test
	public void heldNamesBothVersions()
	{
		QueueView v = view(new SenderStatus(State.HELD, 4, 0, null, 0, null, null, null, "1.12.0", null));
		assertTrue(v.getMessage(), v.getMessage().contains("1.12.0"));
		assertTrue(v.getMessage(), v.getMessage().contains(com.fireside.Contract.REQUIRED_VERSION));
		assertTrue(v.getMessage().contains("kept"));
	}

	@Test
	public void lastSentAndStorageError()
	{
		QueueView v = view(new SenderStatus(State.IDLE, 0, 0, AT, 42, null, null, null, null,
			"Can't write the event queue (IOException: disk full)."));
		assertEquals("18:04:12 (42)", v.getLastSent());
		assertTrue(v.getWarnings().get(0).startsWith("Can't write the event queue"));
		assertEquals("–", view(status(State.IDLE, 0, 0, null)).getLastSent());
	}

	@Test
	public void offAndNotConfigured()
	{
		assertEquals("Sending is off", view(status(State.OFF, 0, 0, null)).getTitle());
		assertEquals("Not configured", view(status(State.NOT_CONFIGURED, 0, 0, null)).getTitle());
	}
}
