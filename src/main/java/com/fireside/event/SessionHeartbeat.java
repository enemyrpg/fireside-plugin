package com.fireside.event;

import java.util.List;
import javax.annotation.Nullable;
import lombok.NonNull;
import lombok.Value;

/**
 * {@code session.heartbeat}: every 60 seconds. {@code worldLocation} and
 * {@code worldActivity} are sent as {@code null} (not left out) when
 * RuneLite's world list is not loaded; see {@link EventJson}.
 */
@Value
public class SessionHeartbeat implements EventPayload
{
	int regionId;
	int world;
	/** {@code UPPER_SNAKE_CASE} names, may be empty. */
	@NonNull
	List<String> worldTypes;
	@Nullable
	String worldLocation;
	/** May be an empty string. */
	@Nullable
	String worldActivity;
	boolean idle;

	@Override
	public EventType type()
	{
		return EventType.SESSION_HEARTBEAT;
	}
}
