package com.fireside.raid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import com.fireside.contract.ContractPackage;
import com.fireside.event.Event;
import com.fireside.event.EventJson;
import com.fireside.event.EventPayload;
import com.fireside.event.Kc;
import com.fireside.event.Loot;
import com.fireside.event.LootItem;
import com.fireside.event.LootSource;
import com.fireside.event.RaidSpecialLoot;
import com.fireside.event.RaidSpecialLoot.Recipient;
import com.fireside.event.UuidV7Generator;
import com.fireside.loot.ItemLookup;
import com.fireside.loot.KillTracker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.ChatMessageType;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.After;
import org.junit.Test;

/**
 * Full raid message sequences through {@link KillTracker} and {@link RaidTracker}.
 * Strings come from RuneLite's ChatCommandsPluginTest and Raid Data Tracker's tests;
 * see docs/plan/08-design.md part 8 for what is still unconfirmed in game.
 */
public class RaidTrackerTest
{
	private static final String ME = "Canvasba";
	private static final String TEAMMATE = "K1NG DK";
	private static final List<LootItem> CHEST = Collections.singletonList(
		new LootItem(560, "Death rune", 500, 200, 108));

	private final List<EventPayload> events = new ArrayList<>();
	private boolean lootEnabled = true;
	private boolean kcEnabled = true;
	private boolean purplesEnabled = true;
	private final FakeRaidView view = new FakeRaidView();
	private final RaidTracker raids = new RaidTracker(events::add, view, new FakeItems(), () -> purplesEnabled,
		() -> kcEnabled);
	private final KillTracker tracker = new KillTracker(events::add, () -> lootEnabled, () -> kcEnabled, raids);

	/** Every test: whatever was sent validates against the pinned schema and contains no player name. */
	@After
	public void eventsAreValidAndNameless()
	{
		EventJson json = new EventJson(RuneLiteAPI.GSON);
		UuidV7Generator ids = new UuidV7Generator();
		String session = ids.next();
		JsonSchema envelope = ContractPackage.schema("schema/event.json");
		for (EventPayload payload : events)
		{
			String line = json.toJson(new Event(ids.next(), 1L, Instant.parse("2026-11-13T19:00:00Z"), session, payload));
			JsonSchema typeSchema = ContractPackage.schema("schema/events/" + payload.type().wireName() + ".json");
			for (JsonSchema schema : new JsonSchema[]{envelope, typeSchema})
			{
				Set<ValidationMessage> errors = ContractPackage.validate(schema, line);
				assertTrue(errors + "\n" + line, errors.isEmpty());
			}
			String lower = line.toLowerCase(Locale.ROOT);
			for (String name : new String[]{ME, TEAMMATE, "K1NG DK", "Bob Teammate"})
			{
				assertFalse("No player name in " + line, lower.contains(name.toLowerCase(Locale.ROOT)));
			}
		}
	}

	private void game(String message, int tick)
	{
		tracker.onChatMessage(ChatMessageType.GAMEMESSAGE, message, tick);
	}

	private void notification(String message, int tick)
	{
		tracker.onChatMessage(ChatMessageType.FRIENDSCHATNOTIFICATION, message, tick);
	}

	private void chest(String lootTrackerName, int tick)
	{
		tracker.onLoot(LootSource.event(lootTrackerName), CHEST, 1, tick);
	}

	private static String kc(String boss, int count)
	{
		return "Your completed " + boss + " count is: <col=ff0000>" + count + "</col>.";
	}

	private <T> List<T> of(Class<T> type)
	{
		return events.stream().filter(type::isInstance).map(type::cast).collect(Collectors.toList());
	}

	private Kc onlyKc()
	{
		List<Kc> kcs = of(Kc.class);
		assertEquals(events.toString(), 1, kcs.size());
		return kcs.get(0);
	}

	private Loot onlyLoot()
	{
		List<Loot> loot = of(Loot.class);
		assertEquals(events.toString(), 1, loot.size());
		return loot.get(0);
	}

	// Chambers of Xeric

