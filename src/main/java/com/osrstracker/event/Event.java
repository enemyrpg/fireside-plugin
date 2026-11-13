package com.osrstracker.event;

import java.time.Instant;
import lombok.NonNull;
import lombok.Value;

/**
 * The event envelope. Create it when the event happens (in game order), so
 * its {@code eventId} sorts after every earlier event's.
 */
@Value
public class Event
{
	public static final int SCHEMA_VERSION = 1;
	/** RuneLite's account hash when not logged in. Such events are never created. */
	public static final long NO_ACCOUNT = -1;

	@NonNull
	String eventId;
	long accountHash;
	@NonNull
	Instant occurredAt;
	@NonNull
	String sessionId;
	@NonNull
	EventPayload payload;

	public Event(@NonNull String eventId, long accountHash, @NonNull Instant occurredAt,
		@NonNull String sessionId, @NonNull EventPayload payload)
	{
		if (accountHash == NO_ACCOUNT)
		{
			throw new IllegalArgumentException("not logged in");
		}
		this.eventId = eventId;
		this.accountHash = accountHash;
		this.occurredAt = occurredAt;
		this.sessionId = sessionId;
		this.payload = payload;
	}

	/** A new event with the next ID from {@code ids}. */
	public static Event create(UuidV7Generator ids, long accountHash, Instant occurredAt, String sessionId,
		EventPayload payload)
	{
		return new Event(ids.next(), accountHash, occurredAt, sessionId, payload);
	}

	public EventType getType()
	{
		return payload.type();
	}
}
