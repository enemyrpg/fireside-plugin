package com.fireside.net;

import com.google.gson.Gson;
import com.fireside.Branding;
import com.fireside.Contract;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

/**
 * Outcome of "Test connection". Built by pure factory methods so every case can
 * be unit tested. Messages are meant for the user and never contain the API key.
 */
public final class VerifyResult
{
	/** Clock difference above which the user is warned (see "Klokker" in 03). */
	public static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(60);

	public enum Outcome
	{
		OK,
		NOT_CONFIGURED,
		INVALID_URL,
		INVALID_KEY,
		UNKNOWN_HOST,
		CONNECTION_REFUSED,
		TIMEOUT,
		TLS_ERROR,
		NETWORK_ERROR,
		UNAUTHORIZED,
		NOT_FOUND,
		RATE_LIMITED,
		SERVER_ERROR,
		UNEXPECTED_RESPONSE,
	}

	private final Outcome outcome;
	private final String message;
	@Nullable
	private final VerifyResponse response;
	@Nullable
	private final Duration clockSkew;
	private final boolean contractTooOld;
	private final List<String> warnings;

	private VerifyResult(Outcome outcome, String message, @Nullable VerifyResponse response,
		@Nullable Duration clockSkew, boolean contractTooOld, List<String> warnings)
	{
		this.outcome = outcome;
		this.message = message;
		this.response = response;
		this.clockSkew = clockSkew;
		this.contractTooOld = contractTooOld;
		this.warnings = Collections.unmodifiableList(warnings);
	}

	private static VerifyResult error(Outcome outcome, String message)
	{
		return new VerifyResult(outcome, message, null, null, false, Collections.emptyList());
	}

	static VerifyResult notConfigured()
	{
		return error(Outcome.NOT_CONFIGURED, "Set the server URL and API key in the plugin settings.");
	}

	static VerifyResult invalidUrl()
	{
		return error(Outcome.INVALID_URL,
			"Invalid server URL. Use the server's full base address, e.g. http://127.0.0.1:3000.");
	}

	static VerifyResult invalidKey()
	{
		return error(Outcome.INVALID_KEY,
			"The API key contains spaces or other invalid characters. Copy it again from the dashboard.");
	}

	/** Classifies a network failure (no HTTP response). */
	static VerifyResult fromException(IOException e, ServerUrl url)
	{
		if (e instanceof UnknownHostException)
		{
			return error(Outcome.UNKNOWN_HOST,
				"Unknown host \"" + url.host() + "\". Check the server URL.");
		}
		// SocketTimeoutException is an InterruptedIOException, as is OkHttp's call timeout.
		if (e instanceof InterruptedIOException)
		{
			return error(Outcome.TIMEOUT,
				"Timed out waiting for the server. Check the URL and that the server is running and reachable.");
		}
		if (e instanceof ConnectException)
		{
			return error(Outcome.CONNECTION_REFUSED,
				"Can't reach the server (connection refused). Is it running, and is the port right?");
		}
		if (e instanceof SSLException)
		{
			return error(Outcome.TLS_ERROR,
				"HTTPS error. Check that the URL starts with http:// or https:// as your server expects.");
		}
		return error(Outcome.NETWORK_ERROR,
			"Network error (" + e.getClass().getSimpleName() + "). Check the server URL.");
	}

