package com.fireside.pet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fireside.Contract;
import com.fireside.contract.ContractPackage;
import com.fireside.event.Event;
import com.fireside.event.EventJson;
import com.fireside.event.EventType;
import com.fireside.event.PetDrop;
import com.fireside.event.UuidV7Generator;
import com.fireside.net.SemVer;
import com.networknt.schema.JsonSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

public class PetTrackerTest
{
	private static final String FOLLOWED = "You have a funny feeling like you're being followed.";
	private static final String BACKPACK = "You feel something weird sneaking into your backpack.";
	private static final String DUPLICATE = "You have a funny feeling like you would have been followed...";
	private static final String VORKATH_KC = "Your Vorkath kill count is: <col=ff0000>412</col>.";
	private static final String CLOG_VORKI = "New item added to your collection log: <col=ef1020>Vorki</col>";
	private static final Instant T0 = Instant.parse("2026-11-08T19:42:13.512Z");
	private static final long ACCOUNT = 1234567890123456789L;
	/** The tick of the pet message in most tests. */
	private static final int T = 100;

	private final FakePetView view = new FakePetView();
	private final UuidV7Generator ids = new UuidV7Generator();
	private final String sessionId = ids.next();
	private final List<Event> events = new ArrayList<>();
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private Logger root;
	private Level oldLevel;
	private Instant now = T0;
	private boolean session = true;
	private boolean enabled = true;
	private final PetTracker tracker = new PetTracker(view,
		() -> session ? Event.reserve(ids, ACCOUNT, now, sessionId) : null, events::add, () -> enabled);

	@Before
	public void captureLogs()
	{
		root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		oldLevel = root.getLevel();
		root.setLevel(Level.ALL);
		appender.start();
		root.addAppender(appender);
	}

	/** Every event is valid for the server, and no chat text was logged. */
	@After
	public void eventsAreValidAndNothingIsLogged()
	{
		root.detachAppender(appender);
		root.setLevel(oldLevel);
		EventJson json = new EventJson(RuneLiteAPI.GSON);
		for (Event event : events)
		{
			assertEquals(EventType.PET_DROP, event.getType());
			String line = json.toJson(event);
			for (JsonSchema schema : Arrays.asList(ContractPackage.schema("schema/event.json"),
				ContractPackage.schema("schema/events/pet.drop.json")))
			{
				assertTrue(line, ContractPackage.validate(schema, line).isEmpty());
			}
			assertFalse(line, line.contains("funny feeling") || line.contains("sneaking"));
		}
		for (ILoggingEvent event : appender.list)
		{
			String logged = event.getFormattedMessage();
			assertFalse(logged, logged.contains("funny feeling") || logged.contains("sneaking")
				|| logged.contains("collection log:") || logged.contains("Vorki") || logged.contains("Vorkath"));
		}
	}

	private void game(String message, int tick)
	{
		tracker.onChatMessage(ChatMessageType.GAMEMESSAGE, message, tick);
	}

	/** Runs the ticks after the pet message until the drop is queued. */
	private PetDrop afterHold()
	{
		for (int tick = T + 1; tick <= T + PetTracker.HOLD_TICKS; tick++)
		{
			assertTrue("held until the hold is over", events.isEmpty());
			tracker.onTick(tick);
		}
		assertEquals(1, events.size());
		return (PetDrop) events.get(0).getPayload();
	}

	// The message

	@Test
	public void aPetMessageBecomesOneEventAfterTheHold()
	{
		game(FOLLOWED, T);
		PetDrop drop = afterHold();
		assertEquals(PetDrop.Message.FOLLOWED, drop.getMessage());
		assertNull(drop.getGuessedPet());
		assertNull(drop.getGuessedSource());
		tracker.onTick(T + 10);
		assertEquals(1, events.size());
	}

	@Test
	public void theEnvelopeIsFromTheMomentOfTheMessage()
	{
		game(FOLLOWED, T);
		String next = ids.next();
		now = T0.plusMillis(1800);
		afterHold();
		assertEquals(T0, events.get(0).getOccurredAt());
		assertEquals(sessionId, events.get(0).getSessionId());
		assertTrue("ID from before later events", events.get(0).getEventId().compareTo(next) < 0);
	}

