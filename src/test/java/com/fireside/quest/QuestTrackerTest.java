package com.fireside.quest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fireside.FiresideConfig;
import com.fireside.bank.HashStore;
import com.fireside.event.DiariesSnapshot;
import com.fireside.event.Event;
import com.fireside.event.EventPayload;
import com.fireside.event.EventType;
import com.fireside.event.QuestsSnapshot;
import com.fireside.event.QuestsSnapshot.State;
import com.fireside.event.UuidV7Generator;
import com.fireside.net.SemVer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;

/** Readiness after login, triggers, throttling and old servers, with fakes for the game and the config. */
public class QuestTrackerTest
{
	private static final long ACCOUNT = 1234567890123456789L;
	private static final String PROFILE = "rsprofile.abc";
	private static final Instant START = Instant.parse("2026-11-15T18:00:00.000Z");
	private static final Duration TICK = Duration.ofMillis(600);
	/** Ticks from login to the baseline when nothing is in the way. */
	private static final int READY_TICKS = QuestTracker.SETTLE_TICKS + QuestTracker.CONFIRM_TICKS;

	private static final class MovingClock extends Clock
	{
		Instant now = START;

		@Override
		public Instant instant()
		{
			return now;
		}

		@Override
		public ZoneId getZone()
		{
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return this;
		}
	}

	/** RS profile config: profile → key → hash. Survives a "restart" (a new tracker). */
	private static final class MapStore implements HashStore
	{
		final Map<String, Map<String, String>> profiles = new HashMap<>();
		@Nullable
		String current = PROFILE;

		@Nullable
		@Override
		public String currentProfile()
		{
			return current;
		}

		@Nullable
		@Override
		public String get(String profile, String key)
		{
			return profiles.getOrDefault(profile, Collections.emptyMap()).get(key);
		}

		@Override
		public void set(String profile, String key, String hash)
		{
			profiles.computeIfAbsent(profile, p -> new HashMap<>()).put(key, hash);
		}

		@Override
		public void clearAll(String key)
		{
			for (Map<String, String> values : profiles.values())
			{
				values.remove(key);
			}
		}
	}

	private final MovingClock clock = new MovingClock();
	private final UuidV7Generator ids = new UuidV7Generator();
	private final FakeQuestView view = new FakeQuestView();
	private final MapStore store = new MapStore();
	private final List<Event> queued = new ArrayList<>();
	private final String sessionId = ids.next();
	private boolean enabled = true;
	private boolean inSession = true;
	private long nanos;
	private QuestTracker tracker;

	@Before
	public void setUp()
	{
		tracker = newTracker();
	}

	private QuestTracker newTracker()
	{
		return new QuestTracker(view, store, this::create, () -> inSession, queued::add, clock, () -> enabled,
			() -> nanos);
	}

	@Nullable
	private Event create(EventPayload payload)
	{
		return inSession ? Event.create(ids, ACCOUNT, clock.instant(), sessionId, payload) : null;
	}

	private void ticks(int count)
	{
		for (int i = 0; i < count; i++)
		{
			clock.now = clock.now.plus(TICK);
			tracker.onTick();
		}
	}

	private void pass(Duration time)
	{
		ticks((int) (time.toMillis() / TICK.toMillis()));
	}

	private List<EventType> types()
	{
		return queued.stream().map(Event::getType).collect(Collectors.toList());
	}

	private QuestsSnapshot lastQuests()
	{
		for (int i = queued.size() - 1; i >= 0; i--)
		{
			if (queued.get(i).getPayload() instanceof QuestsSnapshot)
			{
				return (QuestsSnapshot) queued.get(i).getPayload();
			}
		}
		throw new AssertionError("no quests.snapshot");
	}

	private DiariesSnapshot lastDiaries()
	{
		for (int i = queued.size() - 1; i >= 0; i--)
		{
			if (queued.get(i).getPayload() instanceof DiariesSnapshot)
			{
				return (DiariesSnapshot) queued.get(i).getPayload();
			}
		}
		throw new AssertionError("no diaries.snapshot");
	}

	private void becomeReady()
	{
		ticks(READY_TICKS);
		assertEquals(2, queued.size());
		queued.clear();
	}

	private void finishDragonSlayer()
	{
		view.set("DRAGON_SLAYER_II", State.FINISHED);
		view.questPoints += 5;
		tracker.onVarChanged(VarPlayerID.QP, -1);
	}

	// --- Never before the login data is complete ---

