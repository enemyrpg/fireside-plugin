package com.fireside.queue;

import javax.annotation.Nullable;
import lombok.NonNull;
import lombok.Value;

/** An event that goes to the local error log instead of being sent again. */
@Value
public class RejectedEvent
{
	@NonNull
	QueuedEvent event;
	/** HTTP status of the response that rejected it ({@code 200} for a per-event rejection). */
	@Nullable
	Integer httpStatus;
	/** The server's error message, or why the plugin gave up on it. */
	@NonNull
	String error;
}
