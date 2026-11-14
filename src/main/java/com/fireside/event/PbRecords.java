package com.fireside.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.NonNull;
import lombok.Value;

/**
 * {@code pb.records} (contract 1.17.0): the player's own personal bests per
 * scope and team size from a raid's records table. There is no field for a
 * name. Each (scope, team size) pair appears once.
 */
@Value
public class PbRecords implements EventPayload
{
	/** {@link Record#getScope()} for the whole raid; other scopes are room names. */
	public static final String OVERALL = "overall";

	@Value
	public static class Record
	{
		@NonNull
		String scope;
		/** 1–100, including yourself. */
		int teamSize;
		double seconds;
	}

	/** Raid as the kill count message names it; the server normalizes it. */
	@NonNull
	String boss;
	@NonNull
	List<Record> records;

	public PbRecords(@NonNull String boss, @NonNull List<Record> records)
	{
		if (records.isEmpty())
		{
			throw new IllegalArgumentException("pb.records without records");
		}
		this.boss = boss;
		this.records = Collections.unmodifiableList(new ArrayList<>(records));
	}

	@Override
	public EventType type()
	{
		return EventType.PB_RECORDS;
	}
}
