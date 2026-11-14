package com.fireside.raid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.Test;

public class RaidsTest
{
	/**
	 * The purple item IDs per raid in {@code osrs-tracker/shared/src/game/raids.ts}.
	 * The server pairs SELF purples with chest items by these IDs, so the plugin
	 * must use the same ones. Update both together.
	 */
	private static final Map<Raids.Group, List<Integer>> RAIDS_TS = new TreeMap<>();

	static
	{
		RAIDS_TS.put(Raids.Group.CHAMBERS_OF_XERIC, Arrays.asList(21034, 21079, 21000, 21012, 21015, 21018, 21021,
			21024, 13652, 21003, 21043, 20997));
		RAIDS_TS.put(Raids.Group.THEATRE_OF_BLOOD, Arrays.asList(22477, 22324, 22481, 22326, 22327, 22328, 22486));
		RAIDS_TS.put(Raids.Group.TOMBS_OF_AMASCUT, Arrays.asList(26219, 25975, 25985, 27226, 27229, 27232, 27277));
		RAIDS_TS.put(Raids.Group.FRACTURED_ARCHIVE, Arrays.asList(34717, 34719, 34727, 34679, 34681));
	}

	@Test
	public void purpleIdsMatchRaidsTs()
	{
		Map<Raids.Group, List<Integer>> plugin = new TreeMap<>();
		for (Raids.Group group : Raids.Group.values())
		{
			plugin.put(group, Raids.purples().stream()
				.filter(p -> p.getGroup() == group)
				.map(Raids.Purple::getItemId)
				.collect(Collectors.toList()));
		}
		assertEquals(RAIDS_TS, plugin);
	}

	@Test
	public void groupOfKillCountName()
	{
		assertEquals(Raids.Group.CHAMBERS_OF_XERIC, Raids.groupOfKc("Chambers of Xeric"));
		assertEquals(Raids.Group.CHAMBERS_OF_XERIC, Raids.groupOfKc("Chambers of Xeric Challenge Mode"));
		assertEquals(Raids.Group.THEATRE_OF_BLOOD, Raids.groupOfKc("Theatre of Blood: Entry Mode"));
		assertEquals(Raids.Group.THEATRE_OF_BLOOD, Raids.groupOfKc("Theatre of Blood: Hard Mode"));
		assertEquals(Raids.Group.TOMBS_OF_AMASCUT, Raids.groupOfKc("Tombs of Amascut: Expert Mode"));
		assertEquals(Raids.Group.FRACTURED_ARCHIVE, Raids.groupOfKc("Fractured Archive"));
		assertEquals(Raids.Group.FRACTURED_ARCHIVE, Raids.groupOfKc("The Fractured Archive"));
		assertNull(Raids.groupOfKc("Zulrah"));
		assertNull(Raids.groupOfKc("Theatre"));
	}

	@Test
	public void groupOfChestName()
	{
		assertEquals(Raids.Group.CHAMBERS_OF_XERIC, Raids.groupOfChest("Chambers of Xeric"));
		assertEquals(Raids.Group.THEATRE_OF_BLOOD, Raids.groupOfChest("Theatre of Blood"));
		assertEquals(Raids.Group.TOMBS_OF_AMASCUT, Raids.groupOfChest("Tombs of Amascut"));
		assertEquals(Raids.Group.FRACTURED_ARCHIVE, Raids.groupOfChest("Fractured Archive"));
		assertNull(Raids.groupOfChest("Barrows"));
		assertNull(Raids.groupOfChest("Chambers of Xeric Challenge Mode"));
	}

	@Test
	public void purpleByChatName()
	{
		assertEquals(20997, Raids.purple("Twisted bow").getItemId());
		assertEquals(20997, Raids.purple("twisted BOW").getItemId());
		assertEquals(22486, Raids.purple("Scythe of vitur").getItemId());
		assertEquals(22486, Raids.purple("Scythe of vitur (uncharged)").getItemId());
		assertEquals(22481, Raids.purple("Sanguinesti staff").getItemId());
		assertEquals(27277, Raids.purple("Tumeken's shadow").getItemId());
		assertEquals(34679, Raids.purple("Horn of Bran").getItemId());
		assertEquals(Raids.Group.FRACTURED_ARCHIVE, Raids.purple("Obligator").getGroup());
		assertNull(Raids.purple("Lil' Zik"));
		assertNull(Raids.purple("Tumeken's guardian"));
		assertNull(Raids.purple("Olmlet"));
	}
}
