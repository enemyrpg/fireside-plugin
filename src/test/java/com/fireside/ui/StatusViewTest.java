package com.fireside.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fireside.send.ConnectionStatus;
import com.fireside.send.SenderStatus;
import com.fireside.send.SenderStatus.State;
import com.fireside.send.SenderStatus.StopReason;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import javax.annotation.Nullable;
import org.junit.Test;

public class StatusViewTest
{
	private static final String KEY = "fs_key";
	private static final String URL = "http://127.0.0.1:3100";
	private static final Instant NOW = Instant.parse("2026-11-18T18:00:00Z");
	private static final ZoneId UTC = ZoneOffset.UTC;

	private static ConnectionStatus connection(@Nullable Instant lastContact, int failures, boolean rateLimited,
		@Nullable Instant next)
	{
		return new ConnectionStatus(lastContact, failures, failures > 0 ? "Can't reach the server (connection "
			+ "refused)." : null, rateLimited, false, false, next, null, Collections.emptyList());
	}

	private static SenderStatus status(ConnectionStatus connection)
	{
		return status(State.IDLE, null, null, null, connection);
	}

	private static SenderStatus status(State state, @Nullable StopReason stop, @Nullable String detail,
		@Nullable String tooOld, ConnectionStatus connection)
	{
		return new SenderStatus(state, 0, 0, null, 0, null, detail, stop, tooOld, null, connection);
	}

	private static StatusView view(String url, String key, SenderStatus status)
	{
		return StatusView.compute(url, key, true, status, NOW, UTC, false, null);
	}

	private static StatusView view(SenderStatus status)
	{
		return view(URL, KEY, status);
	}

	// ---- Config ---------------------------------------------------------------------

	@Test
	public void notConfiguredWithoutUrlOrKey()
	{
		SenderStatus s = status(ConnectionStatus.NONE);
		assertEquals("Not configured", view("", KEY, s).getTitle());
		assertEquals("Not configured", view(URL, " ", s).getTitle());
		assertEquals("Not configured", StatusView.compute(null, null, true, s, NOW, UTC, false, null).getTitle());
		assertEquals(StatusView.Level.NEUTRAL, view("", KEY, s).getLevel());
	}

	@Test
	public void invalidUrlAndKey()
	{
		StatusView url = view("localhost:3100", KEY, status(ConnectionStatus.NONE));
		assertEquals(StatusView.Level.ERROR, url.getLevel());
		assertEquals("Invalid server URL", url.getTitle());

		StatusView key = view(URL, "ost key with spaces", status(ConnectionStatus.NONE));
		assertEquals(StatusView.Level.ERROR, key.getLevel());
		assertEquals("Invalid API key", key.getTitle());
	}

	@Test
	public void sendingOff()
	{
		StatusView view = StatusView.compute(URL, KEY, false, status(connection(NOW, 0, false, null)), NOW, UTC,
			false, null);
		assertEquals("Sending off", view.getTitle());
		assertEquals(StatusView.Level.NEUTRAL, view.getLevel());
	}

	// ---- Connection -----------------------------------------------------------------

	@Test
	public void checkingBeforeTheFirstAnswer()
	{
		StatusView view = view(status(ConnectionStatus.NONE));
		assertEquals("Checking…", view.getTitle());
		assertEquals(StatusView.Level.BUSY, view.getLevel());
	}

	@Test
	public void connectedShowsTheLastContact()
	{
		StatusView view = view(status(connection(NOW.minusSeconds(12), 0, false, null)));
		assertEquals("Connected", view.getTitle());
		assertEquals(StatusView.Level.OK, view.getLevel());
		assertEquals("Last contact: 12 s ago.", view.getMessage());
	}

	@Test
	public void noFailuresStaysConnectedHoweverOldTheContact()
	{
		// No polling while connected: logged out for an hour is still connected.
		StatusView view = view(status(connection(NOW.minus(Duration.ofHours(1)), 0, false, null)));
		assertEquals("Connected", view.getTitle());
		assertEquals("Last contact: 1 h ago.", view.getMessage());
	}

	@Test
	public void oneFailureKeepsConnectedForTwoMinutes()
	{
		StatusView fresh = view(status(connection(NOW.minusSeconds(30), 1, false, NOW.plusSeconds(5))));
		assertEquals("Connected", fresh.getTitle());

		StatusView stale = view(status(connection(NOW.minus(ConnectionStatus.STALE_AFTER).minusSeconds(1), 1, false,
			NOW.plusSeconds(5))));
		assertEquals("Can't reach the server – retrying", stale.getTitle());
	}

	@Test
	public void twoFailuresAreRetryingWithTheNextAttempt()
	{
		StatusView view = view(status(connection(NOW.minusSeconds(10), 2, false, Instant.parse(
			"2026-11-18T18:00:07Z"))));
		assertEquals("Can't reach the server – retrying", view.getTitle());
		assertEquals(StatusView.Level.WARNING, view.getLevel());
		assertTrue(view.getMessage(), view.getMessage().contains("connection refused"));
		assertTrue(view.getMessage(), view.getMessage().contains("Next attempt at 18:00:07"));
		assertTrue(view.getMessage(), view.getMessage().contains("Last contact: 10 s ago"));
	}