	@Test
	public void coxThreePlayersWithPurplesAndChest()
	{
		view.cox = 3;
		notification(RaidMessageParserTest.COX_OLD_PB_3, 100);
		game(kc("Chambers of Xeric", 52), 100);
		tracker.onTick(101);
		assertEquals(Kc.builder().boss("Chambers of Xeric").killCount(52).timeSeconds(41 * 60 + 10.0)
			.personalBest(false).personalBestSeconds(40 * 60 + 3.0).teamSize(3).build(), onlyKc());

		notification("<col=ef20ff>Special loot:</col>", 102);
		notification("<col=ef20ff>" + TEAMMATE + " - Kodai insignia</col>", 102);
		notification("<col=ef20ff>" + ME + " - Twisted bow</col>", 102);
		List<RaidSpecialLoot> purples = of(RaidSpecialLoot.class);
		assertEquals(2, purples.size());
		assertEquals(RaidSpecialLoot.builder().raid("Chambers of Xeric")
			.item(new RaidSpecialLoot.Item(21043, "Kodai insignia", 1, 40_000_000))
			.recipient(Recipient.TEAMMATE).teamSize(3).killCount(52).build(), purples.get(0));
		assertEquals(Recipient.SELF, purples.get(1).getRecipient());
		assertEquals(20997, purples.get(1).getItem().getItemId());

		chest("Chambers of Xeric", 400);
		assertEquals(new Loot(LootSource.event("Chambers of Xeric"), CHEST, 52), onlyLoot());
	}

	@Test
	public void challengeModeIntervalUsesThePartySizeVarbitAndDropsTheIntervalPb()
	{
		view.cox = 12;
		notification(RaidMessageParserTest.COX_OLD_PB_11_15, 100);
		game(kc("Chambers of Xeric Challenge Mode", 13), 100);
		tracker.onTick(101);
		assertEquals(Kc.builder().boss("Chambers of Xeric Challenge Mode").killCount(13).timeSeconds(23 * 60 + 25.4)
			.personalBest(false).teamSize(12).build(), onlyKc());

		chest("Chambers of Xeric", 500);
		assertEquals("CM KC never lands on normal CoX",
			new Loot(LootSource.event("Chambers of Xeric Challenge Mode"), CHEST, 13), onlyLoot());
	}

	@Test
	public void intervalWithoutAMatchingVarbitHasNoTeamSize()
	{
		view.cox = 0;
		notification(RaidMessageParserTest.COX_OLD_PB_11_15, 100);
		game(kc("Chambers of Xeric", 60), 100);
		view.cox = 20;
		notification(RaidMessageParserTest.cox("11-15 players", "30:00", "25:00"), 200);
		game(kc("Chambers of Xeric", 61), 200);
		tracker.onTick(201);
		List<Kc> kcs = of(Kc.class);
		assertNull(kcs.get(0).getTeamSize());
		assertNull("20 is not in 11-15", kcs.get(1).getTeamSize());
	}

	@Test
	public void openIntervalNewPersonalBest()
	{
		view.cox = 26;
		notification(RaidMessageParserTest.COX_NEW_PB_24, 100);
		game(kc("Chambers of Xeric", 51), 100);
		tracker.onTick(101);
		Kc kc = onlyKc();
		assertEquals(Integer.valueOf(26), kc.getTeamSize());
		assertEquals(Boolean.TRUE, kc.getPersonalBest());
		assertNull(kc.getPersonalBestSeconds());
		assertEquals(Double.valueOf(37 * 60 + 4.2), kc.getTimeSeconds());
	}

	@Test
	public void coxSoloUsesTheMessageNotTheVarbit()
	{
		view.cox = 0;
		notification(RaidMessageParserTest.cox("Solo", "28:07", null), 100);
		game(kc("Chambers of Xeric", 5), 101);
		tracker.onTick(102);
		assertEquals(Integer.valueOf(1), onlyKc().getTeamSize());
		assertEquals(Double.valueOf(28 * 60 + 7.0), onlyKc().getPersonalBestSeconds());
	}

	// Theatre of Blood

	private void tob(String wave, String boss, int count)
	{
		game("Wave 'The Final Challenge' (" + wave + ") complete!<br>Duration: <col=ff0000>2:42.0</col><br>"
			+ "Theatre of Blood completion time: <col=ff0000>17:00.20</col> (new personal best)", 100);
		game("Theatre of Blood total completion time: <col=ff0000>24:40.20</col>. Personal best: 20:45.00", 100);
		game(kc(boss, count), 100);
		tracker.onTick(101);
	}

