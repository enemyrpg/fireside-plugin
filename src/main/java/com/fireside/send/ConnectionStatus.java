package com.fireside.send;

import com.fireside.net.VerifyResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * What the plugin knows about its connection to the server, as an immutable
 * snapshot for the panel. Every server response updates it: {@code verify}
 * (automatic and "Test connection"), {@code POST /ingest} and the icon
 * endpoints. Stops (401, 404, redirect) and a too-old server are reported by
 * {@link SenderStatus} itself.
 * <p>
 * Whether the connection counts as up depends on the time ({@link #health}),
 * so the panel can turn yellow without a network call.
 */
@Value
public class ConnectionStatus
{
	/** A single failure keeps "Connected" until the last contact is older than this. */
	public static final Duration STALE_AFTER = Duration.ofMinutes(2);
	/** Consecutive failures that turn "Connected" into "retrying" at once. */
	public static final int FAILURES_FOR_RETRYING = 2;

	public static final ConnectionStatus NONE = new ConnectionStatus(null, 0, null, false, false, false, null, null,
		Collections.emptyList());

	public enum Health
	{
		/** No contact yet for this connection, and no failure that says it is down. */
		UNKNOWN,
		CONNECTED,
		/** The server did not answer (or answered 429 or 5xx); the plugin tries again. */
		RETRYING,
	}

	/** The last successful contact with the current server and key. */
	@Nullable
	Instant lastContactAt;
	/** Temporary failures (network, 5xx, 429, unexpected answers) since the last successful contact. */
	int consecutiveFailures;
	/** What went wrong the last time, for the user. */
	@Nullable
	String failureDetail;
	/** The last failure was a 429. */
	boolean rateLimited;
	/** A request to the server is in flight. */
	boolean checking;
	/** "Test connection" is waiting or running. */
	boolean manualCheck;
	/** When the sender tries again after a failure, or null. */
	@Nullable
	Instant nextAttemptAt;
	/** The answer to the last successful {@code verify} for this connection, or null. */
	@Nullable
	VerifyResponse verifyResponse;
	/** Warnings from that {@code verify} (clock difference, contract). */
	List<String> verifyWarnings;

	public Health health(Instant now)
	{
		if (consecutiveFailures == 0)
		{
			return lastContactAt != null ? Health.CONNECTED : Health.UNKNOWN;
		}
		if (consecutiveFailures >= FAILURES_FOR_RETRYING || lastContactAt == null
			|| Duration.between(lastContactAt, now).compareTo(STALE_AFTER) > 0)
		{
			return Health.RETRYING;
		}
		return Health.CONNECTED;
	}
}