	@Test
	public void playerChatIsNeverAPetMessage()
	{
		for (ChatMessageType type : Arrays.asList(ChatMessageType.PUBLICCHAT, ChatMessageType.PRIVATECHAT,
			ChatMessageType.FRIENDSCHAT, ChatMessageType.CLAN_CHAT, ChatMessageType.CLAN_MESSAGE))
		{
			tracker.onChatMessage(type, FOLLOWED, T);
		}
		game("Zezima has a funny feeling like he's being followed: Vorki at 412 kills.", T);
		tracker.onTick(T + PetTracker.HOLD_TICKS);
		assertTrue(events.isEmpty());
	}

	@Test
	public void aSecondMessageSendsTheFirst()
	{
		game(FOLLOWED, T);
		game(DUPLICATE, T + 1);
		assertEquals(1, events.size());
		assertEquals(PetDrop.Message.FOLLOWED, ((PetDrop) events.get(0).getPayload()).getMessage());
		tracker.onTick(T + 1 + PetTracker.HOLD_TICKS);
		assertEquals(2, events.size());
		assertEquals(PetDrop.Message.DUPLICATE, ((PetDrop) events.get(1).getPayload()).getMessage());
	}

	@Test
	public void flushSendsAHeldDropAndResetForgetsIt()
	{
		game(VORKATH_KC, T);
		game(FOLLOWED, T);
		tracker.flush();
		assertEquals(1, events.size());
		assertEquals("Vorkath", ((PetDrop) events.get(0).getPayload()).getGuessedSource().getName());
		tracker.flush();
		assertEquals(1, events.size());

		game(FOLLOWED, T + 20);
		tracker.reset();
		tracker.onTick(T + 30);
		assertEquals(1, events.size());
	}

	// When nothing is sent

	@Test
	public void nothingWithTheToggleOff()
	{
		enabled = false;
		game(FOLLOWED, T);
		tracker.onTick(T + PetTracker.HOLD_TICKS);
		assertTrue(events.isEmpty());
	}

	@Test
	public void nothingWithoutASession()
	{
		session = false;
		game(FOLLOWED, T);
		tracker.onTick(T + PetTracker.HOLD_TICKS);
		assertTrue(events.isEmpty());
	}

	@Test
	public void nothingOnASpecialWorld()
	{
		view.standardWorld = false;
		game(FOLLOWED, T);
		tracker.onTick(T + PetTracker.HOLD_TICKS);
		assertTrue(events.isEmpty());
	}

	@Test
	public void nothingToAServerWithoutPetDrops()
	{
		tracker.onServerContract(SemVer.parse("1.26.0"));
		game(FOLLOWED, T);
		tracker.onTick(T + PetTracker.HOLD_TICKS);
		assertTrue(events.isEmpty());

		tracker.onServerContract(SemVer.parse(Contract.PETS_VERSION));
		game(FOLLOWED, T);
		afterHold();
	}

	@Test
	public void sentWhenTheServerVersionIsUnknown()
	{
		tracker.onServerContract(null);
		game(FOLLOWED, T);
		afterHold();
	}

	// The pet

	@Test
	public void petFromTheCollectionLogLineAfterTheMessage()
	{
		game(FOLLOWED, T);
		game(CLOG_VORKI, T);
		PetDrop drop = afterHold();
		assertEquals(new PetDrop.GuessedPet(FakePetView.VORKI, "Vorki"), drop.getGuessedPet());
	}

	@Test
	public void petFromTheCollectionLogLineJustBeforeTheMessage()
	{
		game(CLOG_VORKI, T);
		game(BACKPACK, T);
		assertEquals(new PetDrop.GuessedPet(FakePetView.VORKI, "Vorki"), afterHold().getGuessedPet());
	}

	@Test
	public void anOldCollectionLogLineIsNotUsed()
	{
		game(CLOG_VORKI, T - PetTracker.COLLECTION_LOG_BEFORE_TICKS - 1);
		game(FOLLOWED, T);
		assertNull(afterHold().getGuessedPet());
	}