	@Test
	public void theatreOfBloodNormalModeWithTeammatePurple()
	{
		view.tob = 2;
		tob("Normal Mode", "Theatre of Blood", 73);
		assertEquals(Kc.builder().boss("Theatre of Blood").killCount(73).timeSeconds(17 * 60 + 0.2)
			.personalBest(true).personalBestSeconds(17 * 60 + 0.2).teamSize(2).build(), onlyKc());

		game("Bob Teammate found something special: Scythe of vitur", 150);
		RaidSpecialLoot purple = of(RaidSpecialLoot.class).get(0);
		assertEquals(RaidSpecialLoot.builder().raid("Theatre of Blood")
			.item(new RaidSpecialLoot.Item(22486, "Scythe of vitur (uncharged)", 1, 40_000_000))
			.recipient(Recipient.TEAMMATE).teamSize(2).killCount(73).build(), purple);

		chest("Theatre of Blood", 300);
		assertEquals(new Loot(LootSource.event("Theatre of Blood"), CHEST, 73), onlyLoot());
	}

	@Test
	public void theatreOfBloodEntryMode()
	{
		view.tob = 1;
		tob("Entry Mode", "Theatre of Blood: Entry Mode", 9);
		assertEquals(Integer.valueOf(1), onlyKc().getTeamSize());
		chest("Theatre of Blood", 300);
		assertEquals(new Loot(LootSource.event("Theatre of Blood: Entry Mode"), CHEST, 9), onlyLoot());
	}

	@Test
	public void theatreOfBloodHardMode()
	{
		view.tob = 4;
		tob("Hard Mode", "Theatre of Blood: Hard Mode", 21);
		assertEquals("Theatre of Blood: Hard Mode", onlyKc().getBoss());
		assertEquals(Integer.valueOf(4), onlyKc().getTeamSize());
		game(ME + " found something special: Avernic defender hilt", 120);
		assertEquals("Theatre of Blood: Hard Mode", of(RaidSpecialLoot.class).get(0).getRaid());
		assertEquals(Recipient.SELF, of(RaidSpecialLoot.class).get(0).getRecipient());
		chest("Theatre of Blood", 300);
		assertEquals(LootSource.event("Theatre of Blood: Hard Mode"), onlyLoot().getSource());
	}

	// Tombs of Amascut

	private void toa(String mode, String pb, int count)
	{
		String name = mode == null ? "Tombs of Amascut" : "Tombs of Amascut: " + mode;
		game("Challenge complete: The Wardens. Duration: <col=ef1020>9:40</col><br>" + name
			+ " challenge completion time: <col=ef1020>9:40</col>" + (pb == null ? " (new personal best)" : ". Personal best: " + pb), 100);
		game("Tombs of Amascut total completion time: <col=ef1020>0:01</col> (new personal best)", 100);
		game(kc(name, count), 100);
		tracker.onTick(101);
	}

	@Test
	public void tombsOfAmascutExpertWithMyPurple()
	{
		view.toa = 4;
		toa("Expert Mode", "8:31", 140);
		assertEquals(Kc.builder().boss("Tombs of Amascut: Expert Mode").killCount(140).timeSeconds(580.0)
			.personalBest(false).personalBestSeconds(511.0).teamSize(4).build(), onlyKc());
		game("<col=ff0000>" + ME + " found something special: Osmumten's fang</col>", 110);
		assertEquals(RaidSpecialLoot.builder().raid("Tombs of Amascut: Expert Mode")
			.item(new RaidSpecialLoot.Item(26219, "Osmumten's fang", 1, 40_000_000))
			.recipient(Recipient.SELF).teamSize(4).killCount(140).build(), of(RaidSpecialLoot.class).get(0));
		chest("Tombs of Amascut", 300);
		assertEquals(new Loot(LootSource.event("Tombs of Amascut: Expert Mode"), CHEST, 140), onlyLoot());
	}

	@Test
	public void tombsOfAmascutNormal()
	{
		view.toa = 8;
		toa(null, null, 2);
		assertEquals(Kc.builder().boss("Tombs of Amascut").killCount(2).timeSeconds(580.0).personalBest(true)
			.personalBestSeconds(580.0).teamSize(8).build(), onlyKc());
		chest("Tombs of Amascut", 300);
		assertEquals(new Loot(LootSource.event("Tombs of Amascut"), CHEST, 2), onlyLoot());
	}