	@Test
	public void oneOfEachOnceTheDataIsSettledAndConfirmed()
	{
		ticks(READY_TICKS - 1);
		assertTrue("sent before two reads agreed", queued.isEmpty());
		ticks(1);
		assertEquals(Arrays.asList(EventType.QUESTS_SNAPSHOT, EventType.DIARIES_SNAPSHOT), types());
		assertEquals(1, lastQuests().getQuestPoints());
		assertEquals(3, lastQuests().getQuests().size());
		assertEquals(48, lastDiaries().getDiaries().size());
		// Exactly two reads of every quest so far, and none on the following ticks.
		assertEquals(2 * view.quests.size(), view.questReads);
		ticks(50);
		assertEquals(2 * view.quests.size(), view.questReads);
		assertEquals(2, queued.size());
	}

	@Test
	public void aBurstOfTicksAfterLoginDoesNotShortenTheWait()
	{
		// The client catches up: many game ticks in the same moment.
		for (int i = 0; i < 50; i++)
		{
			tracker.onTick();
		}
		assertEquals(0, view.questReads);
		assertTrue(queued.isEmpty());
		pass(QuestTracker.SETTLE_TIME.minus(TICK));
		assertEquals(0, view.questReads);
		ticks(1);
		assertEquals(view.quests.size(), view.questReads);
		assertTrue(queued.isEmpty());
		ticks(QuestTracker.CONFIRM_TICKS);
		assertEquals(2, queued.size());
	}

	@Test
	public void nothingWithoutAnActiveSession()
	{
		inSession = false;
		ticks(100);
		assertTrue(queued.isEmpty());
		assertEquals(0, view.questReads);
		inSession = true;
		ticks(READY_TICKS - 1);
		assertTrue(queued.isEmpty());
		ticks(1);
		assertEquals(2, queued.size());
	}

	@Test
	public void nothingBeforeTheStatsAreLoaded()
	{
		view.statsLoaded = false;
		ticks(100);
		assertTrue(queued.isEmpty());
		assertEquals(0, view.questReads);
		view.statsLoaded = true;
		ticks(READY_TICKS - 1);
		assertTrue(queued.isEmpty());
		ticks(1);
		assertEquals(2, queued.size());
	}

	@Test
	public void statsThatDisappearRestartTheWait()
	{
		ticks(QuestTracker.SETTLE_TICKS - 1);
		view.statsLoaded = false;
		ticks(1);
		view.statsLoaded = true;
		ticks(READY_TICKS - 1);
		assertTrue(queued.isEmpty());
		ticks(1);
		assertEquals(2, queued.size());
	}

	@Test
	public void nothingWhileTheQuestDataIsStillArriving()
	{
		// Login: the game has no quest data yet, so everything reads as not started.
		view.set("COOKS_ASSISTANT", State.NOT_STARTED);
		view.set("DRAGON_SLAYER_II", State.NOT_STARTED);
		view.questPoints = 0;
		ticks(QuestTracker.SETTLE_TICKS);
		// It arrives in pieces between the reads.
		view.set("COOKS_ASSISTANT", State.FINISHED);
		view.questPoints = 1;
		ticks(QuestTracker.CONFIRM_TICKS);
		assertTrue("an empty first snapshot would date everything in the next", queued.isEmpty());
		view.set("DRAGON_SLAYER_II", State.IN_PROGRESS);
		ticks(QuestTracker.CONFIRM_TICKS);
		assertTrue(queued.isEmpty());
		ticks(QuestTracker.CONFIRM_TICKS);
		assertEquals(2, queued.size());
		assertEquals(State.FINISHED, lastQuests().getQuests().get(0).getState());
		assertEquals(State.IN_PROGRESS, lastQuests().getQuests().get(2).getState());
	}

	@Test
	public void nothingWhileTheDiaryDataIsStillArriving()
	{
		ticks(QuestTracker.SETTLE_TICKS);
		view.varbits.put(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, 1);
		ticks(QuestTracker.CONFIRM_TICKS);
		assertTrue(queued.isEmpty());
		ticks(QuestTracker.CONFIRM_TICKS);
		assertEquals(2, queued.size());
		assertTrue(lastDiaries().getDiaries().get(0).isCompleted());
	}

	@Test
	public void nothingWhileQuestPointsDoNotFitTheStates()
	{
		// The states are in, the quest points are not.
		view.questPoints = 0;
		ticks(100);
		assertTrue(queued.isEmpty());
		view.questPoints = 1;
		ticks(2 * QuestTracker.CONFIRM_TICKS);
		assertEquals(2, queued.size());

		// And the other way round.
		QuestTracker second = newTracker();
		tracker = second;
		queued.clear();
		store.profiles.clear();
		view.set("COOKS_ASSISTANT", State.NOT_STARTED);
		ticks(100);
		assertTrue(queued.isEmpty());
	}

