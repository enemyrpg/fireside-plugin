package com.osrstracker.event;

import com.google.gson.annotations.SerializedName;
import lombok.NonNull;
import lombok.Value;

/** {@code session.end}: at logout, hop or when the client closes. */
@Value
public class SessionEnd implements EventPayload
{
	public enum Reason
	{
		@SerializedName("logout")
		LOGOUT,
		@SerializedName("client_closed")
		CLIENT_CLOSED,
		@SerializedName("hop")
		HOP,
	}

	@NonNull
	Reason reason;

	@Override
	public EventType type()
	{
		return EventType.SESSION_END;
	}
}