	@Test
	public void tombsOfAmascutEntry()
	{
		view.toa = 1;
		toa("Entry Mode", "20:31", 9);
		assertEquals(Kc.builder().boss("Tombs of Amascut: Entry Mode").killCount(9).timeSeconds(580.0)
			.personalBest(false).personalBestSeconds(20 * 60 + 31.0).teamSize(1).build(), onlyKc());
		chest("Tombs of Amascut", 300);
		assertEquals(LootSource.event("Tombs of Amascut: Entry Mode"), onlyLoot().getSource());
	}

	// The Fractured Archive

	private void fracturedArchive(int count)
	{
		game("Challenge complete: Ocellus. Duration: <col=ef1020>2:54.60</col>. Total: <col=ef1020>2:54.60</col>. "
			+ "Personal best: 2:25.80", 100);
		game("The Fractured Archive: Total completion time: <col=ef1020>2:54.60</col>. Personal best: 2:25.80", 100);
		game(kc("Fractured Archive", count), 100);
		tracker.onTick(101);
	}

	@Test
	public void fracturedArchiveTeamSizeFromParty()
	{
		view.tfa = 3;
		// Other raids' party varbits are not used for TFA.
		view.toa = 5;
		view.tob = 5;
		fracturedArchive(136);
		assertEquals(Kc.builder().boss("Fractured Archive").killCount(136).timeSeconds(174.6).personalBest(false)
			.personalBestSeconds(145.8).teamSize(3).build(), onlyKc());
		chest("Fractured Archive", 300);
		assertEquals(new Loot(LootSource.event("Fractured Archive"), CHEST, 136), onlyLoot());
	}

	@Test
	public void fracturedArchiveWithoutPartyHasNoTeamSize()
	{
		view.tfa = 0;
		view.toa = 3;
		view.tob = 3;
		fracturedArchive(3);
		assertEquals(Kc.builder().boss("Fractured Archive").killCount(3).timeSeconds(174.6).personalBest(false)
			.personalBestSeconds(145.8).build(), onlyKc());
		chest("Fractured Archive", 300);
		assertEquals(new Loot(LootSource.event("Fractured Archive"), CHEST, 3), onlyLoot());
	}

	private static String willFind(String name, String item)
	{
		return "<col=ff0000>" + name + "</col> will find something special: <col=ff0000>" + item + "</col>";
	}

	private static String found(String name, String item)
	{
		return "<col=ef1020>" + name + "</col> found something special: <col=ef1020>" + item + "</col>";
	}

	@Test
	public void fracturedArchiveTeammatePurpleInTheWillFindWording()
	{
		view.tfa = 2;
		fracturedArchive(156);
		game(willFind(TEAMMATE, "Rondache"), 188);
		List<RaidSpecialLoot> purples = of(RaidSpecialLoot.class);
		assertEquals(1, purples.size());
		assertEquals(RaidSpecialLoot.builder().raid("Fractured Archive")
			.item(new RaidSpecialLoot.Item(Raids.purple("Rondache").getItemId(), "Rondache", 1, 40_000_000))
			.recipient(Recipient.TEAMMATE).teamSize(2).killCount(156).build(), purples.get(0));
	}

	@Test
	public void fracturedArchiveMyPurpleIsSentOnce()
	{
		view.tfa = 2;
		fracturedArchive(153);
		game(found(ME, "Rondache"), 183);
		List<RaidSpecialLoot> purples = of(RaidSpecialLoot.class);
		assertEquals(1, purples.size());
		assertEquals(Recipient.SELF, purples.get(0).getRecipient());
	}

	@Test
	public void willFindFollowedByFoundIsOnePurple()
	{
		view.tfa = 2;
		fracturedArchive(156);
		game(willFind(TEAMMATE, "Rondache"), 188);
		game(found(TEAMMATE, "Rondache"), 230);
		assertEquals(1, of(RaidSpecialLoot.class).size());
	}

	@Test
	public void differentItemsOrRecipientsInOneRaidAreSeparatePurples()
	{
		view.tfa = 2;
		fracturedArchive(156);
		game(willFind(TEAMMATE, "Rondache"), 188);
		game(found(TEAMMATE, "Zorya's tome (uncharged)"), 190);
		game(found(ME, "Rondache"), 192);
		List<RaidSpecialLoot> purples = of(RaidSpecialLoot.class);
		assertEquals(3, purples.size());
		assertEquals("Rondache", purples.get(0).getItem().getName());
		assertEquals("Zorya's tome (uncharged)", purples.get(1).getItem().getName());
		assertEquals(Recipient.SELF, purples.get(2).getRecipient());
	}

