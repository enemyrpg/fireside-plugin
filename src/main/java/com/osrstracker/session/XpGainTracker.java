package com.osrstracker.session;

import java.util.HashMap;
import java.util.Map;

/**
 * Tells real XP gains apart from stats being loaded. The first value seen for
 * a skill is only a baseline (at login the client reports every skill once).
 * Not thread-safe; the owner synchronizes.
 */
public class XpGainTracker
{
	private final Map<String, Integer> xpBySkill = new HashMap<>();

	/** @return true if {@code xp} is higher than the last value seen for the skill */
	public boolean onXp(String skill, int xp)
	{
		Integer previous = xpBySkill.put(skill, xp);
		return previous != null && xp > previous;
	}

	/** Forget all baselines (at logout, when the client clears the stats). */
	public void reset()
	{
		xpBySkill.clear();
	}
}
