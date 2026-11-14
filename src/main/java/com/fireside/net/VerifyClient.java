package com.fireside.net;

import com.google.gson.Gson;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Calls {@code GET /api/v1/ingest/verify} for "Test connection".
 * <p>
 * The request runs on OkHttp's thread pool ({@code enqueue}), never on the
 * client thread. The callback runs on an OkHttp thread, or directly on the
 * calling thread when no request is made (missing or invalid config).
 * Never logs the API key or the request headers.
 */
@Slf4j
@Singleton
public class VerifyClient
{
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
	/** Enough for the verify body; anything bigger is not our server. */
	private static final long MAX_BODY_BYTES = 64 * 1024;

	private final OkHttpClient http;
	private final Gson gson;
	private final Clock clock;

	@Inject
	public VerifyClient(OkHttpClient okHttpClient, Gson gson)
	{
		this(okHttpClient, gson, Clock.systemUTC(), CONNECT_TIMEOUT, READ_TIMEOUT);
	}

	VerifyClient(OkHttpClient okHttpClient, Gson gson, Clock clock, Duration connectTimeout, Duration readTimeout)
	{
		this.http = okHttpClient.newBuilder()
			.connectTimeout(connectTimeout)
			.readTimeout(readTimeout)
			.callTimeout(connectTimeout.plus(readTimeout).plus(readTimeout))
			// A redirect is reported to the user instead of followed, so the key
			// is never sent anywhere other than the configured URL.
			.followRedirects(false)
			.followSslRedirects(false)
			.build();
		this.gson = gson;
		this.clock = clock;
	}

	/**
	 * Starts a connection test.
	 *
	 * @return the running call (so it can be cancelled), or {@code null} if no
	 * request was made and the callback has already been called
	 */
	@Nullable
	public Call verify(@Nullable String serverUrl, @Nullable String apiKey, Consumer<VerifyResult> callback)
	{
		String key = apiKey == null ? "" : apiKey.trim();
		if (serverUrl == null || serverUrl.trim().isEmpty() || key.isEmpty())
		{
			callback.accept(VerifyResult.notConfigured());
			return null;
		}
		ServerUrl url = ServerUrl.parse(serverUrl);
		if (url == null)
		{
			callback.accept(VerifyResult.invalidUrl());
			return null;
		}
		// Checked here because OkHttp's header validation puts the value in its exception message.
		if (!isValidKey(key))
		{
			callback.accept(VerifyResult.invalidKey());
			return null;
		}

		Request request = new Request.Builder()
			.url(url.verifyUrl())
			.header("Authorization", "Bearer " + key)
			.header("Accept", "application/json")
			.get()
			.build();

		Instant started = clock.instant();
		Call call = http.newCall(request);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				if (call.isCanceled())
				{
					return;
				}
				VerifyResult result = VerifyResult.fromException(e, url);
				log.debug("Connection test failed: {} ({})", result.getOutcome(), e.getClass().getSimpleName());
				callback.accept(result);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					Instant received = clock.instant();
					String body = readBody(r);
					VerifyResult result = VerifyResult.fromResponse(r.code(), r.header("Retry-After"),
						r.header("Location"), body, gson, started, received);
					log.debug("Connection test finished: HTTP {}, {}", r.code(), result.getOutcome());
					callback.accept(result);
				}
			}
		});
		return call;
	}

	/** Printable ASCII without spaces, as in a bearer token. */
	public static boolean isValidKey(String key)
	{
		for (int i = 0; i < key.length(); i++)
		{
			char c = key.charAt(i);
			if (c <= 0x20 || c >= 0x7f)
			{
				return false;
			}
		}
		return !key.isEmpty();
	}

	@Nullable
	private static String readBody(Response response)
	{
		ResponseBody body = response.body();
		if (body == null)
		{
			return null;
		}
		try
		{
			if (body.contentLength() > MAX_BODY_BYTES)
			{
				return null;
			}
			return response.peekBody(MAX_BODY_BYTES).string();
		}
		catch (IOException e)
		{
			return null;
		}
	}
}