	@Test
	public void duplicateGuardIsClearedByTheNextRaid()
	{
		view.tfa = 2;
		fracturedArchive(156);
		game(willFind(TEAMMATE, "Rondache"), 188);
		game(kc("Fractured Archive", 157), 900);
		tracker.onTick(901);
		game(found(TEAMMATE, "Rondache"), 960);
		List<RaidSpecialLoot> purples = of(RaidSpecialLoot.class);
		assertEquals(2, purples.size());
		assertEquals(Integer.valueOf(156), purples.get(0).getKillCount());
		assertEquals(Integer.valueOf(157), purples.get(1).getKillCount());
	}

	@Test
	public void bothWordingsBeforeTheKillCountAreOnePurple()
	{
		view.tfa = 2;
		game(willFind(TEAMMATE, "Rondache"), 99);
		game(found(TEAMMATE, "Rondache"), 99);
		fracturedArchive(156);
		assertEquals(1, of(RaidSpecialLoot.class).size());
	}

	// Chest without a known mode

	@Test
	public void chestWithoutARaidKillCountHasNoKillCount()
	{
		chest("Chambers of Xeric", 100);
		assertEquals(new Loot(LootSource.event("Chambers of Xeric"), CHEST, null), onlyLoot());
	}

	@Test
	public void chestOfAnotherRaidDoesNotUseTheMode()
	{
		view.cox = 3;
		notification(RaidMessageParserTest.COX_OLD_PB_3, 100);
		game(kc("Chambers of Xeric Challenge Mode", 13), 100);
		tracker.onTick(101);
		chest("Theatre of Blood", 200);
		assertEquals(new Loot(LootSource.event("Theatre of Blood"), CHEST, null), onlyLoot());
	}

	@Test
	public void modeIsUsedForOneChestOnly()
	{
		game(kc("Chambers of Xeric Challenge Mode", 13), 100);
		tracker.onTick(101);
		chest("Chambers of Xeric", 200);
		chest("Chambers of Xeric", 210);
		List<Loot> loot = of(Loot.class);
		assertEquals(new Loot(LootSource.event("Chambers of Xeric Challenge Mode"), CHEST, 13), loot.get(0));
		assertEquals(new Loot(LootSource.event("Chambers of Xeric"), CHEST, null), loot.get(1));
	}

	@Test
	public void modeIsForgottenAfterThirtyMinutes()
	{
		game(kc("Chambers of Xeric Challenge Mode", 13), 100);
		tracker.onTick(101);
		tracker.onTick(100 + RaidTracker.RAID_MEMORY_TICKS + 1);
		chest("Chambers of Xeric", 100 + RaidTracker.RAID_MEMORY_TICKS + 1);
		assertEquals(new Loot(LootSource.event("Chambers of Xeric"), CHEST, null), onlyLoot());
	}

	@Test
	public void modeIsForgottenOnLogout()
	{
		game(kc("Chambers of Xeric Challenge Mode", 13), 100);
		tracker.onTick(101);
		tracker.reset();
		chest("Chambers of Xeric", 200);
		assertEquals(new Loot(LootSource.event("Chambers of Xeric"), CHEST, null), onlyLoot());
		game(ME + " found something special: Twisted bow", 201);
		assertTrue(of(RaidSpecialLoot.class).isEmpty());
	}

	// Purple timing

	@Test
	public void purpleBeforeTheKillCountWaitsForIt()
	{
		view.tob = 3;
		game(ME + " found something special: Ghrazi rapier", 99);
		tracker.onTick(100);
		assertTrue(events.isEmpty());
		tob("Hard Mode", "Theatre of Blood: Hard Mode", 21);
		assertEquals(2, events.size());
		assertTrue("Game order: kc first", events.get(0) instanceof Kc);
		RaidSpecialLoot purple = (RaidSpecialLoot) events.get(1);
		assertEquals("Theatre of Blood: Hard Mode", purple.getRaid());
		assertEquals(Integer.valueOf(21), purple.getKillCount());
		assertEquals(Integer.valueOf(3), purple.getTeamSize());
	}

