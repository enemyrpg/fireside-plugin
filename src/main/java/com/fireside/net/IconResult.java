package com.fireside.net;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * The answer to {@code POST /ingest/icons/wanted} or {@code POST /ingest/icons}.
 * Icons never affect event sending, so there are fewer kinds than for
 * {@link IngestResult}.
 */
@Value
public class IconResult
{
	public enum Kind
	{
		/** 200 with a body that matches the contract. */
		OK,
		/** 429: wait at least {@link #getRetryAfter()}. */
		RATE_LIMITED,
		/** 401: the key is wrong; the event sender reports it. */
		UNAUTHORIZED,
		/** 404: no icon endpoints (an older server or a wrong path). */
		NOT_FOUND,
		/** 5xx, network errors, and anything unexpected: try again later. */
		FAILED,
	}

	Kind kind;
	@Nullable
	Integer status;
	@Nullable
	Duration retryAfter;
	/** {@code wanted}: the IDs without an icon on the server. */
	List<Integer> missing;
	/** {@code icons}: stored and unchanged IDs. */
	List<Integer> accepted;
	/** {@code icons}: IDs the server rejected (never retried this session). */
	List<Integer> rejected;

	/** Request position of each rejection, for a rejection whose {@code itemId} is null. */
	List<Integer> rejectedIndexes;

	static IconResult failed(@Nullable Integer status)
	{
		return of(Kind.FAILED, status, null);
	}

	private static IconResult of(Kind kind, @Nullable Integer status, @Nullable Duration retryAfter)
	{
		return new IconResult(kind, status, retryAfter, Collections.emptyList(), Collections.emptyList(),
			Collections.emptyList(), Collections.emptyList());
	}

	private static final class WantedBody
	{
		List<Integer> missing;
	}

	private static final class IconsBody
	{
		List<Integer> stored;
		List<Integer> unchanged;
		List<Rejection> rejected;
	}

	private static final class Rejection
	{
		Integer index;
		Integer itemId;
	}

	/** Status codes other than 200, shared by both endpoints. */
	@Nullable
	private static IconResult fromStatus(int code, @Nullable String retryAfter)
	{
		if (code == 200)
		{
			return null;
		}
		if (code == 429)
		{
			Long seconds = VerifyResult.parseRetryAfter(retryAfter);
			return of(Kind.RATE_LIMITED, code, seconds != null ? Duration.ofSeconds(seconds) : null);
		}
		if (code == 401)
		{
			return of(Kind.UNAUTHORIZED, code, null);
		}
		if (code == 404)
		{
			return of(Kind.NOT_FOUND, code, null);
		}
		Long seconds = VerifyResult.parseRetryAfter(retryAfter);
		return of(Kind.FAILED, code, seconds != null ? Duration.ofSeconds(seconds) : null);
	}

	static IconResult fromWanted(int code, @Nullable String retryAfter, @Nullable String body, Gson gson)
	{
		IconResult status = fromStatus(code, retryAfter);
		if (status != null)
		{
			return status;
		}
		try
		{
			WantedBody parsed = body == null ? null : gson.fromJson(body, WantedBody.class);
			if (parsed == null || parsed.missing == null || parsed.missing.contains(null))
			{
				return failed(code);
			}
			return new IconResult(Kind.OK, code, null, Collections.unmodifiableList(parsed.missing),
				Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
		}
		catch (JsonParseException | IllegalStateException | NumberFormatException e)
		{
			return failed(code);
		}
	}

	static IconResult fromUpload(int code, @Nullable String retryAfter, @Nullable String body, Gson gson)
	{
		IconResult status = fromStatus(code, retryAfter);
		if (status != null)
		{
			return status;
		}
		try
		{
			IconsBody parsed = body == null ? null : gson.fromJson(body, IconsBody.class);
			if (parsed == null || parsed.stored == null || parsed.unchanged == null || parsed.rejected == null
				|| parsed.stored.contains(null) || parsed.unchanged.contains(null))
			{
				return failed(code);
			}
			List<Integer> accepted = new ArrayList<>(parsed.stored);
			accepted.addAll(parsed.unchanged);
			List<Integer> rejected = new ArrayList<>();
			List<Integer> rejectedIndexes = new ArrayList<>();
			for (Rejection r : parsed.rejected)
			{
				if (r == null)
				{
					continue;
				}
				if (r.itemId != null)
				{
					rejected.add(r.itemId);
				}
				if (r.index != null)
				{
					rejectedIndexes.add(r.index);
				}
			}
			return new IconResult(Kind.OK, code, null, Collections.emptyList(),
				Collections.unmodifiableList(accepted), Collections.unmodifiableList(rejected),
				Collections.unmodifiableList(rejectedIndexes));
		}
		catch (JsonParseException | IllegalStateException | NumberFormatException e)
		{
			return failed(code);
		}
	}
}
