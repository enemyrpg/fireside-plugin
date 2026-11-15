package com.fireside.session;

import com.fireside.event.Event;
import com.fireside.event.EventPayload;
import com.fireside.event.SessionAfk;
import com.fireside.event.SessionEnd;
import com.fireside.event.SessionStart;
import com.fireside.event.UuidV7Generator;
import com.fireside.event.XpSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;

/**
 * Session state machine: {@code session.start}, {@code session.heartbeat},
 * {@code session.afk}, {@code session.end} and the session's
 * {@code xp.snapshot}s ({@code login}, {@code interval}, {@code logout}).
 * <p>
 * Every event is created (and gets its ID) when it happens, in game order,
 * so {@code session.end} always sorts before the next {@code session.start}.
 * <p>
 * Called on the client thread, except {@link #onClientShutdown()}, which
 * only uses cached values. All methods are synchronized for that reason.
 */
@Slf4j
public class SessionTracker
{
	public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(60);
	/** How often an {@code interval} snapshot is considered; only sent if XP changed. */
	public static final Duration XP_SNAPSHOT_INTERVAL = Duration.ofMinutes(5);

	enum State
	{
		/** No session, waiting for {@code LOGGED_IN}. */
		LOGGED_OUT,
		/** Logged in, waiting for the account hash and name. */
		PENDING_START,
		ACTIVE,
	}

	private final GameView game;
	private final UuidV7Generator ids;
	private final Clock clock;
	private final Consumer<Event> sink;
	private final Supplier<Duration> afkThreshold;
	/** The "XP" toggle. */
	private final BooleanSupplier xpEnabled;
	private final String pluginVersion;
	private final XpGainTracker xp = new XpGainTracker();
	private final XpSnapshots xpSnapshots = new XpSnapshots();

	/** Collect sessions at all (the "Send data" toggle). */
	private boolean enabled;
	/** The game is logged in (also while loading or reconnecting). */
	private boolean inGame;
	private State state = State.LOGGED_OUT;

	// Set while ACTIVE.
	private long accountHash = Event.NO_ACCOUNT;
	@Nullable
	private String sessionId;
	@Nullable
	private Instant nextHeartbeat;
	@Nullable
	private AfkDetector afk;
	/** When the connection was lost, until it recovers. A logout after it ends the session then. */
	@Nullable
	private Instant connectionLostAt;
	/** The session's first snapshot is the {@code login} one (false once sent, or once XP was off). */
	private boolean loginSnapshotPending;
	/** Next {@code interval} check; set once the session's first snapshot is sent. */
	@Nullable
	private Instant nextXpCheck;

	public SessionTracker(GameView game, UuidV7Generator ids, Clock clock, Consumer<Event> sink,
		Supplier<Duration> afkThreshold, BooleanSupplier xpEnabled, String pluginVersion)
	{
		this.game = game;
		this.ids = ids;
		this.clock = clock;
		this.sink = sink;
		this.afkThreshold = afkThreshold;
		this.xpEnabled = xpEnabled;
		this.pluginVersion = pluginVersion;
	}

	/** The "Send data" toggle. Off behaves like {@link #stop()}; on starts a session if logged in. */
	public synchronized void setEnabled(boolean enabled)
	{
		if (this.enabled == enabled)
		{
			return;
		}
		if (!enabled)
		{
			stop();
		}
		this.enabled = enabled;
		if (enabled && inGame && state == State.LOGGED_OUT)
		{
			state = State.PENDING_START;
		}
	}

	/**
	 * @return true if a session ended because the player logged out (the
	 *     caller then asks the sender to send)
	 */
	public synchronized boolean onGameState(GameState gameState)
	{
		switch (gameState)
		{
			case LOGGED_IN:
				inGame = true;
				connectionLostAt = null;
				if (enabled && state == State.LOGGED_OUT)
				{
					state = State.PENDING_START;
				}
				return false;
			case CONNECTION_LOST:
				if (connectionLostAt == null)
				{
					connectionLostAt = clock.instant();
				}
				return false;
			case HOPPING:
				inGame = false;
				connectionLostAt = null;
				// The next LOGGED_IN starts a new session on the new world.
				end(SessionEnd.Reason.HOP, clock.instant());
				return false;
			case LOGIN_SCREEN:
			case LOGIN_SCREEN_AUTHENTICATOR:
			{
				Instant at = connectionLostAt != null ? connectionLostAt : clock.instant();
				inGame = false;
				connectionLostAt = null;
				// The logout snapshot is built from the cache first; the stats may be cleared by now.
				boolean ended = end(SessionEnd.Reason.LOGOUT, at);
				xp.reset();
				xpSnapshots.reset();
				return ended;
			}
			default:
				return false;
		}
	}

	/** Every game tick. */
	public synchronized void onTick()
	{
		if (!enabled)
		{
			return;
		}
		Instant now = clock.instant();
		if (state == State.PENDING_START)
		{
			tryStart(now);
			return;
		}
		if (state != State.ACTIVE)
		{
			return;
		}
		SessionAfk period = afk.onTick(now, game.idleTicks(), afkThreshold.get());
		if (period != null)
		{
			emit(now, period);
		}
		if (!now.isBefore(nextHeartbeat))
		{
			emit(now, game.heartbeat(afk.isAfk()));
			nextHeartbeat = nextHeartbeat.plus(HEARTBEAT_INTERVAL);
			if (!nextHeartbeat.isAfter(now))
			{
				// Behind by more than an interval (e.g. after a connection loss): no catch-up burst.
				nextHeartbeat = now.plus(HEARTBEAT_INTERVAL);
			}
		}
		xpTick(now);
	}

