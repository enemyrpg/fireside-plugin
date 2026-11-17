package com.fireside.icons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fireside.net.IconKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.SpriteID;
import org.junit.Test;

/** The catalog read from made-up game data. */
public class IconCatalogReaderTest
{
	private static IconCatalog readAll(FakeIconGameData data)
	{
		IconCatalog catalog = new IconCatalogReader(data, System::nanoTime).step(Long.MAX_VALUE);
		assertNotNull(catalog);
		return catalog;
	}

	private static FakeIconGameData game()
	{
		FakeIconGameData data = new FakeIconGameData();
		data.tab(new int[]{100, 101, 102}, new int[]{200, 101});
		data.tab(new int[]{300}, new int[]{}, new int[]{301, 100});
		data.replace(101, 9101);
		data.replace(777, 9777);
		data.skill("Attack", 197);
		data.skill("Runecraft", 198);
		data.boss("Zulrah", 4001);
		data.boss("Kree'Arra", 4002);
		data.boss("Chambers of Xeric: Challenge Mode", 4003);
		return data;
	}

	@Test
	public void itemsComeFromTabsPagesAndItemEnumsInTheLogsOrderWithoutDuplicates()
	{
		assertEquals(Arrays.asList(100, 9101, 102, 200, 300, 301), readAll(game()).getItemIds());
	}

	@Test
	public void aReplacedItemIsListedAsItsReplacementOnly()
	{
		List<Integer> ids = readAll(game()).getItemIds();
		assertTrue(ids.contains(9101));
		assertFalse(ids.contains(101));
		// A replacement for an item that is not in the log adds nothing.
		assertFalse(ids.contains(9777));
	}

	@Test
	public void skillsAndBossesComeFromTheHiscoreTablesWithTheirSprites()
	{
		IconCatalog catalog = readAll(game());
		assertEquals(Integer.valueOf(197), catalog.getSprites().get(IconKey.skill("ATTACK")));
		assertEquals(Integer.valueOf(198), catalog.getSprites().get(IconKey.skill("RUNECRAFT")));
		assertEquals(Integer.valueOf(4001), catalog.getSprites().get(IconKey.boss("Zulrah")));
		assertEquals(Integer.valueOf(4002), catalog.getSprites().get(IconKey.boss("Kree'Arra")));
		assertEquals(Integer.valueOf(4003),
			catalog.getSprites().get(IconKey.boss("Chambers of Xeric: Challenge Mode")));
		assertEquals(3, catalog.count(IconKey.Kind.SKILL));
		assertEquals(3, catalog.count(IconKey.Kind.BOSS));
		assertEquals(6, catalog.count(IconKey.Kind.ITEM));
		assertEquals(12, catalog.keys().size());
	}

	@Test
	public void overallIsNotInTheSkillTableAndUsesTheTotalLevelSprite()
	{
		IconCatalog catalog = readAll(game());
		assertEquals(Integer.valueOf(SpriteID.Staticons2.TOTAL), catalog.getSprites().get(IconKey.skill("OVERALL")));

		// Also with an empty skill table, and a table row called Overall never replaces it.
		FakeIconGameData data = new FakeIconGameData();
		assertEquals(Collections.singletonMap(IconKey.skill("OVERALL"), SpriteID.Staticons2.TOTAL),
			readAll(data).getSprites());
		data.skill("Overall", 5);
		assertEquals(Integer.valueOf(SpriteID.Staticons2.TOTAL),
			readAll(data).getSprites().get(IconKey.skill("OVERALL")));
	}

	@Test
	public void namesTheServerWouldRejectAndRowsWithoutASpriteAreLeftOut()
	{
		FakeIconGameData data = new FakeIconGameData();
		data.skill("Sailing", 10);
		data.skill("", 11);
		data.skill("9lives", 12);
		data.skill("Åttack", 13);
		data.skill(null, 14);
		data.skill("Slayer", null);
		data.skill("Hunter", -1);
		data.skill("Two words", 15);
		data.boss("Vorkath", 20);
		data.boss("", 21);
		data.boss(" Padded ", 22);
		data.boss(String.join("", Collections.nCopies(101, "x")), 23);
		data.boss(null, 24);
		data.boss("No sprite", null);
		data.boss("Vorkath", 25);

		List<IconKey> keys = new ArrayList<>(readAll(data).getSprites().keySet());
		assertEquals(Arrays.asList(IconKey.skill("OVERALL"), IconKey.skill("SAILING"), IconKey.skill("TWO_WORDS"),
			IconKey.boss("Vorkath"), IconKey.boss("Padded")), keys);
		// The first row of a name wins.
		assertEquals(Integer.valueOf(20), readAll(data).getSprites().get(IconKey.boss("Vorkath")));
	}

	@Test
	public void missingGameDataGivesAnEmptyLogAndOnlyOverall()
	{
		IconCatalog catalog = readAll(new FakeIconGameData());
		assertTrue(catalog.getItemIds().isEmpty());
		assertEquals(1, catalog.getSprites().size());
	}

	@Test
	public void theTimeBudgetSpreadsTheReadOverTicksWithTheSameResult()
	{
		FakeIconGameData data = game();
		// Every look at the clock is one millisecond later: two steps fit in a 2 ms budget.
		long[] now = {0};
		IconCatalogReader reader = new IconCatalogReader(data, () -> now[0] += 1_000_000);
		IconCatalog catalog = null;
		int readsBefore = 0;
		int ticks = 0;
		while (catalog == null)
		{
			catalog = reader.step(2_000_000);
			ticks++;
			assertTrue("a tick did too much: " + (data.reads - readsBefore), data.reads - readsBefore <= 6);
			assertTrue(reader.lastTickNanos() <= 2_000_000);
			readsBefore = data.reads;
			assertTrue("never finished", ticks < 100);
		}
		assertTrue("read in one tick", ticks > 3);
		assertEquals(ticks, reader.ticks());
		assertEquals(readAll(game()), catalog);
	}

	@Test
	public void aStepAlwaysMakesProgressEvenWithNoBudget()
	{
		IconCatalogReader reader = new IconCatalogReader(game(), System::nanoTime);
		IconCatalog catalog = null;
		for (int i = 0; i < 200 && catalog == null; i++)
		{
			catalog = reader.step(0);
		}
		assertEquals(readAll(game()), catalog);
		assertNull(new IconCatalogReader(game(), System::nanoTime).step(0));
	}
}