	@Test
	public void aCollectionLogLineForSomethingElseIsNotAPet()
	{
		game(FOLLOWED, T);
		game("New item added to your collection log: Draconic visage", T);
		assertNull(afterHold().getGuessedPet());
	}

	@Test
	public void backpackPetFromTheInventory()
	{
		view.inventory.put(995, 1000);
		tracker.onTick(T - 1);
		// The pet is in the inventory before the message is handled.
		view.inventory.put(FakePetView.BEAVER, 1);
		game(BACKPACK, T);
		assertEquals(new PetDrop.GuessedPet(FakePetView.BEAVER, "Beaver"), afterHold().getGuessedPet());
	}

	@Test
	public void backpackPetThatAppearsATickLater()
	{
		tracker.onTick(T - 1);
		game(BACKPACK, T);
		view.inventory.put(FakePetView.BEAVER, 1);
		assertEquals(new PetDrop.GuessedPet(FakePetView.BEAVER, "Beaver"), afterHold().getGuessedPet());
	}

	@Test
	public void aPetAlreadyInTheInventoryIsNotTheNewOne()
	{
		view.inventory.put(FakePetView.HERON, 1);
		tracker.onTick(T - 1);
		game(BACKPACK, T);
		assertNull(afterHold().getGuessedPet());
	}

	@Test
	public void twoNewPetsInTheInventoryGiveNoGuess()
	{
		tracker.onTick(T - 1);
		view.inventory.put(FakePetView.HERON, 1);
		view.inventory.put(FakePetView.BEAVER, 1);
		game(BACKPACK, T);
		assertNull(afterHold().getGuessedPet());
	}

	@Test
	public void otherNewItemsInTheInventoryAreNotPets()
	{
		tracker.onTick(T - 1);
		view.inventory.put(1511, 5);
		game(BACKPACK, T);
		assertNull(afterHold().getGuessedPet());
	}

	@Test
	public void followedPetFromTheFollowersName()
	{
		game(FOLLOWED, T);
		view.followerName = "vorki";
		assertEquals(new PetDrop.GuessedPet(FakePetView.VORKI, "Vorki"), afterHold().getGuessedPet());
	}

	@Test
	public void aFollowerWithAnUnknownNameGivesNoGuess()
	{
		view.followerName = "General Graardor Jr.";
		game(FOLLOWED, T);
		assertNull(afterHold().getGuessedPet());
	}

	@Test
	public void theFollowerSaysNothingAboutABackpackPet()
	{
		view.followerName = "Vorki";
		game(BACKPACK, T);
		assertNull(afterHold().getGuessedPet());
	}

	@Test
	public void theCollectionLogBeatsTheFollower()
	{
		view.followerName = "Heron";
		game(FOLLOWED, T);
		game(CLOG_VORKI, T + 1);
		assertEquals(FakePetView.VORKI, afterHold().getGuessedPet().getItemId());
	}

	@Test
	public void aDuplicateNeverHasAPet()
	{
		view.followerName = "Vorki";
		game(VORKATH_KC, T);
		game(DUPLICATE, T);
		game(CLOG_VORKI, T);
		PetDrop drop = afterHold();
		assertEquals(PetDrop.Message.DUPLICATE, drop.getMessage());
		assertNull(drop.getGuessedPet());
		assertEquals(PetDrop.GuessedSource.activity("Vorkath"), drop.getGuessedSource());
	}

	// The source

	@Test
	public void sourceFromAKillCountBeforeTheMessage()
	{
		game(VORKATH_KC, T - PetTracker.ACTIVITY_BEFORE_TICKS);
		game(FOLLOWED, T);
		assertEquals(PetDrop.GuessedSource.activity("Vorkath"), afterHold().getGuessedSource());
	}

	@Test
	public void sourceFromAKillCountAfterTheMessage()
	{
		game(FOLLOWED, T);
		game(VORKATH_KC, T + 1);
		assertEquals(PetDrop.GuessedSource.activity("Vorkath"), afterHold().getGuessedSource());
	}

