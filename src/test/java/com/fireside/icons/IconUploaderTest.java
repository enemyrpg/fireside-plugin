package com.fireside.icons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.fireside.event.Event;
import com.fireside.event.Loot;
import com.fireside.event.LootItem;
import com.fireside.event.LootSource;
import com.fireside.event.UuidV7Generator;
import com.fireside.net.IconClient;
import com.fireside.net.SemVer;
import com.fireside.send.Backoff;
import com.fireside.send.SenderSettings;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The uploader against a mock server: batching, once per session, old servers and failures. */
public class IconUploaderTest
{
	private static final String KEY = "ost_test_0123456789abcdef";
	private static final String OTHER_KEY = "ost_test_fedcba9876543210";
	private static final Duration WAIT = Duration.ofSeconds(10);
	/** Long enough for a request the uploader would make (collect delay 50 ms). */
	private static final long QUIET_MS = 400;
	private static final IconUploader.Timing FAST = new IconUploader.Timing(Duration.ofMillis(50),
		Duration.ofMillis(50), Duration.ofMillis(10), Duration.ofSeconds(5));

	/** One request the server got. */
	private static final class Received
	{
		final String path;
		final String authorization;
		final List<Integer> ids;
		final long atNanos;

		Received(String path, String authorization, List<Integer> ids)
		{
			this.path = path;
			this.authorization = authorization;
			this.ids = ids;
			this.atNanos = System.nanoTime();
		}

		boolean isWanted()
		{
			return path.endsWith("/icons/wanted");
		}
	}

	/** Stores icons like the real server; a script can override the next answers. */
	private final class FakeIconServer extends Dispatcher
	{
		final MockWebServer server = new MockWebServer();
		final Set<Integer> stored = ConcurrentHashMap.newKeySet();
		final Set<Integer> reject = ConcurrentHashMap.newKeySet();
		final Queue<Function<Received, MockResponse>> script = new ConcurrentLinkedQueue<>();
		final LinkedBlockingQueue<Received> received = new LinkedBlockingQueue<>();

		@Override
		public MockResponse dispatch(RecordedRequest request)
		{
			String auth = request.getHeader("Authorization");
			if (!("Bearer " + KEY).equals(auth) && !("Bearer " + OTHER_KEY).equals(auth))
			{
				return new MockResponse().setResponseCode(401);
			}
			JsonObject body = new JsonParser().parse(request.getBody().readUtf8()).getAsJsonObject();
			String path = request.getPath();
			List<Integer> ids = new ArrayList<>();
			if ("/api/v1/ingest/icons/wanted".equals(path))
			{
				for (JsonElement e : body.getAsJsonArray("itemIds"))
				{
					ids.add(e.getAsInt());
				}
			}
			else if ("/api/v1/ingest/icons".equals(path))
			{
				for (JsonElement e : body.getAsJsonArray("icons"))
				{
					ids.add(e.getAsJsonObject().get("itemId").getAsInt());
				}
			}
			else
			{
				return new MockResponse().setResponseCode(404);
			}
			Received r = new Received(path, auth, ids);
			received.add(r);
			Function<Received, MockResponse> next = script.poll();
			if (next != null)
			{
				return next.apply(r);
			}
			return r.isWanted() ? answerWanted(ids) : answerUpload(ids);
		}

		MockResponse answerWanted(List<Integer> ids)
		{
			JsonArray missing = new JsonArray();
			for (int id : ids)
			{
				if (!stored.contains(id))
				{
					missing.add(id);
				}
			}
			JsonObject o = new JsonObject();
			o.add("missing", missing);
			return json(200, o.toString());
		}

		MockResponse answerUpload(List<Integer> ids)
		{
			JsonArray storedNow = new JsonArray();
			JsonArray rejected = new JsonArray();
			for (int i = 0; i < ids.size(); i++)
			{
				int id = ids.get(i);
				if (reject.contains(id))
				{
					JsonObject r = new JsonObject();
					r.addProperty("index", i);
					r.addProperty("itemId", id);
					r.addProperty("error", "png: Must be a base64-encoded PNG");
					rejected.add(r);
				}
				else
				{
					stored.add(id);
					storedNow.add(id);
				}
			}
			JsonObject o = new JsonObject();
			o.add("stored", storedNow);
			o.add("unchanged", new JsonArray());
			o.add("rejected", rejected);
			return json(200, o.toString());
		}
	}