	@Test
	public void aNewAccountWithNothingDoneIsSent()
	{
		view.set("COOKS_ASSISTANT", State.NOT_STARTED);
		view.set("DRAGON_SLAYER_II", State.NOT_STARTED);
		view.questPoints = 0;
		ticks(READY_TICKS);
		assertEquals(2, queued.size());
	}

	@Test
	public void logoutAndHopWaitForTheNewData()
	{
		becomeReady();
		tracker.flush();
		tracker.reset();
		// The next login starts empty and fills in: nothing until it is confirmed again.
		view.set("COOKS_ASSISTANT", State.NOT_STARTED);
		view.set("DRAGON_SLAYER_II", State.NOT_STARTED);
		view.questPoints = 0;
		ticks(QuestTracker.SETTLE_TICKS);
		view.set("COOKS_ASSISTANT", State.FINISHED);
		view.set("DRAGON_SLAYER_II", State.IN_PROGRESS);
		view.questPoints = 1;
		ticks(2 * QuestTracker.CONFIRM_TICKS);
		// Confirmed, and the same as before the logout: nothing to send.
		assertTrue(queued.isEmpty());
		// Two reads before the logout; after it the empty one, the full one and the one confirming it.
		assertEquals(5 * view.quests.size(), view.questReads);
		ticks(100);
		assertEquals(5 * view.quests.size(), view.questReads);
	}

	@Test
	public void notLoggedInOrNotAStandardWorld()
	{
		view.loggedIn = false;
		ticks(50);
		view.loggedIn = true;
		view.standard = false;
		ticks(50);
		store.current = null;
		view.standard = true;
		ticks(50);
		assertTrue(queued.isEmpty());
		assertEquals(0, view.questReads);
	}

	// --- Only when the content changed ---

	@Test
	public void unchangedAfterARestartIsNotSentAgain()
	{
		becomeReady();
		assertNotNull(store.get(PROFILE, FiresideConfig.QUESTS_HASH_KEY));
		assertNotNull(store.get(PROFILE, FiresideConfig.DIARIES_HASH_KEY));
		tracker = newTracker();
		ticks(100);
		assertTrue(queued.isEmpty());
	}

	@Test
	public void aChangeWhileLoggedOutIsSentAfterLogin()
	{
		becomeReady();
		tracker = newTracker();
		view.varbits.put(VarbitID.ATJUN_EASY_DONE, 2);
		ticks(READY_TICKS);
		assertEquals(Collections.singletonList(EventType.DIARIES_SNAPSHOT), types());
	}

	// --- Triggers ---

	@Test
	public void aFinishedQuestIsReadShortlyAfterTheQuestPointsChange()
	{
		becomeReady();
		pass(Duration.ofMinutes(2));
		int reads = view.questReads;
		finishDragonSlayer();
		ticks(1);
		assertEquals("read in the same tick as the trigger", reads, view.questReads);
		ticks(1);
		assertEquals(Collections.singletonList(EventType.QUESTS_SNAPSHOT), types());
		assertEquals(6, lastQuests().getQuestPoints());
		assertEquals(State.FINISHED, lastQuests().getQuests().get(2).getState());
		assertEquals(reads + view.quests.size(), view.questReads);
	}

	@Test
	public void theCompletedCountVarbitsAlsoTrigger()
	{
		becomeReady();
		pass(Duration.ofMinutes(2));
		view.set("ALFRED_GRIMHANDS_BARCRAWL", State.FINISHED);
		tracker.onVarChanged(1234, VarbitID.MINIQUESTS_COMPLETED_COUNT);
		ticks(2);
		assertEquals(Collections.singletonList(EventType.QUESTS_SNAPSHOT), types());
	}

	@Test
	public void otherVariablesNeverCauseARead()
	{
		becomeReady();
		int reads = view.questReads;
		for (int i = 0; i < 100; i++)
		{
			tracker.onVarChanged(VarPlayerID.QP, VarbitID.BANK_TAB_1);
			tracker.onVarChanged(VarPlayerID.QP + 1, -1);
			ticks(1);
		}
		assertEquals(reads, view.questReads);
		assertTrue(queued.isEmpty());
	}

