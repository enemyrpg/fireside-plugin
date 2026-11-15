package com.fireside.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * {@code POST /api/v1/ingest/icons/wanted} and {@code POST /api/v1/ingest/icons}.
 * Requests run on OkHttp's thread pool ({@code enqueue}); the callback is called
 * on an OkHttp thread, never for a cancelled call. Never logs the API key, the
 * request headers or the request body.
 */
@Slf4j
@Singleton
public class IconClient
{
	/** {@code MAX_ICON_IDS} on the server. */
	public static final int MAX_WANTED_IDS = 500;
	/** {@code MAX_ICONS_PER_REQUEST} on the server. */
	public static final int MAX_ICONS_PER_REQUEST = 100;
	/** Under the server's 1 MiB body limit, with room to spare. */
	public static final int MAX_UPLOAD_BYTES = 900_000;

	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
	static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
	private static final long MAX_BODY_BYTES = 256 * 1024;
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/** One icon to upload: the item ID exactly as in the events, and the PNG bytes. */
	@Value
	public static class Icon
	{
		int itemId;
		byte[] png;

		/** Size of this icon in the request body (base64 plus JSON around it). */
		public int jsonSize()
		{
			return 4 * ((png.length + 2) / 3) + 32;
		}
	}

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	public IconClient(OkHttpClient okHttpClient, Gson gson)
	{
		this.http = okHttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.readTimeout(READ_TIMEOUT)
			.writeTimeout(READ_TIMEOUT)
			.callTimeout(CALL_TIMEOUT)
			// As in IngestClient: the key is never sent anywhere but the configured URL.
			.followRedirects(false)
			.followSslRedirects(false)
			.build();
		this.gson = gson;
	}

	/** {@code {"itemIds":[...]}}. */
	public static String wantedBody(Collection<Integer> itemIds)
	{
		JsonArray ids = new JsonArray();
		for (Integer id : itemIds)
		{
			ids.add(id);
		}
		JsonObject body = new JsonObject();
		body.add("itemIds", ids);
		return body.toString();
	}

	/** {@code {"icons":[{"itemId":..,"png":"<base64>"}]}}. */
	public static String uploadBody(List<Icon> icons)
	{
		JsonArray array = new JsonArray();
		for (Icon icon : icons)
		{
			JsonObject o = new JsonObject();
			o.addProperty("itemId", icon.getItemId());
			o.addProperty("png", Base64.getEncoder().encodeToString(icon.getPng()));
			array.add(o);
		}
		JsonObject body = new JsonObject();
		body.add("icons", array);
		return body.toString();
	}

	/** Asks which of the IDs (at most {@link #MAX_WANTED_IDS}) the server has no icon for. */
	public Call wanted(ServerUrl url, String apiKey, Collection<Integer> itemIds, Consumer<IconResult> callback)
	{
		return post(url.iconsWantedUrl(), apiKey, wantedBody(itemIds), "wanted",
			(code, retryAfter, body) -> IconResult.fromWanted(code, retryAfter, body, gson), callback);
	}

	/** Uploads at most {@link #MAX_ICONS_PER_REQUEST} icons. */
	public Call upload(ServerUrl url, String apiKey, List<Icon> icons, Consumer<IconResult> callback)
	{
		return post(url.iconsUrl(), apiKey, uploadBody(icons), "upload",
			(code, retryAfter, body) -> IconResult.fromUpload(code, retryAfter, body, gson), callback);
	}

	private interface Parser
	{
		IconResult parse(int code, @Nullable String retryAfter, @Nullable String body);
	}

	private Call post(HttpUrl target, String apiKey, String body, String what, Parser parser,
		Consumer<IconResult> callback)
	{
		Request request = new Request.Builder()
			.url(target)
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
				log.debug("Icon {} failed: {}", what, e.getClass().getSimpleName());
				callback.accept(IconResult.failed(null));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					IconResult result = parser.parse(r.code(), r.header("Retry-After"), readBody(r));
					log.debug("Icon {}: HTTP {}, {}", what, r.code(), result.getKind());
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
