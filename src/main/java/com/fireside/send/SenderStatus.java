package com.fireside.send;

import java.time.Instant;
import javax.annotation.Nullable;
import lombok.Value;

/** What the sender is doing, as an immutable snapshot for the panel. */
@Value
public class SenderStatus
{
	public enum State
	{
		/** The queue is not open yet. */
		STARTING,
		/** "Send data" is off. */
		OFF,
		/** Server URL or API key missing or invalid. */
		NOT_CONFIGURED,
		IDLE,
		/** Checking the server's contract version. */
		CHECKING,
		SENDING,
		/** Waiting after a temporary error. */
		RETRYING,
		/** 401, 404 or a redirect: waits for new config or a successful "Test connection". */
		STOPPED,
		/** The server's contract is too old: the queue is kept and not sent. */
		HELD,
	}

	public enum StopReason
	{
		UNAUTHORIZED,
		NOT_FOUND,
		REDIRECT,
	}

	public static final SenderStatus STARTING_STATUS = new SenderStatus(State.STARTING, 0, 0, null, 0, null,
		null, null, null, null);

	State state;
	int queueSize;
	long rejectedCount;
	@Nullable
	Instant lastSentAt;
	/** Events the server confirmed in the last successful request. */
	int lastSentCount;
	/** When the next attempt is due ({@link State#RETRYING}). */
	@Nullable
	Instant retryAt;
	/** Why it is retrying or stopped (for the user). */
	@Nullable
	String detail;
	@Nullable
	StopReason stopReason;
	/** The server's contract version when {@link State#HELD}. */
	@Nullable
	String serverContract;
	/** Set when the queue could not be written to or read from disk. */
	@Nullable
	String storageError;
}
