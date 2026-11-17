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
	/** {@code wanted}: the keys without an icon on the server. */
	List<IconKey> missing;
	/** {@code icons}: stored and unchanged keys, as they were sent. */
	List<IconKey> accepted;
	/** {@code icons}: keys the server rejected and could name (never retried this session). */
	List<IconKey> rejected;

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
		/** Only in the answer when the request had skills or bosses (contract 1.29.0). */
		List<String> missingSkills;
		List<String> missingBosses;
	}

	private static final class IconsBody
	{
		List<Integer> stored;
		List<Integer> unchanged;
		List<Rejection> rejected;
		/** Only in the answer when the request had a skill or boss icon (contract 1.29.0). */
		List<Named> storedNamed;
		List<Named> unchangedNamed;
	}

	private static final class Named
	{
		String kind;
		String key;
	}

	private static final class Rejection
	{
		Integer index;
		Integer itemId;
		String kind;
		String key;
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
			List<IconKey> missing = new ArrayList<>();
			for (Integer id : parsed.missing)
			{
				missing.add(IconKey.item(id));
			}
			if (parsed.missingSkills != null)
			{
				for (String name : parsed.missingSkills)
				{
					if (name != null)
					{
						missing.add(IconKey.skill(name));
					}
				}
			}
			if (parsed.missingBosses != null)
			{
				for (String name : parsed.missingBosses)
				{
					if (name != null)
					{
						missing.add(IconKey.boss(name));
					}
				}
			}
			return new IconResult(Kind.OK, code, null, Collections.unmodifiableList(missing),
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
			List<IconKey> accepted = new ArrayList<>();
			for (Integer id : parsed.stored)
			{
				accepted.add(IconKey.item(id));
			}
			for (Integer id : parsed.unchanged)
			{
				accepted.add(IconKey.item(id));
			}
			addNamed(accepted, parsed.storedNamed);
			addNamed(accepted, parsed.unchangedNamed);
			List<IconKey> rejected = new ArrayList<>();
			List<Integer> rejectedIndexes = new ArrayList<>();
			for (Rejection r : parsed.rejected)
			{
				if (r == null)
				{
					continue;
				}
				IconKey named = IconKey.named(r.kind, r.key);
				if (r.itemId != null)
				{
					rejected.add(IconKey.item(r.itemId));
				}
				else if (named != null)
				{
					rejected.add(named);
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

	private static void addNamed(List<IconKey> keys, @Nullable List<Named> named)
	{
		if (named == null)
		{
			return;
		}
		for (Named n : named)
		{
			IconKey key = n == null ? null : IconKey.named(n.kind, n.key);
			if (key != null)
			{
				keys.add(key);
			}
		}
	}
}
