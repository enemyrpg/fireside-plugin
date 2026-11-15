package com.fireside.quest;

import com.fireside.event.QuestsSnapshot.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The game as the quest tracker reads it. */
final class FakeQuestView implements QuestView
{
	static final int QUEST_TYPE = 0;
	static final int MINIQUEST_TYPE = 1;

	boolean loggedIn = true;
	boolean standard = true;
	boolean statsLoaded = true;
	int questPoints = 1;
	final List<RawQuest> quests = new ArrayList<>();
	final Map<Integer, Integer> varbits = new HashMap<>();
	/** Calls to {@link #quest}: each is a client script. */
	int questReads;
	/** Runs before each {@link #quest} call, e.g. to make a read slow. */
	Runnable beforeQuest = () ->
	{
	};

	FakeQuestView()
	{
		quests.add(raw("COOKS_ASSISTANT", State.FINISHED, QUEST_TYPE));
		quests.add(raw("ALFRED_GRIMHANDS_BARCRAWL", State.NOT_STARTED, MINIQUEST_TYPE));
		quests.add(raw("DRAGON_SLAYER_II", State.IN_PROGRESS, QUEST_TYPE));
	}

	static RawQuest raw(String key, State state, Integer type)
	{
		return new RawQuest(key, "Name of " + key, state, type);
	}

	void set(String key, State state)
	{
		for (int i = 0; i < quests.size(); i++)
		{
			if (quests.get(i).getKey().equals(key))
			{
				quests.set(i, raw(key, state, quests.get(i).getType()));
				return;
			}
		}
		throw new IllegalArgumentException(key);
	}

	@Override
	public boolean loggedIn()
	{
		return loggedIn;
	}

	@Override
	public boolean standardWorld()
	{
		return standard;
	}

	@Override
	public boolean statsLoaded()
	{
		return statsLoaded;
	}

	@Override
	public int questPoints()
	{
		return questPoints;
	}

	@Override
	public int questCount()
	{
		return quests.size();
	}

	@Override
	public RawQuest quest(int index)
	{
		beforeQuest.run();
		questReads++;
		return quests.get(index);
	}

	@Override
	public int varbit(int varbitId)
	{
		return varbits.getOrDefault(varbitId, 0);
	}
}