	@Test
	public void atMostOneQuestReadInTenSeconds()
	{
		becomeReady();
		int reads = view.questReads;
		// Right after the baseline read.
		finishDragonSlayer();
		ticks(2);
		assertEquals(reads, view.questReads);
		pass(QuestTracker.MIN_GAP);
		assertEquals(reads + view.quests.size(), view.questReads);
		// Inside the baseline's window: held until it ends or the session does.
		assertTrue(queued.isEmpty());
		tracker.flush();
		assertEquals(Collections.singletonList(EventType.QUESTS_SNAPSHOT), types());
	}

	@Test
	public void aStartedQuestIsFoundByTheIntervalRead()
	{
		becomeReady();
		view.set("ALFRED_GRIMHANDS_BARCRAWL", State.IN_PROGRESS);
		int reads = view.questReads;
		pass(QuestTracker.INTERVAL.minus(TICK));
		assertEquals(reads, view.questReads);
		ticks(1);
		assertEquals(reads + view.quests.size(), view.questReads);
		assertEquals(Collections.singletonList(EventType.QUESTS_SNAPSHOT), types());
		// One read per interval, and nothing is sent when nothing changed.
		pass(QuestTracker.INTERVAL.multipliedBy(3));
		assertEquals(reads + 4 * view.quests.size(), view.questReads);
		assertEquals(1, queued.size());
	}

	@Test
	public void aDiaryTierIsReadOnTheNextTickWithoutReadingQuests()
	{
		becomeReady();
		pass(Duration.ofMinutes(2));
		int reads = view.questReads;
		view.varbits.put(VarbitID.KOUREND_DIARY_ELITE_COMPLETE, 1);
		tracker.onVarChanged(999, VarbitID.KOUREND_DIARY_ELITE_COMPLETE);
		assertTrue(queued.isEmpty());
		ticks(1);
		assertEquals(Collections.singletonList(EventType.DIARIES_SNAPSHOT), types());
		assertEquals(reads, view.questReads);
		// A claimed reward is not a tier.
		tracker.onVarChanged(999, VarbitID.KOUREND_ELITE_REWARD);
		ticks(5);
		assertEquals(1, queued.size());
	}

	@Test
	public void aReadThatDoesNotFitIsSkipped()
	{
		becomeReady();
		pass(Duration.ofMinutes(1));
		// The points changed before the state did.
		view.questPoints = 0;
		tracker.onVarChanged(VarPlayerID.QP, -1);
		ticks(2);
		assertTrue(queued.isEmpty());
	}

	// --- Throttling and flushing ---

	@Test
	public void aSecondChangeInsideTheWindowIsHeldAndFlushedAtLogout()
	{
		becomeReady();
		pass(Duration.ofMinutes(2));
		finishDragonSlayer();
		ticks(2);
		assertEquals(1, queued.size());
		pass(QuestTracker.MIN_GAP);
		view.set("ALFRED_GRIMHANDS_BARCRAWL", State.FINISHED);
		tracker.onVarChanged(1234, VarbitID.MINIQUESTS_COMPLETED_COUNT);
		ticks(2);
		assertEquals("inside the window", 1, queued.size());
		Instant read = clock.now;
		tracker.flush();
		assertEquals(2, queued.size());
		assertEquals("the event is the capture's", read, queued.get(1).getOccurredAt());
		assertEquals(State.FINISHED, lastQuests().getQuests().get(1).getState());
	}

	@Test
	public void theHeldSnapshotIsQueuedWhenTheWindowEnds()
	{
		becomeReady();
		view.varbits.put(VarbitID.VARROCK_DIARY_EASY_COMPLETE, 1);
		tracker.onVarChanged(999, VarbitID.VARROCK_DIARY_EASY_COMPLETE);
		ticks(1);
		assertTrue("the baseline was queued less than a window ago", queued.isEmpty());
		pass(QuestTracker.WINDOW);
		assertEquals(Collections.singletonList(EventType.DIARIES_SNAPSHOT), types());
	}

	// --- A slow read ---

