package com.osrstracker.session;

import com.osrstracker.event.AccountType;
import com.osrstracker.event.SessionHeartbeat;
import com.osrstracker.event.XpSnapshot;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * What the session tracker reads from the game. Only called on the client
 * thread. {@link ClientGameView} is the real one; tests use a fake.
 */
public interface GameView
{
	/** RuneLite's account hash, {@code -1} until known. */
	long accountHash();

	/** The local player's display name, null until known. Never logged. */
	@Nullable
	String displayName();

	AccountType accountType();

	int world();

	/** {@code min(mouse, keyboard)} idle client ticks (20 ms each). */
	int idleTicks();

	/** A heartbeat payload for the current position and world. */
	SessionHeartbeat heartbeat(boolean idle);

	/**
	 * XP and real level of every skill, keyed by RuneLite's {@code Skill} name
	 * in enum order; null until all stats are loaded after login.
	 */
	@Nullable
	Map<String, XpSnapshot.SkillXp> skills();
}
