package com.fireside.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.NonNull;
import lombok.Value;

/**
 * {@code quests.snapshot} (contract 1.21.0): every entry of RuneLite's
 * {@code Quest} enum, each once, with the account's quest points. The enum
 * also holds miniquests; {@code kind} tells them apart and is left out when
 * it could not be read (the server then counts the entry as a quest).
 */
@Value
public class QuestsSnapshot implements EventPayload
{
	public static final int MAX_QUEST_POINTS = 10_000;

	public enum State
	{
		NOT_STARTED,
		IN_PROGRESS,
		FINISHED,
	}

	public enum Kind
	{
		QUEST,
		MINIQUEST,
	}

	@Value
	public static class Entry
	{
		/** The {@code Quest} enum name, e.g. {@code DRAGON_SLAYER_II}. */
		@NonNull
		String quest;
		/** The name in the game. */
		@NonNull
		String name;
		@NonNull
		State state;
		@Nullable
		Kind kind;
	}

	int questPoints;
	@NonNull
	List<Entry> quests;

	public QuestsSnapshot(int questPoints, @NonNull List<Entry> quests)
	{
		if (questPoints < 0 || questPoints > MAX_QUEST_POINTS)
		{
			throw new IllegalArgumentException("questPoints must be 0-" + MAX_QUEST_POINTS);
		}
		this.questPoints = questPoints;
		this.quests = Collections.unmodifiableList(new ArrayList<>(quests));
	}

	@Override
	public EventType type()
	{
		return EventType.QUESTS_SNAPSHOT;
	}
}