	@Test
	public void purpleInTheSameTickAsAPendingKillCountComesAfterIt()
	{
		view.cox = 2;
		notification(RaidMessageParserTest.cox("2 players", "24:40", null), 100);
		game(kc("Chambers of Xeric", 9), 100);
		notification(TEAMMATE + " - Dragon claws", 100);
		assertTrue(events.isEmpty());
		tracker.onTick(101);
		assertEquals(2, events.size());
		assertTrue(events.get(0) instanceof Kc);
		assertEquals("Chambers of Xeric", ((RaidSpecialLoot) events.get(1)).getRaid());
	}

	@Test
	public void purpleWithoutAKillCountIsDropped()
	{
		game(ME + " found something special: Ghrazi rapier", 100);
		tracker.onTick(100 + RaidTracker.PURPLE_WAIT_TICKS + 1);
		game(kc("Theatre of Blood", 5), 100 + RaidTracker.PURPLE_WAIT_TICKS + 2);
		tracker.onTick(100 + RaidTracker.PURPLE_WAIT_TICKS + 3);
		assertEquals(1, events.size());
		assertTrue(of(RaidSpecialLoot.class).isEmpty());
	}

	@Test
	public void purpleOfAnotherRaidIsNotSentUnderTheCurrentRaid()
	{
		game(kc("Chambers of Xeric", 9), 100);
		tracker.onTick(101);
		game(ME + " found something special: Ghrazi rapier", 110);
		tracker.onTick(110 + RaidTracker.PURPLE_WAIT_TICKS + 1);
		assertTrue(of(RaidSpecialLoot.class).isEmpty());
	}

	// Toggles

	@Test
	public void purplesOff()
	{
		purplesEnabled = false;
		game(kc("Tombs of Amascut", 2), 100);
		tracker.onTick(101);
		game(ME + " found something special: Lightbearer", 110);
		assertEquals(1, events.size());
		assertTrue(of(RaidSpecialLoot.class).isEmpty());
	}

	@Test
	public void killCountsOffStillKnowsTheMode()
	{
		kcEnabled = false;
		view.toa = 2;
		toa("Expert Mode", "8:31", 140);
		assertTrue(of(Kc.class).isEmpty());
		game(ME + " found something special: Lightbearer", 110);
		RaidSpecialLoot purple = of(RaidSpecialLoot.class).get(0);
		assertEquals("Tombs of Amascut: Expert Mode", purple.getRaid());
		assertNull(purple.getKillCount());
		assertEquals(Integer.valueOf(2), purple.getTeamSize());
		chest("Tombs of Amascut", 300);
		assertEquals(new Loot(LootSource.event("Tombs of Amascut: Expert Mode"), CHEST, null), onlyLoot());
	}

	@Test
	public void lootOffStillSendsPurples()
	{
		lootEnabled = false;
		game(kc("Tombs of Amascut", 2), 100);
		tracker.onTick(101);
		game(ME + " found something special: Lightbearer", 110);
		chest("Tombs of Amascut", 300);
		assertTrue(of(Loot.class).isEmpty());
		assertEquals(1, of(RaidSpecialLoot.class).size());
	}

	@Test
	public void playerChatIsIgnored()
	{
		game(kc("Tombs of Amascut", 2), 100);
		tracker.onTick(101);
		tracker.onChatMessage(ChatMessageType.PUBLICCHAT, ME + " found something special: Lightbearer", 110);
		tracker.onChatMessage(ChatMessageType.CLAN_CHAT, ME + " - Twisted bow", 110);
		assertTrue(of(RaidSpecialLoot.class).isEmpty());
	}

	public static final class FakeRaidView implements RaidView
	{
		int cox;
		int tob;
		int toa;
		int tfa;

		@Override
		public int coxPartySize()
		{
			return cox;
		}

		@Override
		public int tobPartySize()
		{
			return tob;
		}

		@Override
		public int toaPartySize()
		{
			return toa;
		}

		@Override
		public int tfaPartySize()
		{
			return tfa;
		}

		@Override
		public boolean isLocalPlayer(String name)
		{
			return name.equalsIgnoreCase(ME);
		}
	}

	/** Names from the raid table, 40M each. */
	public static final class FakeItems implements ItemLookup
	{
		@Override
		public String name(int itemId)
		{
			return Raids.purples().stream().filter(p -> p.getItemId() == itemId).findFirst()
				.map(Raids.Purple::getName).orElse("Unknown");
		}


		@Override
		public long gePrice(int itemId)
		{
			return 40_000_000L;
		}

		@Override
		public int haPrice(int itemId)
		{
			return 0;
		}
	}
}