	@Test
	public void failureWithoutAnyContactIsRetrying()
	{
		assertEquals("Can't reach the server – retrying", view(status(connection(null, 1, false, null))).getTitle());
	}

	@Test
	public void rateLimitedSaysServerBusy()
	{
		StatusView view = view(status(connection(NOW.minusSeconds(10), 2, true, NOW.plusSeconds(30))));
		assertEquals("Server busy – retrying", view.getTitle());
		assertEquals(StatusView.Level.WARNING, view.getLevel());
	}

	// ---- Stops and contract -----------------------------------------------------------

	@Test
	public void stopsAreRedAndBeatAnOldContact()
	{
		ConnectionStatus connected = connection(NOW.minusSeconds(5), 0, false, null);
		StatusView key = view(status(State.STOPPED, StopReason.UNAUTHORIZED, null, null, connected));
		assertEquals("Invalid API key", key.getTitle());
		assertEquals(StatusView.Level.ERROR, key.getLevel());
		assertTrue(key.getMessage().contains("Test connection"));

		StatusView url = view(status(State.STOPPED, StopReason.NOT_FOUND, null, null, connected));
		assertEquals("Wrong server URL", url.getTitle());
		assertEquals(StatusView.Level.ERROR, url.getLevel());
		assertTrue(url.getMessage().contains("404"));

		StatusView redirect = view(status(State.STOPPED, StopReason.REDIRECT,
			"The server redirected (HTTP 301) to https://x/.", null, connected));
		assertEquals("Wrong server URL", redirect.getTitle());
		assertTrue(redirect.getMessage().startsWith("The server redirected (HTTP 301)"));
	}

	@Test
	public void tooOldServer()
	{
		StatusView view = view(status(State.HELD, null, null, "1.12.0", connection(NOW, 0, false, null)));
		assertEquals("Server too old", view.getTitle());
		assertEquals(StatusView.Level.ERROR, view.getLevel());
		assertTrue(view.getMessage().contains("1.12.0"));
		assertTrue(view.getMessage().contains("5 minutes"));
	}

	// ---- Warnings -----------------------------------------------------------------------

	@Test
	public void verifyWarningsStayBelowTheStatus()
	{
		String skew = "Your computer's clock is 2 min 0 s ahead of the server's.";
		ConnectionStatus c = new ConnectionStatus(NOW.minusSeconds(3), 0, null, false, false, false, null, null,
			Collections.singletonList(skew));
		StatusView view = view(status(c));
		assertEquals("Connected with warnings", view.getTitle());
		assertEquals(StatusView.Level.WARNING, view.getLevel());
		assertEquals(Collections.singletonList(skew), view.getWarnings());
	}

	@Test
	public void insecureUrlWarns()
	{
		StatusView view = view("http://192.168.1.2:3100", KEY, status(connection(NOW, 0, false, null)));
		assertEquals(Collections.singletonList(StatusView.INSECURE_WARNING), view.getWarnings());
		assertEquals("Connected with warnings", view.getTitle());
	}

	@Test
	public void lootTrackerAndBankWarningsInEveryState()
	{
		String line = "Bank not sent: unexpected bank layout. The next bank close tries again.";
		StatusView notConfigured = StatusView.compute("", KEY, true, status(ConnectionStatus.NONE), NOW, UTC, true,
			line);
		assertEquals(java.util.Arrays.asList(StatusView.LOOT_TRACKER_OFF_WARNING, line), notConfigured.getWarnings());

		StatusView invalid = StatusView.compute("localhost:3100", KEY, true, status(ConnectionStatus.NONE), NOW, UTC,
			true, null);
		assertTrue(invalid.getWarnings().contains(StatusView.LOOT_TRACKER_OFF_WARNING));

		StatusView connected = StatusView.compute(URL, KEY, true, status(connection(NOW, 0, false, null)), NOW, UTC,
			false, null);
		assertTrue(connected.getWarnings().isEmpty());
	}

	@Test
	public void agoFormatting()
	{
		assertEquals("–", StatusView.ago(null, NOW));
		assertEquals("0 s ago", StatusView.ago(NOW, NOW));
		assertEquals("0 s ago", StatusView.ago(NOW.plusSeconds(3), NOW));
		assertEquals("59 s ago", StatusView.ago(NOW.minusSeconds(59), NOW));
		assertEquals("2 min ago", StatusView.ago(NOW.minusSeconds(150), NOW));
		assertEquals("3 h ago", StatusView.ago(NOW.minus(Duration.ofHours(3)), NOW));
		assertFalse(StatusView.ago(NOW, NOW).isEmpty());
	}

	@Test
	public void panelIconIsSixteenPixelsAndNotEmpty()
	{
		BufferedImage icon = PanelIcon.create();
		assertEquals(PanelIcon.SIZE, icon.getWidth());
		assertEquals(PanelIcon.SIZE, icon.getHeight());
		assertTrue("Icon is empty", (icon.getRGB(8, 8) >>> 24) != 0);
	}
}
