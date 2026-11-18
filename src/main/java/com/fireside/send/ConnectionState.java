package com.fireside.send;

import com.fireside.net.VerifyResponse;
import com.fireside.net.VerifyResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The mutable side of {@link ConnectionStatus}: records the outcome of every
 * server response. No IO and no clock of its own. Not thread-safe; the sender
 * only touches it on its executor.
 */
public class ConnectionState
{
	@Nullable
	private Instant lastContactAt;
	private int consecutiveFailures;
	@Nullable
	private String failureDetail;
	private boolean rateLimited;
	@Nullable
	private VerifyResponse verifyResponse;
	private List<String> verifyWarnings = Collections.emptyList();

	/** Any answer that proves the server and key work. */
	public void success(Instant now)
	{
		lastContactAt = now;
		consecutiveFailures = 0;
		failureDetail = null;
		rateLimited = false;
	}

	/** A successful {@code verify}: a contact, plus the server's details and warnings. */
	public void verified(Instant now, VerifyResult result)
	{
		success(now);
		if (result.getResponse() != null)
		{
			verifyResponse = result.getResponse();
			verifyWarnings = Collections.unmodifiableList(new ArrayList<>(result.getWarnings()));
		}
	}

	/** A temporary failure: network error, 5xx, 429 or an unexpected answer. */
	public void failure(String detail, boolean rateLimited)
	{
		consecutiveFailures++;
		failureDetail = detail;
		this.rateLimited = rateLimited;
	}

	/** Another server or key: nothing known applies to it. */
	public void reset()
	{
		lastContactAt = null;
		consecutiveFailures = 0;
		failureDetail = null;
		rateLimited = false;
		verifyResponse = null;
		verifyWarnings = Collections.emptyList();
	}

	public ConnectionStatus snapshot(boolean checking, boolean manualCheck, @Nullable Instant nextAttemptAt)
	{
		return new ConnectionStatus(lastContactAt, consecutiveFailures, failureDetail, rateLimited, checking,
			manualCheck, nextAttemptAt, verifyResponse, verifyWarnings);
	}
}