	@Test
	public void aSlowReadIsSpreadOverTicksAndNeverUsesMoreThanTheBudget()
	{
		view.quests.clear();
		view.quests.add(FakeQuestView.raw("COOKS_ASSISTANT", State.FINISHED, 0));
		view.quests.add(FakeQuestView.raw("ALFRED_GRIMHANDS_BARCRAWL", State.NOT_STARTED, 1));
		for (int i = 0; i < 198; i++)
		{
			view.quests.add(FakeQuestView.raw("QUEST_" + i, State.NOT_STARTED, 0));
		}
		// Each script takes 0.1 ms: a full read would take 20 ms.
		view.beforeQuest = () -> nanos += 100_000;
		int perTick = (int) (QuestTracker.TICK_BUDGET_NANOS / 100_000);
		int ticksPerRead = 200 / perTick;

		ticks(QuestTracker.SETTLE_TICKS - 1);
		int before = 0;
		int readTicks = 0;
		while (queued.isEmpty() && readTicks < 1000)
		{
			ticks(1);
			readTicks++;
			assertTrue("more than the budget in one tick", view.questReads - before <= perTick);
			before = view.questReads;
		}
		assertEquals(2, queued.size());
		assertEquals(400, view.questReads);
		assertEquals(200, lastQuests().getQuests().size());
		// Two reads of ten ticks each, with the confirm wait in between.
		assertEquals(2 * ticksPerRead + QuestTracker.CONFIRM_TICKS - 1, readTicks);
	}

	@Test
	public void aChangeDuringASlowReadIsReadAgain()
	{
		for (int i = 0; i < 2 * QuestTracker.MIN_PER_TICK; i++)
		{
			view.quests.add(FakeQuestView.raw("QUEST_" + i, State.NOT_STARTED, 0));
		}
		// So slow that only the minimum is read per tick: a read takes three ticks.
		view.beforeQuest = () -> nanos += 10_000_000;
		for (int i = 0; i < 100 && queued.isEmpty(); i++)
		{
			ticks(1);
		}
		assertEquals(2, queued.size());
		queued.clear();
		int reads = view.questReads;
		for (int i = 0; i < 1000 && view.questReads == reads; i++)
		{
			ticks(1);
		}
		// The interval read has started and is past QUEST_0.
		assertEquals(reads + QuestTracker.MIN_PER_TICK, view.questReads);
		view.set("QUEST_0", State.FINISHED);
		view.questPoints++;
		tracker.onVarChanged(VarPlayerID.QP, -1);
		ticks(2);
		assertTrue("the read in progress saw the old state", queued.isEmpty());
		pass(QuestTracker.MIN_GAP.plus(QuestTracker.MIN_GAP));
		assertEquals(Collections.singletonList(EventType.QUESTS_SNAPSHOT), types());
		assertEquals(State.FINISHED, lastQuests().getQuests().get(3).getState());
		assertEquals(2, lastQuests().getQuestPoints());
	}

	// --- Settings and old servers ---

	@Test
	public void nothingWhileTurnedOffAndTheBaselineComesWhenTurnedOn()
	{
		enabled = false;
		ticks(100);
		assertTrue(queued.isEmpty());
		assertEquals(0, view.questReads);
		enabled = true;
		tracker.onSettingsChanged();
		ticks(READY_TICKS);
		assertEquals(2, queued.size());
	}

	@Test
	public void turningOffForgetsAHeldSnapshot()
	{
		becomeReady();
		view.varbits.put(VarbitID.VARROCK_DIARY_EASY_COMPLETE, 1);
		tracker.onVarChanged(999, VarbitID.VARROCK_DIARY_EASY_COMPLETE);
		ticks(1);
		enabled = false;
		tracker.onSettingsChanged();
		tracker.flush();
		pass(QuestTracker.WINDOW);
		assertTrue(queued.isEmpty());
		// On again: it is still a change since the last queued snapshot.
		enabled = true;
		tracker.onSettingsChanged();
		ticks(READY_TICKS);
		assertEquals(Collections.singletonList(EventType.DIARIES_SNAPSHOT), types());
	}

	@Test
	public void anOldServerGetsNothingAndTheHashesAreCleared()
	{
		becomeReady();
		tracker.onServerContract(SemVer.parse("1.20.0"));
		assertNull(store.get(PROFILE, FiresideConfig.QUESTS_HASH_KEY));
		assertNull(store.get(PROFILE, FiresideConfig.DIARIES_HASH_KEY));
		int reads = view.questReads;
		ticks(100);
		assertTrue(queued.isEmpty());
		assertEquals(reads, view.questReads);
		// Upgraded: everything is sent again.
		tracker.onServerContract(SemVer.parse("1.21.0"));
		ticks(READY_TICKS);
		assertEquals(2, queued.size());
	}

	@Test
	public void anUnknownServerVersionDoesNotStopTheBaseline()
	{
		tracker.onServerContract(null);
		ticks(READY_TICKS);
		assertEquals(2, queued.size());
	}
}
