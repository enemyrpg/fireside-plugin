package com.fireside.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XpGainTrackerTest
{
	private final XpGainTracker xp = new XpGainTracker();

	@Test
	public void firstValueIsABaseline()
	{
		assertFalse(xp.onXp("ATTACK", 13_034_431));
		assertFalse(xp.onXp("SLAYER", 0));
	}

	@Test
	public void onlyAnIncreaseIsAGain()
	{
		xp.onXp("FISHING", 100);
		assertFalse(xp.onXp("FISHING", 100));
		assertTrue(xp.onXp("FISHING", 150));
		assertFalse(xp.onXp("FISHING", 120));
		assertFalse("Other skills have their own baseline", xp.onXp("COOKING", 500));
	}

	@Test
	public void resetForgetsBaselines()
	{
		xp.onXp("FISHING", 100);
		xp.reset();
		assertFalse(xp.onXp("FISHING", 200));
	}
}