	/**
	 * A skill's XP and real level from {@code StatChanged}. Gains count as
	 * activity for AFK (also with the XP toggle off) and update the cache the
	 * {@code logout} snapshot is built from.
	 */
	public synchronized void onStat(String skill, int xpValue, int level)
	{
		xpSnapshots.onStat(skill, xpValue, level);
		if (xp.onXp(skill, xpValue) && state == State.ACTIVE)
		{
			afk.onXpGain(clock.instant());
		}
	}

	/** {@code ClientShutdown}: ends the session from cached values. */
	public synchronized void onClientShutdown()
	{
		end(SessionEnd.Reason.CLIENT_CLOSED, clock.instant());
		inGame = false;
	}

	/**
	 * The plugin stops (or sending is turned off) while the game goes on.
	 * Sends an open AFK period, but no {@code session.end}: no reason fits,
	 * and the server closes the session at its last heartbeat (timeout).
	 */
	public synchronized void stop()
	{
		if (state == State.ACTIVE)
		{
			closeAfk(clock.instant());
		}
		clearSession();
	}

	/**
	 * A game event (loot, kill count) in the current session, created now.
	 *
	 * @return false if there is no active session (not logged in yet, or
	 *     "Send data" is off); the event is then dropped
	 */
	public synchronized boolean emitInSession(EventPayload payload)
	{
		Event event = createInSession(payload);
		if (event == null)
		{
			return false;
		}
		sink.accept(event);
		return true;
	}

	/**
	 * An event in the current session, created now (its ID in game order),
	 * but not queued: for snapshots that are held and queued later.
	 *
	 * @return null if there is no active session
	 */
	@Nullable
	public synchronized Event createInSession(EventPayload payload)
	{
		if (!enabled || state != State.ACTIVE)
		{
			return null;
		}
		return Event.create(ids, accountHash, clock.instant(), sessionId, payload);
	}

	synchronized State state()
	{
		return state;
	}

	private void tryStart(Instant now)
	{
		long hash = game.accountHash();
		String name = game.displayName();
		if (hash == Event.NO_ACCOUNT || name == null || name.isEmpty())
		{
			return;
		}
		accountHash = hash;
		sessionId = ids.next();
		emit(now, new SessionStart(name, game.accountType(), game.world(), pluginVersion));
		afk = new AfkDetector(now);
		nextHeartbeat = now.plus(HEARTBEAT_INTERVAL);
		state = State.ACTIVE;
		loginSnapshotPending = true;
		log.debug("Session started");
		// Right after session.start if the stats are already loaded.
		xpTick(now);
	}

	/** XP snapshots while ACTIVE: the session's first one as soon as the stats are loaded, then interval checks. */
	private void xpTick(Instant now)
	{
		if (!xpEnabled.getAsBoolean())
		{
			// Turned on later, the first snapshot is an interval baseline, not a login.
			loginSnapshotPending = false;
			xpSnapshots.endSession();
			nextXpCheck = null;
			return;
		}
		if (!xpSnapshots.hasBaseline())
		{
			Map<String, XpSnapshot.SkillXp> skills = game.skills();
			if (skills == null)
			{
				return;
			}
			xpSnapshots.seed(skills);
			emit(now, xpSnapshots.snapshot(loginSnapshotPending ? XpSnapshot.Reason.LOGIN : XpSnapshot.Reason.INTERVAL));
			loginSnapshotPending = false;
			nextXpCheck = now.plus(XP_SNAPSHOT_INTERVAL);
			return;
		}
		if (now.isBefore(nextXpCheck))
		{
			return;
		}
		if (xpSnapshots.changedSinceLastSnapshot())
		{
			emit(now, xpSnapshots.snapshot(XpSnapshot.Reason.INTERVAL));
		}
		nextXpCheck = nextXpCheck.plus(XP_SNAPSHOT_INTERVAL);
		if (!nextXpCheck.isAfter(now))
		{
			nextXpCheck = now.plus(XP_SNAPSHOT_INTERVAL);
		}
	}

	/** @return true if an active session was ended */
	private boolean end(SessionEnd.Reason reason, Instant at)
	{
		if (state != State.ACTIVE)
		{
			clearSession();
			return false;
		}
		closeAfk(at);
		if (xpEnabled.getAsBoolean() && xpSnapshots.hasBaseline())
		{
			// From the cache only: safe at ClientShutdown and after the client cleared its stats.
			emit(at, xpSnapshots.snapshot(XpSnapshot.Reason.LOGOUT));
		}
		emit(at, new SessionEnd(reason));
		log.debug("Session ended ({})", reason);
		clearSession();
		return true;
	}

	private void closeAfk(Instant at)
	{
		SessionAfk period = afk.close(at, afkThreshold.get());
		if (period != null)
		{
			emit(at, period);
		}
	}

	private void clearSession()
	{
		state = State.LOGGED_OUT;
		accountHash = Event.NO_ACCOUNT;
		sessionId = null;
		nextHeartbeat = null;
		afk = null;
		loginSnapshotPending = false;
		nextXpCheck = null;
		xpSnapshots.endSession();
	}

	private void emit(Instant at, EventPayload payload)
	{
		sink.accept(Event.create(ids, accountHash, at, sessionId, payload));
	}
}
