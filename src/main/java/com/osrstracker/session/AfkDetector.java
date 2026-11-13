package com.osrstracker.session;

import com.osrstracker.event.SessionAfk;
import java.time.Duration;
import java.time.Instant;
import javax.annotation.Nullable;

/**
 * AFK rule from {@code 03-datamodell-og-api.md}: AFK when there has been no
 * mouse or keyboard input and no XP gain for at least the threshold. The
 * period starts at the last activity, not when the threshold is reached.
 * Statistics only: nothing in the game reacts to it.
 * <p>
 * One detector per session. Not thread-safe; the owner synchronizes.
 */
public class AfkDetector
{
	/** RuneLite's idle ticks count client cycles of 20 ms. */
	static final Duration CLIENT_TICK = Duration.ofMillis(20);

	private final Instant sessionStart;
	/** Idle ticks at the previous game tick, or -1 before the first one. */
	private int lastIdleTicks = -1;
	@Nullable
	private Instant lastTickAt;
	private Instant lastInputAt;
	@Nullable
	private Instant lastXpAt;
	/** Start of the open AFK period, or null while active. */
	@Nullable
	private Instant afkStartedAt;

	public AfkDetector(Instant sessionStart)
	{
		this.sessionStart = sessionStart;
		this.lastInputAt = sessionStart;
	}

	/**
	 * Called every game tick with {@code min(mouse, keyboard)} idle ticks.
	 * Input is noticed when the idle count drops, or when the idle time is
	 * shorter than the time since the previous game tick (input kept it at or
	 * near zero). A counter that runs slower than the wall clock (e.g. lag)
	 * therefore never looks like input.
	 *
	 * @return the AFK period that just ended, or null
	 */
	@Nullable
	public SessionAfk onTick(Instant now, int idleTicks, Duration threshold)
	{
		Duration idle = CLIENT_TICK.multipliedBy(Math.max(0, idleTicks));
		boolean input = lastTickAt == null || idleTicks < lastIdleTicks
			|| idle.compareTo(Duration.between(lastTickAt, now)) < 0;
		lastTickAt = now;
		if (input)
		{
			Instant inputAt = now.minus(idle);
			// Idle time from before the session (login screen) never counts.
			if (inputAt.isAfter(lastInputAt))
			{
				lastInputAt = inputAt;
			}
		}
		lastIdleTicks = Math.max(0, idleTicks);

		Instant activity = lastActivity();
		if (afkStartedAt != null)
		{
			if (activity.isAfter(afkStartedAt))
			{
				SessionAfk period = new SessionAfk(afkStartedAt, activity);
				afkStartedAt = null;
				return period;
			}
			return null;
		}
		if (!Duration.between(activity, now).minus(threshold).isNegative())
		{
			afkStartedAt = activity;
		}
		return null;
	}

	/** An XP gain counts as activity (AFK skilling is active time). */
	public void onXpGain(Instant at)
	{
		if (lastXpAt == null || at.isAfter(lastXpAt))
		{
			lastXpAt = at;
		}
	}

	public boolean isAfk()
	{
		return afkStartedAt != null;
	}

	/**
	 * Closes the session's AFK tracking at {@code now} (before
	 * {@code session.end}, or when the plugin stops). Also returns a period
	 * when the threshold was crossed after the last game tick.
	 *
	 * @return the open AFK period ending at {@code now}, or null
	 */
	@Nullable
	public SessionAfk close(Instant now, Duration threshold)
	{
		Instant start = afkStartedAt;
		afkStartedAt = null;
		if (start == null)
		{
			Instant activity = lastActivity();
			if (Duration.between(activity, now).minus(threshold).isNegative())
			{
				return null;
			}
			start = activity;
		}
		return new SessionAfk(start, now.isBefore(start) ? start : now);
	}

	private Instant lastActivity()
	{
		Instant activity = lastInputAt;
		if (lastXpAt != null && lastXpAt.isAfter(activity))
		{
			activity = lastXpAt;
		}
		return activity.isBefore(sessionStart) ? sessionStart : activity;
	}
}
