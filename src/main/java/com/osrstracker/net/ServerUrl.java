package com.osrstracker.net;

import java.util.Objects;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;

/**
 * The server's base URL from the config, e.g. {@code http://127.0.0.1:3000}.
 * A path prefix is allowed (for a reverse proxy); API paths are added to it.
 */
public final class ServerUrl
{
	private static final Pattern IPV4_LOOPBACK = Pattern.compile("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");

	private final HttpUrl base;

	private ServerUrl(HttpUrl base)
	{
		this.base = base;
	}

	/**
	 * Parses the configured URL. Returns {@code null} if it is blank or not a
	 * plain http(s) base URL (no user info, query or fragment).
	 */
	@Nullable
	public static ServerUrl parse(@Nullable String raw)
	{
		if (raw == null || raw.trim().isEmpty())
		{
			return null;
		}
		HttpUrl url = HttpUrl.parse(raw.trim());
		if (url == null
			|| !url.encodedUsername().isEmpty()
			|| !url.encodedPassword().isEmpty()
			|| url.encodedQuery() != null
			|| url.encodedFragment() != null)
		{
			return null;
		}

		// Normalize the path so it always ends with exactly one "/".
		HttpUrl.Builder builder = url.newBuilder().encodedPath("/");
		for (String segment : url.encodedPathSegments())
		{
			if (!segment.isEmpty())
			{
				builder.addEncodedPathSegment(segment);
			}
		}
		HttpUrl normalized = builder.build();
		if (!normalized.encodedPath().endsWith("/"))
		{
			normalized = normalized.newBuilder().addEncodedPathSegment("").build();
		}
		return new ServerUrl(normalized);
	}

	/** {@code GET /api/v1/ingest/verify} on this server. */
	public HttpUrl verifyUrl()
	{
		return api("ingest/verify");
	}

	/** {@code POST /api/v1/ingest} on this server. */
	public HttpUrl ingestUrl()
	{
		return api("ingest");
	}

	/** The dashboard, served from the server's base URL. */
	public HttpUrl dashboardUrl()
	{
		return base;
	}

	public String host()
	{
		return base.host();
	}

	/**
	 * True when the API key would travel unencrypted to another machine:
	 * plain {@code http} to a host that is not loopback. Literal check only
	 * (no DNS lookup).
	 */
	public boolean isInsecure()
	{
		return !base.isHttps() && !isLoopback(base.host());
	}

	static boolean isLoopback(String host)
	{
		String h = host.toLowerCase();
		return h.equals("localhost")
			|| h.endsWith(".localhost")
			|| h.equals("::1")
			|| IPV4_LOOPBACK.matcher(h).matches();
	}

	private HttpUrl api(String path)
	{
		// The base path always ends with "/", so a relative path is appended to it.
		return Objects.requireNonNull(base.resolve("api/v1/" + path));
	}

	@Override
	public String toString()
	{
		return base.toString();
	}
}
