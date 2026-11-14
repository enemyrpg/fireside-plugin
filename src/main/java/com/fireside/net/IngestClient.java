package com.fireside.net;

import com.google.gson.Gson;
import com.fireside.queue.EventQueue;
import com.fireside.queue.QueuedEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Sends one batch with {@code POST /api/v1/ingest}. The request runs on
 * OkHttp's thread pool ({@code enqueue}) and the callback is called on an
 * OkHttp thread, never for a cancelled call. Never logs the API key, the
 * request headers or event contents.
 */
@Slf4j
@Singleton
public class IngestClient
{
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	/** The server stores and processes the batch before it answers. */
	static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
	static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);
	static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
	/** 500 rejections with long messages fit easily; anything bigger is not our server. */
	private static final long MAX_BODY_BYTES = 4L * 1024 * 1024;
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	public IngestClient(OkHttpClient okHttpClient, Gson gson)
	{
		this(okHttpClient, gson, CONNECT_TIMEOUT, READ_TIMEOUT, CALL_TIMEOUT);
	}

	IngestClient(OkHttpClient okHttpClient, Gson gson, Duration connectTimeout, Duration readTimeout,
		Duration callTimeout)
	{
		this.http = okHttpClient.newBuilder()
			.connectTimeout(connectTimeout)
			.readTimeout(readTimeout)
			.writeTimeout(WRITE_TIMEOUT)
			.callTimeout(callTimeout)
			// As in VerifyClient: the key is never sent anywhere but the configured URL.
			.followRedirects(false)
			.followSslRedirects(false)
			.build();
		this.gson = gson;
	}

	/** {@code {"events":[...]}} from the queued JSON lines, without parsing them again. */
	public static String body(List<QueuedEvent> batch)
	{
		StringBuilder sb = new StringBuilder(EventQueue.bodyOverhead() + batch.size() * 256);
		sb.append("{\"events\":[");
		for (int i = 0; i < batch.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append(batch.get(i).getJson());
		}
		return sb.append("]}").toString();
	}

	/**
	 * Starts sending a batch. The URL and key must already be checked
	 * ({@link ServerUrl#parse}, {@link VerifyClient#isValidKey}).
	 *
	 * @return the running call, so it can be cancelled
	 */
	public Call send(ServerUrl url, String apiKey, String body, Consumer<IngestResult> callback)
	{
		Request request = new Request.Builder()
			.url(url.ingestUrl())
			.header("Authorization", "Bearer " + apiKey)
			.header("Accept", "application/json")
			.post(RequestBody.create(JSON, body.getBytes(StandardCharsets.UTF_8)))
			.build();

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
				log.debug("Sending events failed: {}", e.getClass().getSimpleName());
				callback.accept(IngestResult.fromException(e, url));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					IngestResult result = IngestResult.fromResponse(r.code(), r.header("Retry-After"),
						r.header("Location"), readBody(r), gson);
					log.debug("Sent events: HTTP {}, {}", r.code(), result.getKind());
					callback.accept(result);
				}
			}
		});
		return call;
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
