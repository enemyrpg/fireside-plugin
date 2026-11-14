package com.fireside.net;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Body of {@code 200 GET /api/v1/ingest/verify}
 * (schema {@code schema/api/ingest-verify-response.json} in the contract package).
 */
public final class VerifyResponse
{
	/** UTC with exactly three decimals and {@code Z}, as in the contract. */
	private static final Pattern TIMESTAMP = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$");

	private final String keyName;
	private final String serverVersion;
	private final SemVer contractVersion;
	private final Instant serverTime;

	private VerifyResponse(String keyName, String serverVersion, SemVer contractVersion, Instant serverTime)
	{
		this.keyName = keyName;
		this.serverVersion = serverVersion;
		this.contractVersion = contractVersion;
		this.serverTime = serverTime;
	}

	/** Raw shape for Gson. */
	private static final class Body
	{
		Boolean ok;
		String keyName;
		String serverVersion;
		String contractVersion;
		String serverTime;
	}

	/**
	 * Parses and checks a verify response body. Returns {@code null} if it is
	 * not a valid response. Unknown extra fields are ignored, so a newer server
	 * with more fields still works.
	 */
	@Nullable
	public static VerifyResponse parse(Gson gson, @Nullable String json)
	{
		if (json == null)
		{
			return null;
		}
		Body body;
		try
		{
			body = gson.fromJson(json, Body.class);
		}
		catch (JsonParseException | IllegalStateException | ClassCastException e)
		{
			return null;
		}
		if (body == null
			|| !Boolean.TRUE.equals(body.ok)
			|| body.keyName == null || body.keyName.isEmpty()
			|| SemVer.parse(body.serverVersion) == null)
		{
			return null;
		}
		SemVer contract = SemVer.parse(body.contractVersion);
		if (contract == null || body.serverTime == null || !TIMESTAMP.matcher(body.serverTime).matches())
		{
			return null;
		}
		try
		{
			return new VerifyResponse(body.keyName, body.serverVersion, contract, Instant.parse(body.serverTime));
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}

	public String getKeyName()
	{
		return keyName;
	}

	public String getServerVersion()
	{
		return serverVersion;
	}

	public SemVer getContractVersion()
	{
		return contractVersion;
	}

	public Instant getServerTime()
	{
		return serverTime;
	}
}
