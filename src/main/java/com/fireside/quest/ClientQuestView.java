package com.fireside.quest;

import com.fireside.event.QuestsSnapshot;
import com.fireside.session.ClientGameView;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.RuneScapeProfileType;

/** {@link QuestView} backed by the client. Client thread only. */
@Singleton
public class ClientQuestView implements QuestView
{
	private static final Quest[] QUESTS = Quest.values();

	private final Client client;
	private final ClientGameView game;
	/** {@code COL_TYPE} per quest once read: static game data, so it is looked up once. */
	private final Integer[] types = new Integer[QUESTS.length];

	@Inject
	ClientQuestView(Client client, ClientGameView game)
	{
		this.client = client;
		this.game = game;
	}

	@Override
	public boolean loggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN;
	}

	@Override
	public boolean standardWorld()
	{
		return RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.STANDARD;
	}

	@Override
	public boolean statsLoaded()
	{
		return game.skills() != null;
	}

	@Override
	public int questPoints()
	{
		return client.getVarpValue(VarPlayerID.QP);
	}

	@Override
	public int questCount()
	{
		return QUESTS.length;
	}

	@Override
	public RawQuest quest(int index)
	{
		Quest quest = QUESTS[index];
		if (types[index] == null)
		{
			types[index] = readType(quest);
		}
		return new RawQuest(quest.name(), quest.getName(), state(quest.getState(client)), types[index]);
	}

	@Override
	public int varbit(int varbitId)
	{
		return client.getVarbitValue(varbitId);
	}

	/** The quest's ID is its row in the game's quest table. Null if the row or column is not there. */
	private Integer readType(Quest quest)
	{
		try
		{
			Object[] field = client.getDBTableField(quest.getId(), DBTableID.Quest.COL_TYPE, 0);
			return field != null && field.length > 0 && field[0] instanceof Integer ? (Integer) field[0] : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static QuestsSnapshot.State state(QuestState state)
	{
		switch (state)
		{
			case FINISHED:
				return QuestsSnapshot.State.FINISHED;
			case NOT_STARTED:
				return QuestsSnapshot.State.NOT_STARTED;
			default:
				return QuestsSnapshot.State.IN_PROGRESS;
		}
	}
}
