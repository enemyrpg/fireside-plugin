package com.osrstracker.loot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrstracker.event.EventPayload;
import com.osrstracker.event.Kc;
import com.osrstracker.event.Loot;
import com.osrstracker.event.LootItem;
import com.osrstracker.event.LootSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.ChatMessageType;
import org.junit.Test;

public class KillTrackerTest
{
	private static final String ZULRAH_KC = "Your Zulrah kill count is: <col=ff0000>4</col>.";
	private static final String ZULRAH_TIME = "Fight duration: <col=ff0000>0:59</col>. Personal best: 0:55";
	private static final String GG_PB = "Fight duration: <col=ff0000>1:36</col> (new personal best)";
	private static final String GG_KC = "Your Grotesque Guardians kill count is: <col=ff0000>179</col>.";
	private static final String BARROWS_KC = "Your Barrows chest count is: <col=ff0000>277</col>.";
	private static final List<LootItem> ITEMS = Collections.singletonList(
		new LootItem(12934, "Zulrah's scales", 120, 160, 0));

	private final List<EventPayload> events = new ArrayList<>();
	private boolean lootEnabled = true;
	private boolean kcEnabled = true;
	private final KillTracker tracker = new KillTracker(events::add, () -> lootEnabled, () -> kcEnabled);

	private void game(String message, int tick)
	{
		tracker.onChatMessage(ChatMessageType.GAMEMESSAGE, message, tick);
	}

	private Kc onlyKc()
	{
		assertEquals(events.toString(), 1, events.size());
		return (Kc) events.get(0);
	}

	@Test
	public void killCountThenDurationInTheSameTick()
	{
		game(ZULRAH_KC, 100);
		game(ZULRAH_TIME, 100);
		assertTrue("Waits for the end of the tick", events.isEmpty());
		tracker.onTick(100);
		assertTrue("Same tick: still waiting", events.isEmpty());
		tracker.onTick(101);
		assertEquals(Kc.builder().boss("Zulrah").killCount(4).timeSeconds(59.0).personalBest(false).build(),
			onlyKc());
	}

	@Test
	public void durationThenKillCount()
	{
		game(GG_PB, 100);
		game(GG_KC, 100);
		tracker.onTick(101);
		assertEquals(Kc.builder().boss("Grotesque Guardians").killCount(179).timeSeconds(96.0)
			.personalBest(true).build(), onlyKc());
	}

	@Test
	public void durationFromThePreviousTickStillCounts()
	{
		game(GG_PB, 99);
		tracker.onTick(100);
		game(GG_KC, 100);
		tracker.onTick(101);
		assertEquals(Double.valueOf(96.0), onlyKc().getTimeSeconds());
	}

	@Test
	public void oldDurationIsNotUsed()
	{
		game(GG_PB, 90);
		tracker.onTick(91);
		tracker.onTick(92);
		game(GG_KC, 100);
		tracker.onTick(101);
		Kc kc = onlyKc();
		assertNull(kc.getTimeSeconds());
		assertNull(kc.getPersonalBest());
	}

	@Test
	public void durationInALaterTickIsNotUsed()
	{
		game(ZULRAH_KC, 100);
		tracker.onTick(101);
		game(ZULRAH_TIME, 101);
		tracker.onTick(102);
		assertNull(onlyKc().getTimeSeconds());
	}

	@Test
	public void killCountWithoutDuration()
	{
		game(BARROWS_KC, 100);
		tracker.onTick(101);
		assertEquals(Kc.builder().boss("Barrows chest").killCount(277).build(), onlyKc());
	}

	@Test
	public void twoCountsInOneTickAreBothSent()
	{
		game(ZULRAH_KC, 100);
		game(BARROWS_KC, 100);
		tracker.onTick(101);
		assertEquals(2, events.size());
		assertEquals("Zulrah", ((Kc) events.get(0)).getBoss());
		assertEquals("Barrows chest", ((Kc) events.get(1)).getBoss());
	}

	@Test
	public void playerChatIsIgnored()
	{
		tracker.onChatMessage(ChatMessageType.PUBLICCHAT, ZULRAH_KC, 100);
		tracker.onChatMessage(ChatMessageType.PRIVATECHAT, ZULRAH_KC, 100);
		tracker.onTick(101);
		assertTrue(events.isEmpty());
	}

	@Test
	public void killCountToggleOff()
	{
		kcEnabled = false;
		game(ZULRAH_KC, 100);
		tracker.onTick(101);
		tracker.onLoot(LootSource.npc("Zulrah", 2042, 725), ITEMS, 1, 103);
		assertEquals(1, events.size());
		assertNull("No KC on loot when KC is off", ((Loot) events.get(0)).getKillCount());
	}