	/** Loads at once (on the calling thread), except IDs that never load. */
	private static final class FakeImages implements IconImages
	{
		final Set<Integer> neverLoad = ConcurrentHashMap.newKeySet();
		final List<Integer> loads = Collections.synchronizedList(new ArrayList<>());

		@Override
		public void load(int itemId, Consumer<BufferedImage> onLoaded)
		{
			loads.add(itemId);
			if (!neverLoad.contains(itemId))
			{
				onLoaded.accept(PngEncoderTest.testImage(36, 32, itemId));
			}
		}
	}

	private final UuidV7Generator eventIds = new UuidV7Generator();
	private final FakeIconServer server = new FakeIconServer();
	private final FakeImages images = new FakeImages();
	private ScheduledThreadPoolExecutor executor;
	/** Game ticks, every few ms, unless a test drives them by hand. */
	private ScheduledThreadPoolExecutor ticker;
	private boolean autoTick = true;
	private IconUploader uploader;
	private SenderSettings settings;

	@Before
	public void setUp() throws Exception
	{
		server.server.setDispatcher(server);
		server.server.start();
		settings = new SenderSettings(true, server.server.url("/").toString(), KEY);
		executor = new ScheduledThreadPoolExecutor(1);
		executor.setRemoveOnCancelPolicy(true);
		ticker = new ScheduledThreadPoolExecutor(1);
	}

	@After
	public void tearDown() throws Exception
	{
		if (uploader != null)
		{
			uploader.shutdown();
		}
		ticker.shutdownNow();
		executor.shutdown();
		server.server.shutdown();
	}

	private IconUploader start(IconUploader.Timing timing, String contract)
	{
		uploader = new IconUploader(executor, new IconClient(new OkHttpClient(), RuneLiteAPI.GSON), images,
			new Backoff(Duration.ofMillis(40), Duration.ofMillis(200), new Random(1)), Clock.systemUTC(), timing);
		uploader.updateSettings(settings, true);
		if (contract != null)
		{
			uploader.onServerContract(SemVer.parse(contract));
		}
		if (autoTick)
		{
			IconUploader u = uploader;
			ticker.scheduleAtFixedRate(u::onGameTick, 1, 1, TimeUnit.MILLISECONDS);
		}
		return uploader;
	}

	private IconUploader start()
	{
		return start(FAST, "1.18.0");
	}

	private Event loot(Integer... itemIds)
	{
		return loot(Arrays.asList(itemIds));
	}

	private Event loot(List<Integer> itemIds)
	{
		List<LootItem> items = new ArrayList<>();
		for (int id : itemIds)
		{
			items.add(new LootItem(id, "Item " + id, 1, 1, 0));
		}
		return Event.create(eventIds, 42L, Instant.now(), eventIds.next(),
			new Loot(LootSource.npc("Vorkath", 8061, 732), items, null));
	}

	private Received next(String what) throws InterruptedException
	{
		Received r = server.received.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
		assertNotNull("Timed out waiting for " + what, r);
		return r;
	}

	private Received nextWanted() throws InterruptedException
	{
		Received r = next("icons/wanted");
		assertTrue("expected icons/wanted, got " + r.path, r.isWanted());
		return r;
	}

	private Received nextUpload() throws InterruptedException
	{
		Received r = next("icons");
		assertTrue("expected icons, got " + r.path, !r.isWanted());
		return r;
	}

	private void assertQuiet() throws InterruptedException
	{
		Received r = server.received.poll(QUIET_MS, TimeUnit.MILLISECONDS);
		assertNull(r == null ? null : "unexpected request " + r.path + " " + r.ids, r);
	}

	private static List<Integer> range(int from, int to)
	{
		List<Integer> ids = new ArrayList<>();
		for (int i = from; i < to; i++)
		{
			ids.add(i);
		}
		return ids;
	}

	@Test
	public void asksInChunksOf500AndUploadsInBatchesOf100() throws Exception
	{
		for (int id = 0; id < 400; id++)
		{
			server.stored.add(id);
		}
		start().collect(loot(range(0, 650)));

		assertEquals(range(0, 500), nextWanted().ids);
		// Uploads of the first chunk's icons may go before the second wanted.
		List<List<Integer>> wanted = new ArrayList<>();
		Set<Integer> uploaded = new TreeSet<>();
		List<Integer> sizes = new ArrayList<>();
		while (uploaded.size() < 250 || wanted.isEmpty())
		{
			Received r = next("requests");
			if (r.isWanted())
			{
				wanted.add(r.ids);
				continue;
			}
			assertTrue("batch of " + r.ids.size(), r.ids.size() <= IconClient.MAX_ICONS_PER_REQUEST);
			uploaded.addAll(r.ids);
			sizes.add(r.ids.size());
		}
		assertEquals(Collections.singletonList(range(500, 650)), wanted);
		assertEquals(new TreeSet<>(range(400, 650)), uploaded);
		assertEquals(250, sizes.stream().mapToInt(Integer::intValue).sum());
		assertEquals("as few batches as possible", 3, sizes.size());
		// Only the missing ones were loaded.
		assertEquals(new HashSet<>(range(400, 650)), new HashSet<>(images.loads));
		assertQuiet();
	}

