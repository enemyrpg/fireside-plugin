package com.fireside.send;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.fireside.Contract;
import com.fireside.event.Timestamps;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A stand-in for the server: answers {@code verify}, and {@code POST /ingest}
 * either from a script or by accepting every event (duplicates as
 * {@code duplicates}, like the real server). Records what it received.
 */
class FakeIngestServer extends Dispatcher
{
	static final String KEY = "ost_test_0123456789abcdef";

	/** One received batch. */
	static final class Batch
	{
		final List<String> eventIds;
		final String body;
		final String authorization;
		final String contentType;
		final long receivedAtNanos;

		Batch(List<String> eventIds, String body, String authorization, String contentType, long receivedAtNanos)
		{
			this.eventIds = eventIds;
			this.body = body;
			this.authorization = authorization;
			this.contentType = contentType;
			this.receivedAtNanos = receivedAtNanos;
		}
	}

	final MockWebServer server = new MockWebServer();
	final List<Batch> batches = new CopyOnWriteArrayList<>();
	/** IDs the server has stored, in order (each once). */
	final List<String> stored = Collections.synchronizedList(new ArrayList<>());
	final Queue<Function<Batch, MockResponse>> script = new ConcurrentLinkedQueue<>();
	volatile String contractVersion = Contract.REQUIRED_VERSION;
	volatile int verifyCalls;
	volatile MockResponse verifyOverride;
	/** Status for both icon endpoints (404 = a server without them). */
	volatile int iconStatus = 404;
	final List<String> iconCalls = new CopyOnWriteArrayList<>();

	FakeIngestServer()
	{
		server.setDispatcher(this);
	}

	FakeIngestServer start() throws IOException
	{
		server.start();
		return this;
	}

	FakeIngestServer start(int port) throws IOException
	{
		server.start(port);
		return this;
	}

	String url()
	{
		return server.url("/").toString();
	}

	@Override
	public MockResponse dispatch(RecordedRequest request)
	{
		String path = request.getPath();
		if (!("Bearer " + KEY).equals(request.getHeader("Authorization")))
		{
			return new MockResponse().setResponseCode(401);
		}
		if ("GET".equals(request.getMethod()) && "/api/v1/ingest/verify".equals(path))
		{
			verifyCalls++;
			if (verifyOverride != null)
			{
				return verifyOverride;
			}
			return json(200, "{\"ok\":true,\"keyName\":\"test\",\"serverVersion\":\"0.1.0\",\"contractVersion\":\""
				+ contractVersion + "\",\"serverTime\":\"" + Timestamps.format(Instant.now()) + "\"}");
		}
		if ("POST".equals(request.getMethod()) && "/api/v1/ingest".equals(path))
		{
			String body = request.getBody().readUtf8();
			List<String> ids = new ArrayList<>();
			for (JsonElement e : new JsonParser().parse(body).getAsJsonObject().getAsJsonArray("events"))
			{
				ids.add(e.getAsJsonObject().get("eventId").getAsString());
			}
			Batch batch = new Batch(ids, body, request.getHeader("Authorization"), request.getHeader("Content-Type"),
				System.nanoTime());
			batches.add(batch);
			Function<Batch, MockResponse> next = script.poll();
			return next != null ? next.apply(batch) : acceptAll(batch);
		}
		if ("POST".equals(request.getMethod()) && path != null && path.startsWith("/api/v1/ingest/icons"))
		{
			iconCalls.add(path);
			return json(iconStatus, "{\"error\":{\"code\":\"X\",\"message\":\"icon endpoint failed\"}}");
		}
		return new MockResponse().setResponseCode(404);
	}

	/** Stores new events and answers like the real server. */
	MockResponse acceptAll(Batch batch)
	{
		JsonArray accepted = new JsonArray();
		JsonArray duplicates = new JsonArray();
		synchronized (stored)
		{
			for (String id : batch.eventIds)
			{
				if (stored.contains(id))
				{
					duplicates.add(id);
				}
				else
				{
					stored.add(id);
					accepted.add(id);
				}
			}
		}
		JsonObject body = new JsonObject();
		body.add("accepted", accepted);
		body.add("duplicates", duplicates);
		body.add("rejected", new JsonArray());
		return json(200, body.toString());
	}

	static MockResponse json(int code, String body)
	{
		return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
	}

	void stop() throws IOException
	{
		server.shutdown();
	}
}
