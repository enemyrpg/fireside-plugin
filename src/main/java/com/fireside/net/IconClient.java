package com.fireside.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
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
	/** {@code MAX_ICON_IDS} on the server: keys in one {@code wanted} request, all kinds together. */
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

	/** One icon to upload: its key (an item ID exactly as in the events, or a skill or boss name) and the PNG bytes. */
	@Value
	public static class Icon
	{
		IconKey key;
		byte[] png;

		public Icon(IconKey key, byte[] png)
		{
			this.key = key;
			this.png = png;
		}

		/** An item icon. */
		public Icon(int itemId, byte[] png)
		{
			this(IconKey.item(itemId), png);
		}

		/** Size of this icon in the request body (base64 plus JSON around it), an upper bound. */
		public int jsonSize()
		{
			int base64 = 4 * ((png.length + 2) / 3);
			// A name: every character may be escaped (six bytes at most), plus "kind" and "key".
			return key.getName() == null ? base64 + 32 : base64 + 48 + 6 * key.getName().length();
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
		return wantedBody(itemIds, Collections.emptyList(), Collections.emptyList());
	}

	/**
	 * {@code {"itemIds":[...],"skills":[...],"bosses":[...]}}. An empty list is
	 * left out (the server rejects one), so a request with only item IDs is the
	 * same as before 1.29.0. At least one list must have an element.
	 */
	public static String wantedBody(Collection<Integer> itemIds, Collection<String> skills,
		Collection<String> bosses)
	{
		JsonObject body = new JsonObject();
		if (!itemIds.isEmpty())
		{
			JsonArray ids = new JsonArray();
			for (Integer id : itemIds)
			{
				ids.add(id);
			}
			body.add("itemIds", ids);
		}
		addNames(body, "skills", skills);
		addNames(body, "bosses", bosses);
		return body.toString();
	}

	private static void addNames(JsonObject body, String field, Collection<String> names)
	{
		if (!names.isEmpty())
		{
			JsonArray array = new JsonArray();
			for (String name : names)
			{
				array.add(name);
			}
			body.add(field, array);
		}
	}

	/** {@link #wantedBody(Collection, Collection, Collection)} for keys of any kind. */
	public static String wantedBodyFor(Collection<IconKey> keys)
	{
		List<Integer> itemIds = new ArrayList<>();
		List<String> skills = new ArrayList<>();
		List<String> bosses = new ArrayList<>();
		for (IconKey key : keys)
		{
			switch (key.getKind())
			{
				case ITEM:
					itemIds.add(key.getItemId());
					break;
				case SKILL:
					skills.add(key.getName());
					break;
				default:
					bosses.add(key.getName());
					break;
			}
		}
		return wantedBody(itemIds, skills, bosses);
	}

	/**
	 * {@code {"icons":[{"itemId":..,"png":"<base64>"}]}}; a skill or boss icon
	 * is {@code {"kind":"SKILL"|"BOSS","key":..,"png":..}}.
	 */
	public static String uploadBody(List<Icon> icons)
	{
		JsonArray array = new JsonArray();
		for (Icon icon : icons)
		{
			JsonObject o = new JsonObject();
			IconKey key = icon.getKey();
			if (key.getKind() == IconKey.Kind.ITEM)
			{
				o.addProperty("itemId", key.getItemId());
			}
			else
			{
				o.addProperty("kind", key.getKind().name());
				o.addProperty("key", key.getName());
			}
			o.addProperty("png", Base64.getEncoder().encodeToString(icon.getPng()));
			array.add(o);
		}
		JsonObject body = new JsonObject();
		body.add("icons", array);
		return body.toString();
	}

	/**
	 * Asks which of the keys (at most {@link #MAX_WANTED_IDS} in total) the server
	 * has no icon for. Skill and boss keys need a server with contract 1.29.0.
	 */
	public Call wanted(ServerUrl url, String apiKey, Collection<IconKey> keys, Consumer<IconResult> callback)
	{
		return post(url.iconsWantedUrl(), apiKey, wantedBodyFor(keys), "wanted",
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