	/** Waits until {@code images.loads} has {@code count} entries (loads are started on the executor). */
	private void awaitLoads(int count) throws InterruptedException
	{
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (images.loads.size() < count && System.nanoTime() < deadline)
		{
			TimeUnit.MILLISECONDS.sleep(5);
		}
		assertEquals(count, images.loads.size());
	}

	/** Lets the executor run everything posted so far. */
	private void drainExecutor() throws Exception
	{
		executor.submit(() ->
		{
		}).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
	}

	@Test
	public void startsAtMost25IconLoadsPerGameTick() throws Exception
	{
		autoTick = false;
		IconUploader u = start();
		u.collect(loot(range(0, 500)));
		assertEquals(500, nextWanted().ids.size());
		TimeUnit.MILLISECONDS.sleep(QUIET_MS);
		drainExecutor();
		assertTrue("loads started without a game tick", images.loads.isEmpty());

		u.onGameTick();
		awaitLoads(IconUploader.MAX_LOADS_PER_TICK);
		TimeUnit.MILLISECONDS.sleep(100);
		drainExecutor();
		assertEquals(IconUploader.MAX_LOADS_PER_TICK, images.loads.size());

		u.onGameTick();
		awaitLoads(2 * IconUploader.MAX_LOADS_PER_TICK);
		assertEquals("in the order the server listed them", range(0, 2 * IconUploader.MAX_LOADS_PER_TICK),
			new ArrayList<>(images.loads));
	}

	@Test
	public void theLoadTimeoutCountsFromTheTickThatStartsTheLoad() throws Exception
	{
		autoTick = false;
		IconUploader u = start(new IconUploader.Timing(Duration.ofMillis(50), Duration.ofMillis(50),
			Duration.ofMillis(10), Duration.ofMillis(100)), "1.18.0");
		u.collect(loot(7));
		nextWanted();
		// Much longer than the load timeout before the first tick: the icon must still load and upload.
		TimeUnit.MILLISECONDS.sleep(400);
		u.onGameTick();
		assertEquals(Collections.singletonList(7), nextUpload().ids);
	}

	@Test
	public void queuedLoadsAreDroppedWhenIconsAreTurnedOff() throws Exception
	{
		autoTick = false;
		IconUploader u = start();
		u.collect(loot(range(0, 30)));
		nextWanted();
		drainExecutor();
		u.updateSettings(settings, false);
		u.onGameTick();
		drainExecutor();
		assertTrue(images.loads.isEmpty());
		assertQuiet();
	}

	@Test
	public void uploadsTheExactIdFromTheEvent() throws Exception
	{
		start().collect(loot(4152, 995));
		assertEquals(Arrays.asList(4152, 995), nextWanted().ids);
		assertEquals(new HashSet<>(Arrays.asList(4152, 995)), new HashSet<>(nextUpload().ids));
		assertEquals(new HashSet<>(Arrays.asList(4152, 995)), new HashSet<>(images.loads));
	}

	@Test
	public void asksAtMostOncePerIdPerSession() throws Exception
	{
		server.stored.add(4151);
		IconUploader u = start();
		u.collect(loot(4151));
		assertEquals(Collections.singletonList(4151), nextWanted().ids);
		assertQuiet();

		u.collect(loot(4151, 995));
		u.collect(loot(4151));
		assertEquals(Collections.singletonList(995), nextWanted().ids);
		assertEquals(Collections.singletonList(995), nextUpload().ids);
		u.collect(loot(995, 4151));
		assertQuiet();
	}

	@Test
	public void rejectedIconsAreNotTriedAgain() throws Exception
	{
		server.reject.add(11840);
		IconUploader u = start();
		u.collect(loot(11840));
		nextWanted();
		assertEquals(Collections.singletonList(11840), nextUpload().ids);
		u.collect(loot(11840));
		assertQuiet();
	}