	/**
	 * Classifies an HTTP response.
	 *
	 * @param body           response body, or {@code null} if it could not be read
	 * @param requestStarted local time just before the request was sent
	 * @param responseAt     local time when the response arrived
	 */
	static VerifyResult fromResponse(int code, @Nullable String retryAfter, @Nullable String location,
		@Nullable String body, Gson gson, Instant requestStarted, Instant responseAt)
	{
		if (code == 401)
		{
			return error(Outcome.UNAUTHORIZED,
				"The server rejected the API key (invalid or revoked). Create a key in the dashboard under Settings → API keys.");
		}
		if (code == 404)
		{
			return error(Outcome.NOT_FOUND,
				"Not found (HTTP 404). Use the server's base URL, e.g. http://127.0.0.1:3000, without /api/v1.");
		}
		if (code == 429)
		{
			Long seconds = parseRetryAfter(retryAfter);
			return error(Outcome.RATE_LIMITED, seconds != null
				? "Too many requests (HTTP 429). Try again in " + seconds + " s."
				: "Too many requests (HTTP 429). Try again in a minute.");
		}
		if (code >= 500 && code <= 599)
		{
			return error(Outcome.SERVER_ERROR,
				"The server had an error (HTTP " + code + "). Check the server log.");
		}
		if (code >= 300 && code <= 399)
		{
			return error(Outcome.UNEXPECTED_RESPONSE, location != null
				? "The server redirected (HTTP " + code + ") to " + location + ". Use that address as the server URL."
				: "The server redirected (HTTP " + code + "). Check the server URL.");
		}
		VerifyResponse response = code == 200 ? VerifyResponse.parse(gson, body) : null;
		if (response == null)
		{
			return error(Outcome.UNEXPECTED_RESPONSE, code == 200
				? "The server answered, but not like a " + Branding.DISPLAY_NAME + " server. Check the server URL."
				: "Unexpected response from the server (HTTP " + code + "). Check the server URL.");
		}
		return ok(response, requestStarted, responseAt);
	}

	private static VerifyResult ok(VerifyResponse response, Instant requestStarted, Instant responseAt)
	{
		List<String> warnings = new ArrayList<>();

		// Compare with the local clock at the midpoint of the request.
		Instant midpoint = requestStarted.plus(Duration.between(requestStarted, responseAt).dividedBy(2));
		Duration skew = Duration.between(midpoint, response.getServerTime());
		if (skew.abs().compareTo(MAX_CLOCK_SKEW) > 0)
		{
			warnings.add("Your computer's clock is " + formatDuration(skew.abs())
				+ (skew.isNegative() ? " ahead of" : " behind")
				+ " the server's. Turn on automatic time sync (NTP) on both machines, or sessions may be closed too early.");
		}

		SemVer required = SemVer.parse(Contract.REQUIRED_VERSION);
		SemVer server = response.getContractVersion();
		boolean tooOld = required != null && server.compareTo(required) < 0;
		if (tooOld)
		{
			warnings.add("The server is too old (contract " + server + ", this plugin needs " + required
				+ "). Update the server. Events are kept and not sent until then.");
		}
		else if (required != null && server.getMajor() > required.getMajor())
		{
			warnings.add("The server uses a newer contract (" + server + ") than this plugin supports ("
				+ required + "). Update the plugin.");
		}

		String message = "Connected as key \"" + response.getKeyName() + "\". Server " + response.getServerVersion()
			+ ", contract " + server + ".";
		return new VerifyResult(Outcome.OK, message, response, skew, tooOld, warnings);
	}

	/** {@code Retry-After} in seconds, or null if missing or not a number of seconds. */
	@Nullable
	static Long parseRetryAfter(@Nullable String value)
	{
		if (value == null)
		{
			return null;
		}
		try
		{
			long seconds = Long.parseLong(value.trim());
			return seconds >= 0 ? seconds : null;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	static String formatDuration(Duration d)
	{
		long total = d.getSeconds();
		long hours = total / 3600;
		long minutes = (total % 3600) / 60;
		long seconds = total % 60;
		if (hours > 0)
		{
			return hours + " h " + minutes + " min";
		}
		if (minutes > 0)
		{
			return minutes + " min " + seconds + " s";
		}
		return seconds + " s";
	}

	public Outcome getOutcome()
	{
		return outcome;
	}

	public boolean isOk()
	{
		return outcome == Outcome.OK;
	}

	public String getMessage()
	{
		return message;
	}

	@Nullable
	public VerifyResponse getResponse()
	{
		return response;
	}

	/** Server time minus local time (positive: the local clock is behind). Null unless OK. */
	@Nullable
	public Duration getClockSkew()
	{
		return clockSkew;
	}

	/** True when the server's contract is older than {@link Contract#REQUIRED_VERSION}. */
	public boolean isContractTooOld()
	{
		return contractTooOld;
	}

	public List<String> getWarnings()
	{
		return warnings;
	}
}
