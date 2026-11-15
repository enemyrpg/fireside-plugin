package com.fireside.quest;

import com.fireside.bank.ContentHash;
import com.fireside.event.DiariesSnapshot;
import com.fireside.event.QuestsSnapshot;
import com.fireside.event.QuestsSnapshot.Kind;
import com.fireside.event.QuestsSnapshot.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import javax.annotation.Nullable;

/**
 * Builds the {@code quests.snapshot} and {@code diaries.snapshot} payloads
 * from what was read, and their content hashes. No client access.
 * <p>
 * <b>Kind.</b> The game's quest table has a type column, and its values are
 * learned from two entries whose kind is known: {@link #QUEST_ANCHOR} is a
 * quest and {@link #MINIQUEST_ANCHOR} a miniquest. An entry with the same
 * type as one of them gets that kind; any other entry gets none. If either
 * anchor is missing or unread, or both have the same type, no entry gets a
 * kind. A change in the game can thus only remove {@code kind} (the server
 * then counts the entry as a quest), never send a wrong one.
 */
public final class QuestSnapshots
{
	static final String QUEST_ANCHOR = "COOKS_ASSISTANT";
	/** RuneLite names this row {@code MINIQUEST_BARCRAWL} in {@code DBTableID.Quest.Row}. */
	static final String MINIQUEST_ANCHOR = "ALFRED_GRIMHANDS_BARCRAWL";

	/** Bumping a prefix resends that snapshot once for every account. */
	private static final String QUESTS_PREFIX = "quests-v1";
	private static final String DIARIES_PREFIX = "diaries-v1";

	private QuestSnapshots()
	{
	}

	public static QuestsSnapshot quests(int questPoints, List<QuestView.RawQuest> raw)
	{
		Integer questType = null;
		Integer miniquestType = null;
		for (QuestView.RawQuest quest : raw)
		{
			if (QUEST_ANCHOR.equals(quest.getKey()))
			{
				questType = quest.getType();
			}
			else if (MINIQUEST_ANCHOR.equals(quest.getKey()))
			{
				miniquestType = quest.getType();
			}
		}
		boolean kindsKnown = questType != null && miniquestType != null && !questType.equals(miniquestType);
		List<QuestsSnapshot.Entry> entries = new ArrayList<>(raw.size());
		for (QuestView.RawQuest quest : raw)
		{
			Kind kind = null;
			if (kindsKnown && quest.getType() != null)
			{
				kind = quest.getType().equals(questType) ? Kind.QUEST
					: quest.getType().equals(miniquestType) ? Kind.MINIQUEST : null;
			}
			entries.add(new QuestsSnapshot.Entry(quest.getKey(), quest.getName(), quest.getState(), kind));
		}
		int points = Math.max(0, Math.min(QuestsSnapshot.MAX_QUEST_POINTS, questPoints));
		return new QuestsSnapshot(points, entries);
	}

	/** Every region and tier of {@link DiaryVarbits#ALL}. */
	public static DiariesSnapshot diaries(IntUnaryOperator varbit)
	{
		List<DiariesSnapshot.Entry> entries = new ArrayList<>(DiaryVarbits.ALL.size());
		for (DiaryVarbits.Entry diary : DiaryVarbits.ALL)
		{
			entries.add(new DiariesSnapshot.Entry(diary.getRegion(), diary.getTier(),
				diary.completed(varbit.applyAsInt(diary.getVarbit()))));
		}
		return new DiariesSnapshot(entries);
	}

	/** Quest points and each entry's key, state and kind, in order. Never names. */
	public static String hash(QuestsSnapshot snapshot)
	{
		StringBuilder text = new StringBuilder(QUESTS_PREFIX).append('|').append(snapshot.getQuestPoints()).append('|');
		for (QuestsSnapshot.Entry entry : snapshot.getQuests())
		{
			text.append(entry.getQuest()).append(':').append(entry.getState()).append(':')
				.append(entry.getKind() != null ? entry.getKind().name() : "-").append(';');
		}
		return ContentHash.sha256(text.toString());
	}

	public static String hash(DiariesSnapshot snapshot)
	{
		StringBuilder text = new StringBuilder(DIARIES_PREFIX).append('|');
		for (DiariesSnapshot.Entry entry : snapshot.getDiaries())
		{
			text.append(entry.getRegion()).append(':').append(entry.getTier()).append(':')
				.append(entry.isCompleted() ? '1' : '0').append(';');
		}
		return ContentHash.sha256(text.toString());
	}

	/**
	 * Whether the quest points and the quest states can belong together.
	 * Every quest gives at least one quest point and miniquests give none, so
	 * points without a finished entry, or a finished quest without points,
	 * means the login data is not complete yet.
	 */
	public static boolean consistent(QuestsSnapshot snapshot)
	{
		boolean anyFinished = false;
		boolean questFinished = false;
		boolean kindsKnown = false;
		for (QuestsSnapshot.Entry entry : snapshot.getQuests())
		{
			kindsKnown |= entry.getKind() != null;
			if (entry.getState() == State.FINISHED)
			{
				anyFinished = true;
				questFinished |= entry.getKind() == Kind.QUEST;
			}
		}
		if (snapshot.getQuestPoints() > 0)
		{
			return anyFinished;
		}
		// Without kinds a finished entry may be a miniquest, which gives no points.
		return !(kindsKnown && questFinished);
	}

	/** Type value → number of entries, for the debug log ({@code null} key: not read). Never names. */
	static Map<String, Integer> typeHistogram(List<QuestView.RawQuest> raw)
	{
		Map<String, Integer> counts = new TreeMap<>();
		for (QuestView.RawQuest quest : raw)
		{
			counts.merge(String.valueOf(quest.getType()), 1, Integer::sum);
		}
		return counts;
	}

	@Nullable
	static Integer typeOf(List<QuestView.RawQuest> raw, String key)
	{
		for (QuestView.RawQuest quest : raw)
		{
			if (key.equals(quest.getKey()))
			{
				return quest.getType();
			}
		}
		return null;
	}
}
