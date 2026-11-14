package com.fireside.net;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * Body of {@code 200 POST /api/v1/ingest}
 * (schema {@code schema/api/ingest-response.json} in the contract package).
 * Every event in the batch is in exactly one of the three lists.
 */
public final class IngestResponse
{
	@Value
	public static class Rejection
	{
		/** Position in the batch. */
		int index;
		/** The ID as it was sent, or null if it was missing or not a string. */
		@Nullable
		String eventId;
		String error;
	}

	private final List<String> accepted;
	private final List<String> duplicates;
	private final List<Rejection> rejected;

	IngestResponse(List<String> accepted, List<String> duplicates, List<Rejection> rejected)
	{
		this.accepted = Collections.unmodifiableList(accepted);
		this.duplicates = Collections.unmodifiableList(duplicates);
		this.rejected = Collections.unmodifiableList(rejected);
	}

	/** Raw shape for Gson. */
	private static final class Body
	{
		List<String> accepted;
		List<String> duplicates;
		List<RejectionBody> rejected;
	}

	private static final class RejectionBody
	{
		Integer index;
		String eventId;
		String error;
	}

	/** Parses a response body. Returns {@code null} if it is not a valid response. */
	@Nullable
	public static IngestResponse parse(Gson gson, @Nullable String json)
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
		catch (JsonParseException | IllegalStateException | ClassCastException | NumberFormatException e)
		{
			return null;
		}
		if (body == null || body.accepted == null || body.duplicates == null || body.rejected == null
			|| body.accepted.contains(null) || body.duplicates.contains(null))
		{
			return null;
		}
		List<Rejection> rejected = new ArrayList<>();
		for (RejectionBody r : body.rejected)
		{
			if (r == null || r.index == null || r.index < 0 || r.error == null)
			{
				return null;
			}
			rejected.add(new Rejection(r.index, r.eventId, r.error));
		}
		return new IngestResponse(body.accepted, body.duplicates, rejected);
	}

	public List<String> getAccepted()
	{
		return accepted;
	}

	public List<String> getDuplicates()
	{
		return duplicates;
	}

	public List<Rejection> getRejected()
	{
		return rejected;
	}
}