	@Test
	public void lootToggleOff()
	{
		lootEnabled = false;
		tracker.onLoot(LootSource.npc("Zulrah", 2042, 725), ITEMS, 1, 100);
		assertTrue(events.isEmpty());
	}

	@Test
	public void lootGetsTheKillCountThatCameJustBefore()
	{
		game(ZULRAH_KC, 100);
		tracker.onTick(101);
		tracker.onLoot(LootSource.npc("Zulrah", 2042, 725), ITEMS, 1, 104);
		assertEquals(2, events.size());
		assertEquals(new Loot(LootSource.npc("Zulrah", 2042, 725), ITEMS, 4), events.get(1));
	}

	@Test
	public void lootInTheSameTickSendsThePendingKillCountFirst()
	{
		game(BARROWS_KC, 100);
		tracker.onLoot(LootSource.event("Barrows"), ITEMS, 1, 100);
		assertEquals(2, events.size());
		assertEquals(Kc.builder().boss("Barrows chest").killCount(277).build(), events.get(0));
		assertEquals("Barrows chest count matches Barrows loot", Integer.valueOf(277),
			((Loot) events.get(1)).getKillCount());
		tracker.onTick(101);
		assertEquals("Not sent twice", 2, events.size());
	}

	@Test
	public void nameMatchIgnoresCase()
	{
		game("Your herbiboar harvest count is: <col=ff0000>4091</col>.", 100);
		tracker.onLoot(LootSource.event("Herbiboar"), ITEMS, 1, 101);
		assertEquals(Integer.valueOf(4091), ((Loot) events.get(1)).getKillCount());
	}

	@Test
	public void otherSourceGetsNoKillCount()
	{
		game(ZULRAH_KC, 100);
		tracker.onLoot(LootSource.npc("Snakeling", null, 90), ITEMS, 1, 101);
		assertNull(((Loot) events.get(1)).getKillCount());
	}

	@Test
	public void killCountIsUsedOnce()
	{
		game(ZULRAH_KC, 100);
		tracker.onLoot(LootSource.npc("Zulrah", 2042, 725), ITEMS, 1, 101);
		tracker.onLoot(LootSource.npc("Zulrah", 2042, 725), ITEMS, 1, 102);
		assertEquals(Integer.valueOf(4), ((Loot) events.get(1)).getKillCount());
		assertNull(((Loot) events.get(2)).getKillCount());
	}

	@Test
	public void killCountExpires()
	{
		game(ZULRAH_KC, 100);
		tracker.onLoot(LootSource.npc("Zulrah", 2042, 725), ITEMS, 1, 100 + KillTracker.LOOT_WINDOW_TICKS + 1);
		assertNull(((Loot) events.get(1)).getKillCount());
	}

	@Test
	public void killCountAtTheEndOfTheWindowCounts()
	{
		game(ZULRAH_KC, 100);
		tracker.onLoot(LootSource.npc("Zulrah", 2042, 725), ITEMS, 1, 100 + KillTracker.LOOT_WINDOW_TICKS);
		assertEquals(Integer.valueOf(4), ((Loot) events.get(1)).getKillCount());
	}

	@Test
	public void combinedLootGetsNoKillCount()
	{
		game(ZULRAH_KC, 100);
		tracker.onLoot(LootSource.npc("Zulrah", 2042, 725), ITEMS, 3, 101);
		assertNull(((Loot) events.get(1)).getKillCount());
	}

	@Test
	public void playerLootNeverGetsAKillCount()
	{
		game(ZULRAH_KC, 100);
		tracker.onLoot(LootSource.player(), ITEMS, 1, 101);
		Loot loot = (Loot) events.get(1);
		assertNull(loot.getKillCount());
		assertNull(loot.getSource().getName());
	}

	@Test
	public void flushSendsThePendingKillCount()
	{
		game(ZULRAH_KC, 100);
		tracker.flush();
		assertEquals("Zulrah", onlyKc().getBoss());
	}

	@Test
	public void resetForgetsEverything()
	{
		game(ZULRAH_KC, 100);
		tracker.reset();
		tracker.onTick(101);
		assertTrue(events.isEmpty());
		tracker.onLoot(LootSource.npc("Zulrah", 2042, 725), ITEMS, 1, 101);
		assertNull(((Loot) events.get(0)).getKillCount());
	}
}
