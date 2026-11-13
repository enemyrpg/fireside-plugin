package com.osrstracker.session;

import com.osrstracker.event.XpSnapshot;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The XP and real level of every skill, cached so the {@code logout} snapshot
 * can be built after the client has cleared its stats, plus what the last
 * snapshot of the session sent. Not thread-safe; the owner synchronizes.
 */
class XpSnapshots
{
	/** Latest known value per skill, in RuneLite's {@code Skill} order once seeded. */
	private final Map<String, XpSnapshot.SkillXp> cache = new LinkedHashMap<>();
	/** XP per skill in the last snapshot of this session; null until one is sent. */
	@Nullable
	private Map<String, Integer> lastSentXp;

	/**
	 * A skill's value from {@code StatChanged}. XP never goes down in the game,
	 * so a lower value or level 0 is the client clearing its stats (logout) and
	 * is ignored.
	 */
	void onStat(String skill, int xp, int level)
	{
		if (level < 1)
		{
			return;
		}
		XpSnapshot.SkillXp cached = cache.get(skill);
		if (cached != null && xp < cached.getXp())
		{
			return;
		}
		cache.put(skill, new XpSnapshot.SkillXp(xp, level));
	}

	/** Replaces the cache with a full, loaded read of the game. */
	void seed(Map<String, XpSnapshot.SkillXp> skills)
	{
		cache.clear();
		cache.putAll(skills);
	}

	/** A snapshot was sent in this session (the baseline exists). */
	boolean hasBaseline()
	{
		return lastSentXp != null;
	}

	/** Any skill's XP differs from the last snapshot sent. */
	boolean changedSinceLastSnapshot()
	{
		if (lastSentXp == null)
		{
			return true;
		}
		if (lastSentXp.size() != cache.size())
		{
			return true;
		}
		for (Map.Entry<String, XpSnapshot.SkillXp> entry : cache.entrySet())
		{
			Integer sent = lastSentXp.get(entry.getKey());
			if (sent == null || sent != entry.getValue().getXp())
			{
				return true;
			}
		}
		return false;
	}

	/** A snapshot of the cache; remembered as the last one sent. */
	XpSnapshot snapshot(XpSnapshot.Reason reason)
	{
		Map<String, Integer> sent = new HashMap<>();
		cache.forEach((skill, value) -> sent.put(skill, value.getXp()));
		lastSentXp = sent;
		return new XpSnapshot(reason, cache);
	}

	/** The session ended: the next one needs its own baseline. The cache stays (still valid after a hop). */
	void endSession()
	{
		lastSentXp = null;
	}

	/** Logged out: forget everything (the next login may be another account). */
	void reset()
	{
		cache.clear();
		lastSentXp = null;
	}
}
