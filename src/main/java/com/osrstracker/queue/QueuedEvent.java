package com.osrstracker.queue;

import java.nio.charset.StandardCharsets;
import lombok.Value;

/** One event in the queue: its ID and its JSON line, exactly as it is sent. */
@Value
public class QueuedEvent
{
	String eventId;
	String json;

	/** Size of the JSON in a request body (UTF-8). */
	public int byteSize()
	{
		return json.getBytes(StandardCharsets.UTF_8).length;
	}
}
