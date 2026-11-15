package com.fireside.quest;

import static com.fireside.quest.FakeQuestView.MINIQUEST_TYPE;
import static com.fireside.quest.FakeQuestView.QUEST_TYPE;
import static com.fireside.quest.FakeQuestView.raw;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fireside.event.DiariesSnapshot;
import com.fireside.event.DiariesSnapshot.Tier;
import com.fireside.event.QuestsSnapshot;
import com.fireside.event.QuestsSnapshot.Kind;
import com.fireside.event.QuestsSnapshot.State;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

public class QuestSnapshotsTest
{
	private static final List<QuestView.RawQuest> RAW = Arrays.asList(
		raw("COOKS_ASSISTANT", State.FINISHED, QUEST_TYPE),
		raw("ALFRED_GRIMHANDS_BARCRAWL", State.NOT_STARTED, MINIQUEST_TYPE),
		raw("DRAGON_SLAYER_II", State.IN_PROGRESS, QUEST_TYPE),
		raw("BEAR_YOUR_SOUL", State.FINISHED, MINIQUEST_TYPE));

	private static Kind kind(QuestsSnapshot snapshot, String quest)
	{
		for (QuestsSnapshot.Entry entry : snapshot.getQuests())
		{
			if (entry.getQuest().equals(quest))
			{
				return entry.getKind();
			}
		}
		throw new AssertionError(quest);
	}

	@Test
	public void kindFollowsTheTypeOfTheTwoAnchors()
	{
		QuestsSnapshot snapshot = QuestSnapshots.quests(3, RAW);
		assertEquals(3, snapshot.getQuestPoints());
		assertEquals(4, snapshot.getQuests().size());
		assertEquals(Kind.QUEST, kind(snapshot, "COOKS_ASSISTANT"));
		assertEquals(Kind.QUEST, kind(snapshot, "DRAGON_SLAYER_II"));
		assertEquals(Kind.MINIQUEST, kind(snapshot, "ALFRED_GRIMHANDS_BARCRAWL"));
		assertEquals(Kind.MINIQUEST, kind(snapshot, "BEAR_YOUR_SOUL"));
		assertEquals(State.IN_PROGRESS, snapshot.getQuests().get(2).getState());
		assertEquals("Name of DRAGON_SLAYER_II", snapshot.getQuests().get(2).getName());
	}

	@Test
	public void theTypeValuesThemselvesDoNotMatter()
	{
		QuestsSnapshot snapshot = QuestSnapshots.quests(1, Arrays.asList(
			raw("COOKS_ASSISTANT", State.FINISHED, 7),
			raw("ALFRED_GRIMHANDS_BARCRAWL", State.NOT_STARTED, 3),
			raw("BEAR_YOUR_SOUL", State.NOT_STARTED, 3)));
		assertEquals(Kind.QUEST, kind(snapshot, "COOKS_ASSISTANT"));
		assertEquals(Kind.MINIQUEST, kind(snapshot, "BEAR_YOUR_SOUL"));
	}

	@Test
	public void anEntryWithAnotherOrNoTypeHasNoKind()
	{
		QuestsSnapshot snapshot = QuestSnapshots.quests(1, Arrays.asList(
			raw("COOKS_ASSISTANT", State.FINISHED, QUEST_TYPE),
			raw("ALFRED_GRIMHANDS_BARCRAWL", State.NOT_STARTED, MINIQUEST_TYPE),
			raw("SOMETHING_NEW", State.NOT_STARTED, 5),
			raw("UNREAD", State.NOT_STARTED, null)));
		assertNull(kind(snapshot, "SOMETHING_NEW"));
		assertNull(kind(snapshot, "UNREAD"));
		assertEquals(Kind.QUEST, kind(snapshot, "COOKS_ASSISTANT"));
	}

