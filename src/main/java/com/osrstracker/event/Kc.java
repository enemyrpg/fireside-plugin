package com.osrstracker.event;

import javax.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/** {@code kc}: a kill count message in chat. Null optional fields are left out of the JSON. */
@Value
@Builder
public class Kc implements EventPayload
{
	@NonNull
	String boss;
	int killCount;
	@Nullable
	Double timeSeconds;
	@Nullable
	Boolean personalBest;
	/** 1–100, including yourself. */
	@Nullable
	Integer teamSize;

	@Override
	public EventType type()
	{
		return EventType.KC;
	}
}
