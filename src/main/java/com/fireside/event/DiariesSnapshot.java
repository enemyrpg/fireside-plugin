package com.fireside.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.NonNull;
import lombok.Value;

/**
 * {@code diaries.snapshot} (contract 1.21.0): one entry per achievement diary
 * region and tier. {@code completed} means every task of the tier is done,
 * whether or not the reward has been claimed.
 */
@Value
public class DiariesSnapshot implements EventPayload
{
	public enum Tier
	{
		EASY,
		MEDIUM,
		HARD,
		ELITE,
	}

	@Value
	public static class Entry
	{
		/** Region key in {@code UPPER_SNAKE_CASE}, e.g. {@code ARDOUGNE}. */
		@NonNull
		String region;
		@NonNull
		Tier tier;
		boolean completed;
	}

	@NonNull
	List<Entry> diaries;

	public DiariesSnapshot(@NonNull List<Entry> diaries)
	{
		this.diaries = Collections.unmodifiableList(new ArrayList<>(diaries));
	}

	@Override
	public EventType type()
	{
		return EventType.DIARIES_SNAPSHOT;
	}
}