	@Test
	public void noKindAnywhereWhenAnAnchorCannotBeTrusted()
	{
		List<List<QuestView.RawQuest>> broken = Arrays.asList(
			// The miniquest anchor was not read.
			Arrays.asList(raw("COOKS_ASSISTANT", State.FINISHED, QUEST_TYPE),
				raw("ALFRED_GRIMHANDS_BARCRAWL", State.NOT_STARTED, null),
				raw("DRAGON_SLAYER_II", State.IN_PROGRESS, QUEST_TYPE)),
			// Both anchors have the same type.
			Arrays.asList(raw("COOKS_ASSISTANT", State.FINISHED, QUEST_TYPE),
				raw("ALFRED_GRIMHANDS_BARCRAWL", State.NOT_STARTED, QUEST_TYPE),
				raw("DRAGON_SLAYER_II", State.IN_PROGRESS, QUEST_TYPE)),
			// The quest anchor is gone.
			Arrays.asList(raw("ALFRED_GRIMHANDS_BARCRAWL", State.NOT_STARTED, MINIQUEST_TYPE),
				raw("DRAGON_SLAYER_II", State.IN_PROGRESS, QUEST_TYPE)));
		for (List<QuestView.RawQuest> raw : broken)
		{
			for (QuestsSnapshot.Entry entry : QuestSnapshots.quests(1, raw).getQuests())
			{
				assertNull(entry.getQuest(), entry.getKind());
			}
		}
	}

	@Test
	public void questPointsStayInsideTheContract()
	{
		assertEquals(0, QuestSnapshots.quests(-5, RAW).getQuestPoints());
		assertEquals(10_000, QuestSnapshots.quests(70_000, RAW).getQuestPoints());
	}

	@Test
	public void theHashCoversPointsStatesAndKindsButNotNames()
	{
		String hash = QuestSnapshots.hash(QuestSnapshots.quests(3, RAW));
		assertTrue(hash, hash.matches("sha256:[0-9a-f]{64}"));
		assertEquals(hash, QuestSnapshots.hash(QuestSnapshots.quests(3, RAW)));
		assertNotEquals(hash, QuestSnapshots.hash(QuestSnapshots.quests(4, RAW)));

		List<QuestView.RawQuest> renamed = Arrays.asList(
			new QuestView.RawQuest("COOKS_ASSISTANT", "Another name", State.FINISHED, QUEST_TYPE), RAW.get(1),
			RAW.get(2), RAW.get(3));
		assertEquals(hash, QuestSnapshots.hash(QuestSnapshots.quests(3, renamed)));

		List<QuestView.RawQuest> progressed = Arrays.asList(RAW.get(0), RAW.get(1),
			raw("DRAGON_SLAYER_II", State.FINISHED, QUEST_TYPE), RAW.get(3));
		assertNotEquals(hash, QuestSnapshots.hash(QuestSnapshots.quests(3, progressed)));

		List<QuestView.RawQuest> noKinds = Arrays.asList(RAW.get(0),
			raw("ALFRED_GRIMHANDS_BARCRAWL", State.NOT_STARTED, null), RAW.get(2), RAW.get(3));
		assertNotEquals(hash, QuestSnapshots.hash(QuestSnapshots.quests(3, noKinds)));
	}