	@Test
	public void olderServerGetsNoIconRequests() throws Exception
	{
		IconUploader u = start(FAST, "1.17.0");
		u.collect(loot(4151, 995));
		assertQuiet();
		assertTrue(images.loads.isEmpty());
	}

	@Test
	public void waitsForTheContractCheck() throws Exception
	{
		IconUploader u = start(FAST, null);
		u.collect(loot(4151));
		assertQuiet();
		u.onServerContract(SemVer.parse("1.18.0"));
		assertEquals(Collections.singletonList(4151), nextWanted().ids);
	}

	@Test
	public void toggleOffUploadsNothing() throws Exception
	{
		IconUploader u = start();
		u.updateSettings(settings, false);
		u.collect(loot(4151));
		assertQuiet();

		u.updateSettings(new SenderSettings(false, settings.getServerUrl(), KEY), true);
		u.collect(loot(4151));
		assertQuiet();
	}

	@Test
	public void anImageThatNeverLoadsDoesNotHoldBackTheOthers() throws Exception
	{
		images.neverLoad.add(2);
		// A long load timeout: the others must be uploaded long before it.
		IconUploader u = start(new IconUploader.Timing(Duration.ofMillis(50), Duration.ofMillis(50),
			Duration.ofMillis(10), Duration.ofSeconds(30)), "1.18.0");
		u.collect(loot(1, 2, 3));
		Received wanted = nextWanted();
		Received upload = nextUpload();
		assertEquals(new HashSet<>(Arrays.asList(1, 3)), new HashSet<>(upload.ids));
		assertTrue("upload waited for the stuck image",
			upload.atNanos - wanted.atNanos < TimeUnit.SECONDS.toNanos(5));
	}

	@Test
	public void anImageThatTimesOutIsDroppedForTheSession() throws Exception
	{
		images.neverLoad.add(2);
		IconUploader u = start(new IconUploader.Timing(Duration.ofMillis(50), Duration.ofMillis(50),
			Duration.ofMillis(10), Duration.ofMillis(100)), "1.18.0");
		u.collect(loot(2));
		nextWanted();
		assertQuiet();
		u.collect(loot(2));
		assertQuiet();
		assertEquals(Collections.singletonList(2), images.loads);
	}

	@Test
	public void rateLimitRespectsRetryAfter() throws Exception
	{
		server.script.add(r -> json(429, "{}").setHeader("Retry-After", "1"));
		start().collect(loot(4151));
		Received first = nextWanted();
		Received second = nextWanted();
		assertEquals(first.ids, second.ids);
		assertTrue("retried too soon", second.atNanos - first.atNanos >= TimeUnit.MILLISECONDS.toNanos(900));
		assertEquals(Collections.singletonList(4151), nextUpload().ids);
	}

	@Test
	public void serverErrorsAreRetried() throws Exception
	{
		// wanted: 500, then OK; upload: 503, then OK.
		server.script.add(r -> json(500, "{}"));
		server.script.add(r -> server.answerWanted(r.ids));
		server.script.add(r -> json(503, "{}"));
		start().collect(loot(4151));
		assertEquals(Collections.singletonList(4151), nextWanted().ids);
		assertEquals(Collections.singletonList(4151), nextWanted().ids);
		assertEquals(Collections.singletonList(4151), nextUpload().ids);
		assertEquals(Collections.singletonList(4151), nextUpload().ids);
		assertTrue(server.stored.contains(4151));
	}

	@Test
	public void notFoundTurnsIconsOffForTheConnection() throws Exception
	{
		server.script.add(r -> json(404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"x\"}}"));
		IconUploader u = start();
		u.collect(loot(4151));
		nextWanted();
		u.collect(loot(995));
		assertQuiet();
	}

	@Test
	public void anotherConnectionStartsAFreshSession() throws Exception
	{
		server.stored.add(4151);
		IconUploader u = start();
		u.collect(loot(4151));
		assertEquals("Bearer " + KEY, nextWanted().authorization);

		u.updateSettings(new SenderSettings(true, settings.getServerUrl(), OTHER_KEY), true);
		u.onServerContract(SemVer.parse("1.18.0"));
		u.collect(loot(4151));
		Received again = nextWanted();
		assertEquals(Collections.singletonList(4151), again.ids);
		assertEquals("Bearer " + OTHER_KEY, again.authorization);
	}

	private static MockResponse json(int code, String body)
	{
		return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
	}
}
