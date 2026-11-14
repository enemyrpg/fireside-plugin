package com.fireside.event;

/**
 * The {@code payload} of an event. Implementations are immutable and are
 * serialized by {@link EventJson}; every field becomes a JSON field, so they
 * must hold exactly what the contract allows.
 */
public interface EventPayload
{
	/** Not a field, so it is never serialized into the payload. */
	EventType type();
}