	@Test
	public void questPointsMustFitTheStates()
	{
		assertTrue(QuestSnapshots.consistent(QuestSnapshots.quests(1, RAW)));
		// A finished quest gives at least one point.
		assertFalse(QuestSnapshots.consistent(QuestSnapshots.quests(0, RAW)));
		List<QuestView.RawQuest> nothingFinished = Arrays.asList(
			raw("COOKS_ASSISTANT", State.NOT_STARTED, QUEST_TYPE),
			raw("ALFRED_GRIMHANDS_BARCRAWL", State.NOT_STARTED, MINIQUEST_TYPE));
		// Points need a finished entry.
		assertFalse(QuestSnapshots.consistent(QuestSnapshots.quests(5, nothingFinished)));
		// A new account.
		assertTrue(QuestSnapshots.consistent(QuestSnapshots.quests(0, nothingFinished)));
		assertTrue(QuestSnapshots.consistent(QuestSnapshots.quests(0, Collections.emptyList())));
		// Only a miniquest finished: no points.
		List<QuestView.RawQuest> onlyMiniquest = Arrays.asList(
			raw("COOKS_ASSISTANT", State.NOT_STARTED, QUEST_TYPE),
			raw("ALFRED_GRIMHANDS_BARCRAWL", State.FINISHED, MINIQUEST_TYPE));
		assertTrue(QuestSnapshots.consistent(QuestSnapshots.quests(0, onlyMiniquest)));
		// The same when the kinds are unknown: the finished entry may be a miniquest.
		List<QuestView.RawQuest> unknownKinds = Arrays.asList(
			raw("COOKS_ASSISTANT", State.NOT_STARTED, null),
			raw("ALFRED_GRIMHANDS_BARCRAWL", State.FINISHED, null));
		assertTrue(QuestSnapshots.consistent(QuestSnapshots.quests(0, unknownKinds)));
	}

	@Test
	public void everyRegionHasFourTiersOnceAndItsOwnVarbit()
	{
		assertEquals(48, DiaryVarbits.ALL.size());
		Set<String> pairs = new HashSet<>();
		Set<Integer> varbits = new HashSet<>();
		for (DiaryVarbits.Entry diary : DiaryVarbits.ALL)
		{
			assertTrue(pairs.add(diary.getRegion() + "/" + diary.getTier()));
			assertTrue(varbits.add(diary.getVarbit()));
		}
		assertEquals("ARDOUGNE", DiaryVarbits.ALL.get(0).getRegion());
		assertEquals(Tier.EASY, DiaryVarbits.ALL.get(0).getTier());
		assertEquals("WILDERNESS", DiaryVarbits.ALL.get(47).getRegion());
		assertEquals(Tier.ELITE, DiaryVarbits.ALL.get(47).getTier());
	}

	private static boolean completed(DiariesSnapshot snapshot, String region, Tier tier)
	{
		for (DiariesSnapshot.Entry entry : snapshot.getDiaries())
		{
			if (entry.getRegion().equals(region) && entry.getTier() == tier)
			{
				return entry.isCompleted();
			}
		}
		throw new AssertionError(region);
	}

	@Test
	public void completedMeansAllTasksDoneAlsoForKaramja()
	{
		Map<Integer, Integer> varbits = new HashMap<>();
		varbits.put(VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, 1);
		// Karamja easy to hard: 1 is "started", 2 is "all tasks done".
		varbits.put(VarbitID.ATJUN_EASY_DONE, 2);
		varbits.put(VarbitID.ATJUN_MED_DONE, 1);
		varbits.put(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, 1);
		// A claimed reward is not a completed tier.
		varbits.put(VarbitID.FALADOR_EASY_REWARD, 1);
		DiariesSnapshot snapshot = QuestSnapshots.diaries(id -> varbits.getOrDefault(id, 0));
		assertEquals(48, snapshot.getDiaries().size());
		assertTrue(completed(snapshot, "ARDOUGNE", Tier.HARD));
		assertFalse(completed(snapshot, "ARDOUGNE", Tier.EASY));
		assertTrue(completed(snapshot, "KARAMJA", Tier.EASY));
		assertFalse(completed(snapshot, "KARAMJA", Tier.MEDIUM));
		assertFalse(completed(snapshot, "KARAMJA", Tier.HARD));
		assertTrue(completed(snapshot, "KARAMJA", Tier.ELITE));
		assertFalse(completed(snapshot, "FALADOR", Tier.EASY));

		String hash = QuestSnapshots.hash(snapshot);
		varbits.put(VarbitID.ATJUN_MED_DONE, 2);
		assertNotEquals(hash, QuestSnapshots.hash(QuestSnapshots.diaries(id -> varbits.getOrDefault(id, 0))));
	}
}
