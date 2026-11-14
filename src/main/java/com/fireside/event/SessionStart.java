package com.fireside.event;

import lombok.NonNull;
import lombok.Value;

/** {@code session.start}: at login. */
@Value
public class SessionStart implements EventPayload
{
	@NonNull
	String displayName;
	@NonNull
	AccountType accountType;
	int world;
	@NonNull
	String pluginVersion;

	@Override
	public EventType type()
	{
		return EventType.SESSION_START;
	}
}
