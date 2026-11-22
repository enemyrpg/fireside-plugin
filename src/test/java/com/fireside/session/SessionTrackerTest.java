package com.fireside.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import com.fireside.contract.ContractPackage;
import com.fireside.event.AccountType;
import com.fireside.event.Event;
import com.fireside.event.EventJson;
import com.fireside.event.EventType;
import com.fireside.event.Kc;
import com.fireside.event.SessionAfk;
import com.fireside.event.SessionEnd;
import com.fireside.event.SessionHeartbeat;
import com.fireside.event.SessionStart;
import com.fireside.event.UuidV7Generator;
import com.fireside.event.XpSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SessionTrackerTest
{
	private static final Instant T0 = Instant.parse("2026-11-08T19:00:00Z");
	private static final Duration TICK = Duration.ofMillis(600);
	private static final long ACCOUNT = 1234567890123456789L;

	private final FakeClock clock = new FakeClock(T0);
	private final FakeGame game = new FakeGame();
	private final List<Event> events = new ArrayList<>();
	private Duration threshold = Duration.ofMinutes(5);
	/** Off for the session-only tests; the XP tests turn it on. */
	private boolean xpEnabled;
	private final SessionTracker tracker = new SessionTracker(game, new UuidV7Generator(), clock, events::add,
		() -> threshold, () -> xpEnabled, "0.1.0");

	@Before
	public void setUp()
	{
		tracker.setEnabled(true);
	}

	/** Every event the tracker made is valid for the server, and IDs follow creation order. */
	@After
	public void eventsAreValidAndInOrder()
	{
		EventJson json = new EventJson(RuneLiteAPI.GSON);
		JsonSchema envelope = ContractPackage.schema("schema/event.json");
		UUID previous = null;
		for (Event event : events)
		{
			String line = json.toJson(event);
			JsonSchema typeSchema = ContractPackage.schema("schema/events/" + event.getType().wireName() + ".json");
			for (JsonSchema schema : Arrays.asList(envelope, typeSchema))
			{
				Set<ValidationMessage> errors = ContractPackage.validate(schema, line);
				assertTrue(errors + "\n" + line, errors.isEmpty());
			}
			UUID id = UUID.fromString(event.getEventId());
			if (previous != null)
			{
				assertTrue("eventIds strictly increasing", compareUnsigned(previous, id) < 0);
			}
			previous = id;
		}
	}

	@Test
	public void startWaitsForTheAccountHashAndName()
	{
		game.hash = -1;
		game.name = null;
		tracker.onGameState(GameState.LOGGED_IN);
		tick();
		game.hash = ACCOUNT;
		tick();
		assertTrue("No session without a name", events.isEmpty());
		game.name = "Test Main";
		tick();

		assertEquals(types(EventType.SESSION_START), types());
		Event start = events.get(0);
		assertEquals(ACCOUNT, start.getAccountHash());
		assertEquals(new SessionStart("Test Main", AccountType.IRONMAN, 420, "0.1.0"), start.getPayload());
		assertNotEquals("sessionId is its own UUID", start.getEventId(), start.getSessionId());
	}

	@Test
	public void heartbeatEverySixtySecondsOfWallClock()
	{
		login();
		Instant start = events.get(0).getOccurredAt();
		play(Duration.ofMinutes(3).plusSeconds(1));

		List<Event> heartbeats = ofType(EventType.SESSION_HEARTBEAT);
		assertEquals(3, heartbeats.size());
		for (int i = 0; i < 3; i++)
		{
			Duration sinceStart = Duration.between(start, heartbeats.get(i).getOccurredAt());
			assertTrue(sinceStart.toMillis() >= (i + 1) * 60_000L);
			assertTrue("At most one game tick late", sinceStart.toMillis() < (i + 1) * 60_000L + TICK.toMillis());
		}
		SessionHeartbeat heartbeat = (SessionHeartbeat) heartbeats.get(0).getPayload();
		assertEquals(12342, heartbeat.getRegionId());
		assertEquals(420, heartbeat.getWorld());
		assertEquals(Collections.singletonList("MEMBERS"), heartbeat.getWorldTypes());
		assertFalse(heartbeat.isIdle());
		assertSameSession();
	}

	@Test
	public void logoutEndsTheSession()
	{
		login();
		play(Duration.ofMinutes(2));
		assertTrue("Caller sends at logout", tracker.onGameState(GameState.LOGIN_SCREEN));

		Event end = last();
		assertEquals(new SessionEnd(SessionEnd.Reason.LOGOUT), end.getPayload());
		assertEquals(clock.instant(), end.getOccurredAt());
		assertSameSession();
		assertEquals(SessionTracker.State.LOGGED_OUT, tracker.state());

		tick();
		assertEquals("Nothing after logout", end, last());
	}

	@Test
	public void hopEndsTheSessionAndStartsANewOneOnTheNewWorld()
	{
		login();
		play(Duration.ofMinutes(2));
		tracker.onGameState(GameState.HOPPING);
		Event end = last();
		assertEquals(new SessionEnd(SessionEnd.Reason.HOP), end.getPayload());

		tracker.onGameState(GameState.LOADING);
		game.world = 302;
		tracker.onGameState(GameState.LOGGED_IN);
		tick();

		Event start = last();
		assertEquals(EventType.SESSION_START, start.getType());
		assertEquals(302, ((SessionStart) start.getPayload()).getWorld());
		assertNotEquals("New session after a hop", end.getSessionId(), start.getSessionId());
		play(Duration.ofSeconds(61));
		assertEquals(start.getSessionId(), last().getSessionId());
		assertEquals(302, ((SessionHeartbeat) last().getPayload()).getWorld());
	}

	@Test
	public void clientClosedEndsTheSession()
	{
		login();
		play(Duration.ofSeconds(90));
		tracker.onClientShutdown();
		assertEquals(new SessionEnd(SessionEnd.Reason.CLIENT_CLOSED), last().getPayload());
		assertSameSession();
	}

	@Test
	public void shortConnectionLossKeepsTheSessionWithoutAHeartbeatBurst()
	{
		login();
		play(Duration.ofSeconds(50));
		tracker.onGameState(GameState.CONNECTION_LOST);
		// No game ticks while the connection is lost.
		clock.advance(Duration.ofSeconds(150));
		tracker.onGameState(GameState.LOADING);
		tracker.onGameState(GameState.LOGGED_IN);
		tick();

		assertEquals(types(EventType.SESSION_START, EventType.SESSION_HEARTBEAT), types());
		Instant resumedAt = clock.instant();
		play(Duration.ofSeconds(59));
		assertEquals("Next heartbeat 60 s after the late one", 2, events.size());
		play(Duration.ofSeconds(2));
		assertEquals(3, events.size());
		assertTrue(Duration.between(resumedAt, last().getOccurredAt()).getSeconds() >= 60);
		assertSameSession();
	}

	@Test
	public void connectionLossThatEndsAtTheLoginScreenEndsWhenTheConnectionWasLost()
	{
		login();
		play(Duration.ofSeconds(30));
		tracker.onGameState(GameState.CONNECTION_LOST);
		Instant lostAt = clock.instant();
		clock.advance(Duration.ofSeconds(40));
		tracker.onGameState(GameState.LOGIN_SCREEN);

		assertEquals(new SessionEnd(SessionEnd.Reason.LOGOUT), last().getPayload());
		assertEquals(lostAt, last().getOccurredAt());
	}

	@Test
	public void afkPeriodIsSentWhenActivityResumes()
	{
		login();
		play(Duration.ofSeconds(10));
		Instant leftAt = clock.instant();
		game.idleFrom(leftAt);
		play(Duration.ofMinutes(7));
		SessionHeartbeat lastHeartbeat = (SessionHeartbeat) ofType(EventType.SESSION_HEARTBEAT)
			.get(ofType(EventType.SESSION_HEARTBEAT).size() - 1).getPayload();
		assertTrue("Heartbeats after the threshold are idle", lastHeartbeat.isIdle());
		assertTrue(ofType(EventType.SESSION_AFK).isEmpty());

		game.idleFrom(null);
		tick();
		SessionAfk afk = (SessionAfk) last().getPayload();
		assertEquals(leftAt, afk.getStartedAt());
		assertEquals(clock.instant(), afk.getEndedAt());
		assertSameSession();
	}

	@Test
	public void xpGainsCountAsActivity()
	{
		tracker.onStat("FISHING", 1000, 10);
		login();
		game.idleFrom(clock.instant());
		for (int i = 0; i < 10; i++)
		{
			play(Duration.ofMinutes(1));
			tracker.onStat("FISHING", 1000 + i * 50 + 50, 10);
		}
		play(Duration.ofSeconds(1));
		assertTrue(ofType(EventType.SESSION_AFK).isEmpty());
		assertFalse(((SessionHeartbeat) ofType(EventType.SESSION_HEARTBEAT).get(9).getPayload()).isIdle());
	}

	@Test
	public void afkAcrossLogoutIsClosedBeforeSessionEnd()
	{
		login();
		Instant leftAt = clock.instant();
		game.idleFrom(leftAt);
		play(Duration.ofMinutes(6));
		tracker.onGameState(GameState.LOGIN_SCREEN);
		Instant logoutAt = clock.instant();

		List<Event> tail = events.subList(events.size() - 2, events.size());
		assertEquals(EventType.SESSION_AFK, tail.get(0).getType());
		assertEquals(EventType.SESSION_END, tail.get(1).getType());
		assertEquals(new SessionAfk(leftAt, logoutAt), tail.get(0).getPayload());
		assertSameSession();

		// A new login starts fresh: the old idle time does not carry over.
		clock.advance(Duration.ofMinutes(3));
		game.idleFrom(null);
		login();
		play(Duration.ofMinutes(2));
		tracker.onGameState(GameState.LOGIN_SCREEN);
		assertEquals(1, ofType(EventType.SESSION_AFK).size());
	}

	@Test
	public void afkCrossedBetweenTicksIsClosedAtLogout()
	{
		login();
		Instant leftAt = clock.instant();
		game.idleFrom(leftAt);
		Instant logoutAt = leftAt.plus(Duration.ofMinutes(5));
		play(Duration.ofMinutes(5).minusMillis(300));
		assertFalse("Threshold not reached at the last tick", clock.instant().plus(TICK).isBefore(logoutAt));
		clock.advance(Duration.between(clock.instant(), logoutAt));
		tracker.onGameState(GameState.LOGIN_SCREEN);
		assertEquals(new SessionAfk(leftAt, logoutAt),
			ofType(EventType.SESSION_AFK).get(0).getPayload());
	}

	@Test
	public void pluginStopSendsAnOpenAfkPeriodButNoSessionEnd()
	{
		login();
		game.idleFrom(clock.instant());
		play(Duration.ofMinutes(6));
		tracker.stop();
		assertEquals(EventType.SESSION_AFK, last().getType());
		assertTrue(ofType(EventType.SESSION_END).isEmpty());
		tick();
		assertEquals(EventType.SESSION_AFK, last().getType());
	}

	@Test
	public void nothingIsCollectedWhileSendingIsOff()
	{
		tracker.setEnabled(false);
		tracker.onGameState(GameState.LOGGED_IN);
		play(Duration.ofMinutes(3));
		tracker.onGameState(GameState.LOGIN_SCREEN);
		assertTrue(events.isEmpty());
	}

	@Test
	public void turningSendingOnMidGameStartsASession()
	{
		tracker.setEnabled(false);
		tracker.onGameState(GameState.LOGGED_IN);
		play(Duration.ofMinutes(1));
		tracker.setEnabled(true);
		tick();
		assertEquals(types(EventType.SESSION_START), types());

		tracker.setEnabled(false);
		play(Duration.ofMinutes(2));
		assertEquals("Off stops the session", 1, events.size());
	}

	@Test
	public void startUpWhileLoggedIn()
	{
		// The plugin replays the current game state at startup.
		tracker.onGameState(GameState.LOGGED_IN);
		tick();
		assertEquals(types(EventType.SESSION_START), types());
	}

	@Test
	public void unknownAccountTypeIsSentAsNormal()
	{
		assertEquals(AccountType.NORMAL, ClientGameView.accountType(7));
		assertEquals(AccountType.NORMAL, ClientGameView.accountType(-1));
		assertEquals(AccountType.UNRANKED_GROUP_IRONMAN, ClientGameView.accountType(6));
		assertEquals(AccountType.HARDCORE_IRONMAN, ClientGameView.accountType(3));
	}

	@Test
	public void loginSnapshotRightAfterSessionStart()
	{
		xpEnabled = true;
		login();
		assertEquals(types(EventType.SESSION_START, EventType.XP_SNAPSHOT), types());
		XpSnapshot snapshot = (XpSnapshot) last().getPayload();
		assertEquals(XpSnapshot.Reason.LOGIN, snapshot.getReason());
		assertEquals(Skill.values().length, snapshot.getSkills().size());
		assertEquals(new XpSnapshot.SkillXp(1000, 10), snapshot.getSkills().get("SAILING"));
		assertEquals(events.get(0).getOccurredAt(), last().getOccurredAt());
		assertSameSession();
	}

	@Test
	public void loginSnapshotWaitsForTheStats()
	{
		xpEnabled = true;
		game.skills = null;
		login();
		play(Duration.ofSeconds(3));
		assertTrue(ofType(EventType.XP_SNAPSHOT).isEmpty());
		game.skills = allSkills(5000, 20);
		tick();
		tick();
		List<Event> snapshots = ofType(EventType.XP_SNAPSHOT);
		assertEquals(1, snapshots.size());
		XpSnapshot snapshot = (XpSnapshot) snapshots.get(0).getPayload();
		assertEquals(XpSnapshot.Reason.LOGIN, snapshot.getReason());
		assertEquals(new XpSnapshot.SkillXp(5000, 20), snapshot.getSkills().get("ATTACK"));
	}

	@Test
	public void noIntervalSnapshotWithoutXpChange()
	{
		xpEnabled = true;
		login();
		play(Duration.ofMinutes(16));
		assertEquals("Only the login snapshot", 1, ofType(EventType.XP_SNAPSHOT).size());
	}

	@Test
	public void intervalSnapshotEveryFiveMinutesWhenXpChanged()
	{
		xpEnabled = true;
		login();
		Instant loginAt = clock.instant();
		for (int i = 0; i < 20; i++)
		{
			play(Duration.ofSeconds(14));
			game.gainXp("FISHING", 50);
		}
		// 280 s of gains, then nothing until 16 min.
		play(Duration.ofMinutes(16).minus(Duration.between(loginAt, clock.instant())));

		List<Event> snapshots = ofType(EventType.XP_SNAPSHOT);
		assertEquals("Login + one interval (later checks see no change)", 2, snapshots.size());
		Event interval = snapshots.get(1);
		XpSnapshot payload = (XpSnapshot) interval.getPayload();
		assertEquals(XpSnapshot.Reason.INTERVAL, payload.getReason());
		assertEquals(2000, payload.getSkills().get("FISHING").getXp());
		long sinceLogin = Duration.between(loginAt, interval.getOccurredAt()).toMillis();
		assertTrue(sinceLogin >= 300_000 && sinceLogin < 300_000 + TICK.toMillis());
		assertSameSession();
	}

	@Test
	public void logoutSnapshotFromCachedValuesBeforeSessionEnd()
	{
		xpEnabled = true;
		login();
		play(Duration.ofMinutes(1));
		game.gainXp("ATTACK", 500);
		// The client clears its stats at logout.
		game.skills = null;
		tracker.onStat("ATTACK", 0, 0);
		tracker.onGameState(GameState.LOGIN_SCREEN);

		assertEquals(types(EventType.SESSION_START, EventType.XP_SNAPSHOT, EventType.SESSION_HEARTBEAT,
			EventType.XP_SNAPSHOT, EventType.SESSION_END), types());
		Event logout = events.get(3);
		XpSnapshot payload = (XpSnapshot) logout.getPayload();
		assertEquals(XpSnapshot.Reason.LOGOUT, payload.getReason());
		assertEquals(new XpSnapshot.SkillXp(1500, 10), payload.getSkills().get("ATTACK"));
		assertEquals(Skill.values().length, payload.getSkills().size());
		assertEquals(clock.instant(), logout.getOccurredAt());
		assertSameSession();
	}

	@Test
	public void logoutSnapshotOnHopAndClientClose()
	{
		xpEnabled = true;
		login();
		tracker.onGameState(GameState.HOPPING);
		assertLogoutThenEnd();
		String firstSession = last().getSessionId();

		tracker.onGameState(GameState.LOADING);
		tracker.onGameState(GameState.LOGGED_IN);
		tick();
		Event login = last();
		assertEquals(XpSnapshot.Reason.LOGIN, ((XpSnapshot) login.getPayload()).getReason());
		assertNotEquals(firstSession, login.getSessionId());

		tracker.onClientShutdown();
		assertLogoutThenEnd();
	}

	@Test
	public void logoutAfterConnectionLossIsAtTheLossTime()
	{
		xpEnabled = true;
		login();
		play(Duration.ofSeconds(30));
		tracker.onGameState(GameState.CONNECTION_LOST);
		Instant lostAt = clock.instant();
		clock.advance(Duration.ofSeconds(40));
		tracker.onGameState(GameState.LOGIN_SCREEN);
		assertLogoutThenEnd();
		assertEquals(lostAt, events.get(events.size() - 2).getOccurredAt());
	}

	@Test
	public void afkIsClosedBeforeTheLogoutSnapshot()
	{
		xpEnabled = true;
		login();
		game.idleFrom(clock.instant());
		play(Duration.ofMinutes(6));
		tracker.onGameState(GameState.LOGIN_SCREEN);
		List<EventType> tail = types().subList(events.size() - 3, events.size());
		assertEquals(types(EventType.SESSION_AFK, EventType.XP_SNAPSHOT, EventType.SESSION_END), tail);
	}

	@Test
	public void xpToggleOffSendsNoSnapshotsButXpStillCountsAsActivity()
	{
		login();
		game.idleFrom(clock.instant());
		for (int i = 0; i < 12; i++)
		{
			play(Duration.ofMinutes(1));
			game.gainXp("FISHING", 50);
		}
		tracker.onGameState(GameState.LOGIN_SCREEN);
		assertTrue(ofType(EventType.XP_SNAPSHOT).isEmpty());
		assertTrue(ofType(EventType.SESSION_AFK).isEmpty());
		assertEquals(EventType.SESSION_END, last().getType());
	}

	@Test
	public void xpToggledOnMidSessionSendsAnIntervalBaseline()
	{
		login();
		play(Duration.ofMinutes(2));
		xpEnabled = true;
		tick();
		XpSnapshot baseline = (XpSnapshot) last().getPayload();
		assertEquals(XpSnapshot.Reason.INTERVAL, baseline.getReason());

		xpEnabled = false;
		tick();
		game.gainXp("MINING", 100);
		play(Duration.ofMinutes(6));
		tracker.onGameState(GameState.LOGIN_SCREEN);
		assertEquals("No more snapshots once off", 1, ofType(EventType.XP_SNAPSHOT).size());
	}

	@Test
	public void pluginStopSendsNoLogoutSnapshot()
	{
		xpEnabled = true;
		login();
		play(Duration.ofSeconds(30));
		tracker.stop();
		assertEquals(1, ofType(EventType.XP_SNAPSHOT).size());
	}

	@Test
	public void gameEventsGoIntoTheActiveSession()
	{
		Kc kc = Kc.builder().boss("Zulrah").killCount(4).build();
		assertFalse("No session yet", tracker.emitInSession(kc));
		assertTrue(events.isEmpty());

		login();
		play(Duration.ofSeconds(5));
		assertTrue(tracker.emitInSession(kc));
		Event event = events.get(events.size() - 1);
		assertEquals(EventType.KC, event.getType());
		assertEquals(ACCOUNT, event.getAccountHash());
		assertEquals(events.get(0).getSessionId(), event.getSessionId());
		assertEquals(clock.instant(), event.getOccurredAt());

		tracker.setEnabled(false);
		int before = events.size();
		assertFalse("Send data off", tracker.emitInSession(kc));
		assertEquals(before, events.size());
	}

	private void assertLogoutThenEnd()
	{
		Event snapshot = events.get(events.size() - 2);
		assertEquals(EventType.XP_SNAPSHOT, snapshot.getType());
		assertEquals(XpSnapshot.Reason.LOGOUT, ((XpSnapshot) snapshot.getPayload()).getReason());
		assertEquals(EventType.SESSION_END, last().getType());
		assertEquals(snapshot.getSessionId(), last().getSessionId());
	}

	private static Map<String, XpSnapshot.SkillXp> allSkills(int xp, int level)
	{
		Map<String, XpSnapshot.SkillXp> skills = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			skills.put(skill.name(), new XpSnapshot.SkillXp(xp, level));
		}
		return skills;
	}

	private void login()
	{
		tracker.onGameState(GameState.LOGGING_IN);
		tracker.onGameState(GameState.LOADING);
		tracker.onGameState(GameState.LOGGED_IN);
		int before = events.size();
		tick();
		assertEquals(EventType.SESSION_START, events.get(before).getType());
	}

	/** Game ticks for {@code duration}. */
	private void play(Duration duration)
	{
		Instant until = clock.instant().plus(duration);
		while (!clock.instant().plus(TICK).isAfter(until))
		{
			tick();
		}
	}

	private void tick()
	{
		clock.advance(TICK);
		tracker.onTick();
	}

	private Event last()
	{
		return events.get(events.size() - 1);
	}

	private List<Event> ofType(EventType type)
	{
		return events.stream().filter(e -> e.getType() == type).collect(Collectors.toList());
	}

	private List<EventType> types()
	{
		return events.stream().map(Event::getType).collect(Collectors.toList());
	}

	private static List<EventType> types(EventType... types)
	{
		return Arrays.asList(types);
	}

	private void assertSameSession()
	{
		String sessionId = events.get(0).getSessionId();
		for (Event event : events)
		{
			assertEquals(sessionId, event.getSessionId());
		}
	}

	private static int compareUnsigned(UUID a, UUID b)
	{
		int msb = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
		return msb != 0 ? msb : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
	}

	private final class FakeGame implements GameView
	{
		long hash = ACCOUNT;
		@Nullable
		String name = "Test Main";
		int world = 420;
		/** The game's stats; null while not loaded (like the client before stats arrive). */
		@Nullable
		Map<String, XpSnapshot.SkillXp> skills = allSkills(1000, 10);

		/** A gain, as the client reports it: the game's stats change and StatChanged fires. */
		void gainXp(String skill, int amount)
		{
			XpSnapshot.SkillXp old = skills.get(skill);
			XpSnapshot.SkillXp now = new XpSnapshot.SkillXp(old.getXp() + amount, old.getLevel());
			skills.put(skill, now);
			tracker.onStat(skill, now.getXp(), now.getLevel());
		}
		/** No input since this time; null while the player keeps moving the mouse. */
		@Nullable
		private Instant idleSince;

		void idleFrom(@Nullable Instant since)
		{
			idleSince = since;
		}

		@Override
		public long accountHash()
		{
			return hash;
		}

		@Nullable
		@Override
		public String displayName()
		{
			return name;
		}

		@Override
		public AccountType accountType()
		{
			return AccountType.IRONMAN;
		}

		@Override
		public int world()
		{
			return world;
		}

		@Override
		public int idleTicks()
		{
			if (idleSince == null)
			{
				return 0;
			}
			return (int) (Duration.between(idleSince, clock.instant()).toMillis()
				/ AfkDetector.CLIENT_TICK.toMillis());
		}

		@Override
		public SessionHeartbeat heartbeat(boolean idle)
		{
			return new SessionHeartbeat(12342, world, Collections.singletonList("MEMBERS"), "GERMANY",
				"Trade - Members", idle);
		}

		@Nullable
		@Override
		public Map<String, XpSnapshot.SkillXp> skills()
		{
			return skills == null ? null : new LinkedHashMap<>(skills);
		}
	}

	@Test
	public void aReservedEnvelopeKeepsItsIdAndTime()
	{
		assertEquals(null, tracker.reserveInSession());
		tracker.onGameState(GameState.LOGGED_IN);
		tracker.onTick();
		Event.Reserved reserved = tracker.reserveInSession();
		clock.advance(TICK);
		tracker.emitInSession(Kc.builder().boss("Zulrah").killCount(1).build());
		Event later = events.get(events.size() - 1);
		Event event = reserved.with(Kc.builder().boss("Vorkath").killCount(2).build());
		assertEquals(later.getOccurredAt().minus(TICK), event.getOccurredAt());
		assertEquals(later.getSessionId(), event.getSessionId());
		assertEquals(ACCOUNT, event.getAccountHash());
		assertTrue(compareUnsigned(UUID.fromString(event.getEventId()), UUID.fromString(later.getEventId())) < 0);
	}
}
