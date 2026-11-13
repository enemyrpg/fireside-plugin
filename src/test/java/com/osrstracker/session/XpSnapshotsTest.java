package com.osrstracker.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.osrstracker.event.XpSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class XpSnapshotsTest
{
	private final XpSnapshots xp = new XpSnapshots();

	@Test
	public void cacheIgnoresClearedStats()
	{
		xp.seed(skills(1000, 10));
		xp.onStat("ATTACK", 0, 1);
		xp.onStat("ATTACK", 1500, 0);
		assertEquals(new XpSnapshot.SkillXp(1000, 10), xp.snapshot(XpSnapshot.Reason.LOGOUT).getSkills().get("ATTACK"));
	}

	@Test
	public void gainsUpdateTheCache()
	{
		xp.seed(skills(1000, 10));
		xp.onStat("ATTACK", 1200, 11);
		assertEquals(new XpSnapshot.SkillXp(1200, 11), xp.snapshot(XpSnapshot.Reason.INTERVAL).getSkills().get("ATTACK"));
	}

	@Test
	public void changeDetection()
	{
		xp.seed(skills(1000, 10));
		assertTrue("No baseline yet", xp.changedSinceLastSnapshot());
		xp.snapshot(XpSnapshot.Reason.LOGIN);
		assertTrue(xp.hasBaseline());
		assertFalse(xp.changedSinceLastSnapshot());

		xp.onStat("ATTACK", 1000, 10);
		assertFalse("Same value is no change", xp.changedSinceLastSnapshot());
		xp.onStat("DEFENCE", 1001, 10);
		assertTrue(xp.changedSinceLastSnapshot());
		xp.snapshot(XpSnapshot.Reason.INTERVAL);
		assertFalse(xp.changedSinceLastSnapshot());

		xp.endSession();
		assertFalse(xp.hasBaseline());
	}

	@Test
	public void snapshotKeepsSkillOrder()
	{
		xp.seed(skills(1000, 10));
		xp.onStat("DEFENCE", 2000, 15);
		assertEquals("[ATTACK, DEFENCE, SAILING]",
			xp.snapshot(XpSnapshot.Reason.LOGIN).getSkills().keySet().toString());
	}

	private static Map<String, XpSnapshot.SkillXp> skills(int xp, int level)
	{
		Map<String, XpSnapshot.SkillXp> skills = new LinkedHashMap<>();
		for (String skill : new String[]{"ATTACK", "DEFENCE", "SAILING"})
		{
			skills.put(skill, new XpSnapshot.SkillXp(xp, level));
		}
		return skills;
	}
}
