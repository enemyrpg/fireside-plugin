package com.fireside.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.time.Duration;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * Outcome of one {@code POST /api/v1/ingest}, classified as in the status
 * table in 03 and the M7 decisions in 07. Messages are for the user and never
 * contain the API key.
 */
@Value
public class IngestResult
{
	public enum Kind
	{
		/** 200 with a valid body. */
		OK,
		/** 400: the whole batch is bad. It goes to the error log. */
		BAD_REQUEST,
		/** 401: invalid or revoked key. Sending stops. */
		UNAUTHORIZED,
		/** 404: wrong URL. Sending stops. */
		NOT_FOUND,
		/** 3xx: not followed (the key is only sent to the configured URL). Sending stops. */
		REDIRECT,
		/** 413: batch too big. Split and try again. */
		TOO_LARGE,
		/** 429, 5xx, network errors and anything unexpected: try again later. */
		TEMPORARY,
	}

	/** Longest server error message kept for the error log. */
	static final int MAX_ERROR_LENGTH = 2000;

	Kind kind;
	/** HTTP status, or null for a network error. */
	@Nullable
	Integer httpStatus;
	String message;
	@Nullable
	IngestResponse response;
	/** From {@code Retry-After}, if the server sent one. */
	@Nullable
	Duration retryAfter;

	private static IngestResult of(Kind kind, @Nullable Integer status, String message)
	{
		return new IngestResult(kind, status, message, null, null);
	}

	static IngestResult fromException(IOException e, ServerUrl url)
	{
		return of(Kind.TEMPORARY, null, VerifyResult.fromException(e, url).getMessage());
	}

	static IngestResult fromResponse(int code, @Nullable String retryAfter, @Nullable String location,
		@Nullable String body, Gson gson)
	{
		if (code == 200)
		{
			IngestResponse response = IngestResponse.parse(gson, body);
			if (response == null)
			{
				return of(Kind.TEMPORARY, code, "The server answered with something unexpected (HTTP 200).");
			}
			return new IngestResult(Kind.OK, code, "OK", response, null);
		}
		if (code == 400)
		{
			String error = errorMessage(gson, body);
			return of(Kind.BAD_REQUEST, code, error != null ? error : "Bad request (HTTP 400)");
		}
		if (code == 401)
		{
			return of(Kind.UNAUTHORIZED, code, "The server rejected the API key (invalid or revoked).");
		}
		if (code == 404)
		{
			return of(Kind.NOT_FOUND, code, "The server answered 404 Not Found, so the server URL is probably wrong.");
		}
		if (code >= 300 && code <= 399)
		{
			return of(Kind.REDIRECT, code, location != null
				? "The server redirected (HTTP " + code + ") to " + location + ". Use that address as the server URL."
				: "The server redirected (HTTP " + code + "). Check the server URL.");
		}
		if (code == 413)
		{
			return of(Kind.TOO_LARGE, code, "The batch was too large for the server (HTTP 413).");
		}
		if (code == 429)
		{
			Long seconds = VerifyResult.parseRetryAfter(retryAfter);
			return new IngestResult(Kind.TEMPORARY, code, "Too many requests (HTTP 429).", null,
				seconds != null ? Duration.ofSeconds(seconds) : null);
		}
		if (code >= 500 && code <= 599)
		{
			Long seconds = VerifyResult.parseRetryAfter(retryAfter);
			return new IngestResult(Kind.TEMPORARY, code, "The server had an error (HTTP " + code + ").", null,
				seconds != null ? Duration.ofSeconds(seconds) : null);
		}
		return of(Kind.TEMPORARY, code, "Unexpected response from the server (HTTP " + code + ").");
	}

	/**
	 * The message from the contract's error format
	 * ({@code {"error":{"code","message","details":[{"path","message"}]}}}),
	 * with the details appended.
	 */
	@Nullable
	static String errorMessage(Gson gson, @Nullable String body)
	{
		if (body == null)
		{
			return null;
		}
		try
		{
			JsonElement root = new JsonParser().parse(body);
			if (!root.isJsonObject() || !root.getAsJsonObject().has("error")
				|| !root.getAsJsonObject().get("error").isJsonObject())
			{
				return null;
			}
			JsonObject error = root.getAsJsonObject().getAsJsonObject("error");
			StringBuilder sb = new StringBuilder();
			if (error.has("message") && error.get("message").isJsonPrimitive())
			{
				sb.append(error.get("message").getAsString());
			}
			if (error.has("details") && error.get("details").isJsonArray())
			{
				JsonArray details = error.getAsJsonArray("details");
				for (JsonElement detail : details)
				{
					if (!detail.isJsonObject())
					{
						continue;
					}
					JsonObject d = detail.getAsJsonObject();
					sb.append(sb.length() > 0 ? "; " : "");
					if (d.has("path") && d.get("path").isJsonPrimitive() && !d.get("path").getAsString().isEmpty())
					{
						sb.append(d.get("path").getAsString()).append(": ");
					}
					if (d.has("message") && d.get("message").isJsonPrimitive())
					{
						sb.append(d.get("message").getAsString());
					}
				}
			}
			if (sb.length() == 0)
			{
				return null;
			}
			return sb.length() > MAX_ERROR_LENGTH ? sb.substring(0, MAX_ERROR_LENGTH) + "…" : sb.toString();
		}
		catch (JsonParseException | IllegalStateException | UnsupportedOperationException e)
		{
			return null;
		}
	}
}