	@Test
	public void anOldKillCountIsNotTheSource()
	{
		game(VORKATH_KC, T - PetTracker.ACTIVITY_BEFORE_TICKS - 1);
		game(FOLLOWED, T);
		assertNull(afterHold().getGuessedSource());
	}

	@Test
	public void sourceFromLoot()
	{
		tracker.onLoot("Clue Scroll (Master)", T - 1);
		game(FOLLOWED, T);
		assertEquals(PetDrop.GuessedSource.activity("Clue Scroll (Master)"), afterHold().getGuessedSource());
	}

	@Test
	public void lootFromAnotherPlayerIsNotASource()
	{
		tracker.onLoot(null, T);
		game(FOLLOWED, T);
		assertNull(afterHold().getGuessedSource());
	}

	@Test
	public void aKillCountBeatsLootInTheSameTick()
	{
		tracker.onLoot("Chambers of Xeric", T);
		game("Your completed Chambers of Xeric Challenge Mode count is: <col=ff0000>12</col>.", T);
		game(FOLLOWED, T);
		assertEquals(PetDrop.GuessedSource.activity("Chambers of Xeric Challenge Mode"),
			afterHold().getGuessedSource());
	}

	@Test
	public void sourceFromSkillXp()
	{
		tracker.onXp("WOODCUTTING", 1000, T - 50);
		tracker.onXp("WOODCUTTING", 1025, T);
		game(BACKPACK, T);
		assertEquals(PetDrop.GuessedSource.skill("WOODCUTTING"), afterHold().getGuessedSource());
	}

	@Test
	public void theXpLoadedAtLoginIsNotAGain()
	{
		tracker.onXp("WOODCUTTING", 1000, T);
		game(FOLLOWED, T);
		assertNull(afterHold().getGuessedSource());
	}

	@Test
	public void combatXpIsNotASource()
	{
		for (String skill : Arrays.asList("ATTACK", "STRENGTH", "DEFENCE", "RANGED", "MAGIC", "HITPOINTS",
			"PRAYER", "SLAYER"))
		{
			tracker.onXp(skill, 1000, T - 50);
			tracker.onXp(skill, 1100, T);
		}
		game(FOLLOWED, T);
		assertNull(afterHold().getGuessedSource());
	}

	@Test
	public void oldSkillXpIsNotTheSource()
	{
		tracker.onXp("FISHING", 1000, T - 50);
		tracker.onXp("FISHING", 1040, T - PetTracker.SKILL_BEFORE_TICKS - 1);
		game(FOLLOWED, T);
		assertNull(afterHold().getGuessedSource());
	}

	@Test
	public void theClosestCandidateWins()
	{
		game(VORKATH_KC, T - 6);
		tracker.onXp("FISHING", 1000, T - 50);
		tracker.onXp("FISHING", 1040, T);
		game(FOLLOWED, T);
		assertEquals(PetDrop.GuessedSource.skill("FISHING"), afterHold().getGuessedSource());
	}

	@Test
	public void anActivityBeatsASkillInTheSameTick()
	{
		tracker.onXp("MINING", 1000, T - 50);
		tracker.onXp("MINING", 1040, T);
		tracker.onLoot("Zalcano", T);
		game(FOLLOWED, T);
		assertEquals(PetDrop.GuessedSource.activity("Zalcano"), afterHold().getGuessedSource());
	}

	@Test
	public void aCloserCandidateAfterTheMessageReplacesAnOlderOne()
	{
		game("Your Zulrah kill count is: <col=ff0000>5</col>.", T - 8);
		game(FOLLOWED, T);
		game(VORKATH_KC, T + 1);
		assertEquals(PetDrop.GuessedSource.activity("Vorkath"), afterHold().getGuessedSource());
	}

	@Test
	public void longNamesAreCutToTheContractLimit()
	{
		StringBuilder name = new StringBuilder();
		while (name.length() < 150)
		{
			name.append("Reward chest ");
		}
		tracker.onLoot(name.toString(), T);
		game(FOLLOWED, T);
		PetDrop.GuessedSource source = afterHold().getGuessedSource();
		assertNotNull(source);
		assertEquals(PetDrop.MAX_NAME_LENGTH, source.getName().length());
	}
}
