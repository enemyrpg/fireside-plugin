package com.fireside.event;

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

	/**
	 * The envelope of an event whose payload is not known yet. Reserve it when
	 * the event happens, so its ID and time are in game order, and add the
	 * payload when it is known.
	 */
	@Value
	public static class Reserved
	{
		@NonNull
		String eventId;
		long accountHash;
		@NonNull
		Instant occurredAt;
		@NonNull
		String sessionId;

		public Event with(EventPayload payload)
		{
			return new Event(eventId, accountHash, occurredAt, sessionId, payload);
		}
	}

	/** The envelope of a new event with the next ID from {@code ids}. */
	public static Reserved reserve(UuidV7Generator ids, long accountHash, Instant occurredAt, String sessionId)
	{
		if (accountHash == NO_ACCOUNT)
		{
			throw new IllegalArgumentException("not logged in");
		}
		return new Reserved(ids.next(), accountHash, occurredAt, sessionId);
	}

	public EventType getType()
	{
		return payload.type();
	}
}
