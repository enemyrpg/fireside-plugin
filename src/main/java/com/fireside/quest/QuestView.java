package com.fireside.quest;

import com.fireside.event.QuestsSnapshot;
import javax.annotation.Nullable;
import lombok.NonNull;
import lombok.Value;

/**
 * What the quest tracker reads from the game. Client thread only.
 * {@link ClientQuestView} is the real one; tests use a fake.
 */
public interface QuestView
{
	/** One entry of RuneLite's {@code Quest} enum as the game has it now. */
	@Value
	class RawQuest
	{
		/** The enum name. */
		@NonNull
		String key;
		/** The name in the game. */
		@NonNull
		String name;
		@NonNull
		QuestsSnapshot.State state;
		/** {@code DBTableID.Quest.COL_TYPE} of the quest's row, or null if it could not be read. */
		@Nullable
		Integer type;
	}

	boolean loggedIn();

	/** A standard world: not Leagues, Deadman, beta or another world with its own progress. */
	boolean standardWorld();

	/** Every skill has a real level: the login data has arrived. */
	boolean statsLoaded();

	int questPoints();

	/** Number of entries in the {@code Quest} enum. */
	int questCount();

	/** Entry {@code index} of the {@code Quest} enum. Runs a client script: never call it for every quest every tick. */
	RawQuest quest(int index);

	int varbit(int varbitId);
}
