package com.fireside.event;

import java.time.Instant;
import lombok.NonNull;
import lombok.Value;

/** {@code session.afk}: when an AFK period is over. Statistics only. */
@Value
public class SessionAfk implements EventPayload
{
	@NonNull
	Instant startedAt;
	@NonNull
	Instant endedAt;

	public SessionAfk(@NonNull Instant startedAt, @NonNull Instant endedAt)
	{
		if (endedAt.isBefore(startedAt))
		{
			throw new IllegalArgumentException("endedAt is before startedAt");
		}
		this.startedAt = startedAt;
		this.endedAt = endedAt;
	}

	@Override
	public EventType type()
	{
		return EventType.SESSION_AFK;
	}
}
