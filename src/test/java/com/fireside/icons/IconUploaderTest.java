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
import com.fireside.net.IconResult;
import com.fireside.net.IconKey;
import com.fireside.net.SemVer;
import com.fireside.send.Backoff;
import com.fireside.send.SenderSettings;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import javax.imageio.ImageIO;
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
		/** Skill and boss keys in the request, as {@code SKILL:ATTACK}, in order. */
		final List<String> names = new ArrayList<>();
		/** Width and height of each skill and boss icon in an upload. */
		final List<int[]> namedSizes = new ArrayList<>();
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
		/** Stored skill and boss icons, as {@code SKILL:ATTACK}. */
		final Set<String> storedNamed = ConcurrentHashMap.newKeySet();
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
			Received r = new Received(path, auth, ids);
			if ("/api/v1/ingest/icons/wanted".equals(path))
			{
				if (body.has("itemIds"))
				{
					for (JsonElement e : body.getAsJsonArray("itemIds"))
					{
						ids.add(e.getAsInt());
					}
				}
				for (String kind : new String[]{"skills", "bosses"})
				{
					if (body.has(kind))
					{
						for (JsonElement e : body.getAsJsonArray(kind))
						{
							r.names.add(("skills".equals(kind) ? "SKILL:" : "BOSS:") + e.getAsString());
						}
					}
				}
			}
			else if ("/api/v1/ingest/icons".equals(path))
			{
				for (JsonElement e : body.getAsJsonArray("icons"))
				{
					JsonObject icon = e.getAsJsonObject();
					if (icon.has("kind"))
					{
						r.names.add(icon.get("kind").getAsString() + ":" + icon.get("key").getAsString());
						r.namedSizes.add(pngSize(icon.get("png").getAsString()));
					}
					else
					{
						ids.add(icon.get("itemId").getAsInt());
					}
				}
			}
			else
			{
				return new MockResponse().setResponseCode(404);
			}
			Function<Received, MockResponse> next = script.poll();
			MockResponse response = next != null ? next.apply(r)
				: r.isWanted() ? answerWanted(r) : answerUpload(r);
			// Recorded after the answer (and what it stores), so a test that sees the request sees its effect.
			received.add(r);
			return response;
		}

		MockResponse answerWanted(List<Integer> ids)
		{
			return answerWanted(new Received("/api/v1/ingest/icons/wanted", null, ids));
		}

		MockResponse answerWanted(Received r)
		{
			JsonArray missing = new JsonArray();
			for (int id : r.ids)
			{
				if (!stored.contains(id))
				{
					missing.add(id);
				}
			}
			JsonObject o = new JsonObject();
			o.add("missing", missing);
			// As the real server: the named lists are only in the answer when they were asked for.
			for (String kind : new String[]{"SKILL:", "BOSS:"})
			{
				JsonArray missingNames = new JsonArray();
				boolean asked = false;
				for (String name : r.names)
				{
					if (name.startsWith(kind))
					{
						asked = true;
						if (!storedNamed.contains(name))
						{
							missingNames.add(name.substring(kind.length()));
						}
					}
				}
				if (asked)
				{
					o.add("SKILL:".equals(kind) ? "missingSkills" : "missingBosses", missingNames);
				}
			}
			return json(200, o.toString());
		}

		MockResponse answerUpload(Received r)
		{
			List<Integer> ids = r.ids;
			JsonArray named = new JsonArray();
			for (String name : r.names)
			{
				storedNamed.add(name);
				JsonObject n = new JsonObject();
				n.addProperty("kind", name.substring(0, name.indexOf(':')));
				n.addProperty("key", name.substring(name.indexOf(':') + 1));
				named.add(n);
			}
			JsonArray storedNow = new JsonArray();
			JsonArray rejected = new JsonArray();
			for (int i = 0; i < ids.size(); i++)
			{
				int id = ids.get(i);
				if (reject.contains(id))
				{
					JsonObject rejection = new JsonObject();
					rejection.addProperty("index", i);
					rejection.addProperty("itemId", id);
					rejection.addProperty("error", "png: Must be a base64-encoded PNG");
					rejected.add(rejection);
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
			if (!r.names.isEmpty())
			{
				o.add("storedNamed", named);
				o.add("unchangedNamed", new JsonArray());
			}
			return json(200, o.toString());
		}
	}

	private static int[] pngSize(String base64)
	{
		try
		{
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
			return new int[]{image.getWidth(), image.getHeight()};
		}
		catch (IOException | RuntimeException e)
		{
			return new int[]{-1, -1};
		}
	}

	/** Loads at once (on the calling thread), except IDs that never load. */
	private static final class FakeImages implements IconImages
	{
		final Set<Integer> neverLoad = ConcurrentHashMap.newKeySet();
		final List<Integer> loads = Collections.synchronizedList(new ArrayList<>());
		/** Sprites the game does not have. */
		final Set<Integer> noSprite = ConcurrentHashMap.newKeySet();
		/** Sprites that are fully transparent. */
		final Set<Integer> blankSprite = ConcurrentHashMap.newKeySet();
		final List<Integer> spriteLoads = Collections.synchronizedList(new ArrayList<>());

		@Override
		public void load(int itemId, Consumer<BufferedImage> onLoaded)
		{
			loads.add(itemId);
			if (!neverLoad.contains(itemId))
			{
				onLoaded.accept(PngEncoderTest.testImage(36, 32, itemId));
			}
		}

		/** Trimmed like the game's sprites: 16 to 25 px wide, never the full 25 x 25 canvas. */
		@Override
		public void loadSprite(int spriteId, Consumer<BufferedImage> onLoaded)
		{
			spriteLoads.add(spriteId);
			if (blankSprite.contains(spriteId))
			{
				onLoaded.accept(new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB));
			}
			else if (!noSprite.contains(spriteId))
			{
				onLoaded.accept(PngEncoderTest.testImage(16 + spriteId % 10, 18 + spriteId % 7, spriteId));
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
		// A long gather delay: loads are spread over game ticks, and a slow machine must not split batches.
		start(new IconUploader.Timing(Duration.ofMillis(50), Duration.ofSeconds(2), Duration.ofMillis(10),
			Duration.ofSeconds(5)), "1.18.0").collect(loot(range(0, 650)));

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
		assertEquals("as few batches as possible: " + sizes, 3, sizes.size());
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
	public void everyAnswerIsReportedForTheConnectionStatus() throws Exception
	{
		LinkedBlockingQueue<IconResult.Kind> contacts = new LinkedBlockingQueue<>();
		server.stored.add(1);
		server.script.add(r -> new MockResponse().setResponseCode(503));
		server.script.add(r -> new MockResponse().setResponseCode(429).setHeader("Retry-After", "0"));
		IconUploader u = new IconUploader(executor, new IconClient(new OkHttpClient(), RuneLiteAPI.GSON), images,
			new Backoff(Duration.ofMillis(40), Duration.ofMillis(200), new Random(1)), Clock.systemUTC(), FAST);
		u.setContactListener(contacts::add);
		uploader = u;
		u.updateSettings(settings, true);
		u.onServerContract(SemVer.parse("1.18.0"));
		u.collect(loot(1));
		assertEquals(IconResult.Kind.FAILED, contacts.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS));
		assertEquals(IconResult.Kind.RATE_LIMITED, contacts.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS));
		assertEquals(IconResult.Kind.OK, contacts.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS));
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

	// ---- The import of the collection log, skills and bosses (10.12) ----------------

	private static final String NAMED = "1.29.0";

	private static final Set<String> NAMES = new TreeSet<>(Arrays.asList("SKILL:OVERALL", "SKILL:ATTACK",
		"BOSS:Zulrah", "BOSS:Kree'Arra"));

	/** Items {@code from..to}, the skills OVERALL (sprite 1) and ATTACK (2), and two bosses (3 and 4). */
	private static IconCatalog catalog(int from, int to)
	{
		Map<IconKey, Integer> sprites = new LinkedHashMap<>();
		sprites.put(IconKey.skill("OVERALL"), 1);
		sprites.put(IconKey.skill("ATTACK"), 2);
		sprites.put(IconKey.boss("Zulrah"), 3);
		sprites.put(IconKey.boss("Kree'Arra"), 4);
		return IconCatalog.of(range(from, to), sprites);
	}

	/** Reads requests until the server has stored {@code items} item icons and {@code named} named icons. */
	private List<Received> untilStored(int items, int named) throws InterruptedException
	{
		List<Received> requests = new ArrayList<>();
		while (server.stored.size() < items || server.storedNamed.size() < named)
		{
			requests.add(next("import requests"));
		}
		return requests;
	}

	@Test
	public void theFirstImportUploadsEverythingAndTheNextUploadsNothing() throws Exception
	{
		IconUploader u = start(FAST, NAMED);
		u.importAll(catalog(1000, 1030));

		List<Received> requests = untilStored(30, 4);
		Received wanted = requests.get(0);
		assertTrue(wanted.isWanted());
		assertEquals(range(1000, 1030), wanted.ids);
		assertEquals(Arrays.asList("SKILL:OVERALL", "SKILL:ATTACK", "BOSS:Zulrah", "BOSS:Kree'Arra"), wanted.names);
		assertEquals(new TreeSet<>(range(1000, 1030)), new TreeSet<>(server.stored));
		assertEquals(NAMES, new TreeSet<>(server.storedNamed));
		assertEquals("only one wanted", 1, requests.stream().filter(Received::isWanted).count());
		assertQuiet();

		// The next login: the same catalog is checked again, and nothing is loaded or uploaded.
		int loads = images.loads.size() + images.spriteLoads.size();
		u.importAll(catalog(1000, 1030));
		Received again = nextWanted();
		assertEquals(range(1000, 1030), again.ids);
		assertEquals(4, again.names.size());
		assertQuiet();
		assertEquals(loads, images.loads.size() + images.spriteLoads.size());
	}

	@Test
	public void everyUploadedSkillAndBossIconIs25By25() throws Exception
	{
		start(FAST, NAMED).importAll(catalog(1000, 1003));
		int named = 0;
		for (Received r : untilStored(3, 4))
		{
			for (int[] size : r.namedSizes)
			{
				assertEquals("width of a named icon", IconUploader.NAMED_SIDE, size[0]);
				assertEquals("height of a named icon", IconUploader.NAMED_SIDE, size[1]);
				named++;
			}
		}
		assertEquals(4, named);
		assertEquals(25, IconUploader.NAMED_SIDE);
		assertEquals(new HashSet<>(Arrays.asList(1, 2, 3, 4)), new HashSet<>(images.spriteLoads));
	}

	@Test
	public void aSpriteIsCentredOnTheCanvasAndKeepsItsPixels()
	{
		// Opaque or fully transparent pixels only, like the game's sprites.
		BufferedImage sprite = new BufferedImage(17, 21, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 21; y++)
		{
			for (int x = 0; x < 17; x++)
			{
				if ((x + y) % 4 != 0)
				{
					sprite.setRGB(x, y, 0xff000000 | x * 13 << 16 | y * 11 << 8 | (x ^ y));
				}
			}
		}
		BufferedImage padded = IconUploader.padded(sprite);
		assertEquals(25, padded.getWidth());
		assertEquals(25, padded.getHeight());
		int dx = 25 / 2 - 17 / 2;
		int dy = 25 / 2 - 21 / 2;
		for (int y = 0; y < 25; y++)
		{
			for (int x = 0; x < 25; x++)
			{
				boolean inside = x >= dx && x < dx + 17 && y >= dy && y < dy + 21;
				int expected = inside ? sprite.getRGB(x - dx, y - dy) : 0;
				assertEquals("pixel " + x + "," + y, expected, padded.getRGB(x, y));
			}
		}
	}

	@Test
	public void aServerBeforeNamedIconsGetsNoImportButEventIconsStillWork() throws Exception
	{
		IconUploader u = start(FAST, "1.28.0");
		u.importAll(catalog(1000, 1030));
		assertQuiet();

		u.collect(loot(4151));
		Received wanted = nextWanted();
		assertEquals(Collections.singletonList(4151), wanted.ids);
		assertTrue(wanted.names.isEmpty());
		assertEquals(Collections.singletonList(4151), nextUpload().ids);
		assertQuiet();
		assertTrue(images.spriteLoads.isEmpty());
	}

	@Test
	public void theImportWaitsForTheContractCheck() throws Exception
	{
		IconUploader u = start(FAST, null);
		u.importAll(catalog(1000, 1002));
		assertQuiet();
		u.onServerContract(SemVer.parse(NAMED));
		assertEquals(range(1000, 1002), nextWanted().ids);
	}

	@Test
	public void aHeldImportIsDroppedWhenTheServerTurnsOutToBeOlder() throws Exception
	{
		IconUploader u = start(FAST, null);
		u.importAll(catalog(1000, 1002));
		u.onServerContract(SemVer.parse("1.18.0"));
		assertQuiet();
	}

	@Test
	public void noImportWhileIconsOrSendingAreOff() throws Exception
	{
		IconUploader u = start(FAST, NAMED);
		u.updateSettings(settings, false);
		u.importAll(catalog(1000, 1002));
		assertQuiet();

		u.updateSettings(new SenderSettings(false, settings.getServerUrl(), KEY), true);
		u.importAll(catalog(1000, 1002));
		assertQuiet();
	}

	@Test
	public void turningIconsOffStopsTheImport() throws Exception
	{
		autoTick = false;
		IconUploader u = start(FAST, NAMED);
		u.importAll(catalog(1000, 1030));
		nextWanted();
		drainExecutor();
		u.updateSettings(settings, false);
		u.onGameTick();
		drainExecutor();
		assertTrue(images.loads.isEmpty());
		assertTrue(images.spriteLoads.isEmpty());
		assertQuiet();
	}

	@Test
	public void anImageTheGameCannotGiveIsSkippedAndNotImportedAgain() throws Exception
	{
		images.neverLoad.add(1001);
		images.noSprite.add(2);
		images.blankSprite.add(3);
		IconUploader u = start(new IconUploader.Timing(Duration.ofMillis(50), Duration.ofMillis(50),
			Duration.ofMillis(10), Duration.ofMillis(200)), NAMED);
		u.importAll(catalog(1000, 1003));

		untilStored(2, 2);
		assertEquals(new HashSet<>(Arrays.asList(1000, 1002)), new HashSet<>(server.stored));
		assertEquals(new HashSet<>(Arrays.asList("SKILL:OVERALL", "BOSS:Kree'Arra")),
			new HashSet<>(server.storedNamed));
		// Longer than the load timeout: the import is done, with three icons skipped.
		assertQuiet();

		u.importAll(catalog(1000, 1003));
		Received again = nextWanted();
		assertEquals(Arrays.asList(1000, 1002), again.ids);
		assertEquals(Arrays.asList("SKILL:OVERALL", "BOSS:Kree'Arra"), again.names);
		assertQuiet();
	}

	@Test
	public void itemImagesAndSpritesShareTheCapPerGameTick() throws Exception
	{
		autoTick = false;
		IconUploader u = start(FAST, NAMED);
		u.importAll(catalog(1000, 1040));
		nextWanted();
		TimeUnit.MILLISECONDS.sleep(QUIET_MS);
		drainExecutor();
		assertEquals("loads started without a game tick", 0, images.loads.size() + images.spriteLoads.size());

		u.onGameTick();
		TimeUnit.MILLISECONDS.sleep(100);
		drainExecutor();
		assertEquals(IconUploader.MAX_LOADS_PER_TICK, images.loads.size() + images.spriteLoads.size());

		u.onGameTick();
		TimeUnit.MILLISECONDS.sleep(100);
		drainExecutor();
		assertEquals(40, images.loads.size());
		assertEquals(4, images.spriteLoads.size());
	}

	@Test
	public void iconsOfEventsGoBeforeTheImport() throws Exception
	{
		autoTick = false;
		// A long gap between requests, so the event arrives between the import's two wanted requests.
		IconUploader u = start(new IconUploader.Timing(Duration.ofMillis(20), Duration.ofSeconds(5),
			Duration.ofMillis(600), Duration.ofSeconds(5)), NAMED);
		u.importAll(catalog(1000, 1600));
		Received first = nextWanted();
		assertEquals("500 keys in total", 500, first.ids.size() + first.names.size());
		assertEquals(range(1000, 1500), first.ids);

		u.collect(loot(9001));
		Received second = nextWanted();
		assertEquals("the event's item first", Integer.valueOf(9001), second.ids.get(0));
		assertEquals(101, second.ids.size());
		assertEquals("named keys last", 4, second.names.size());
		// The request is recorded before its answer reaches the uploader.
		TimeUnit.MILLISECONDS.sleep(200);
		drainExecutor();

		u.onGameTick();
		awaitLoads(IconUploader.MAX_LOADS_PER_TICK);
		assertEquals("the event's icon is loaded first", Integer.valueOf(9001), images.loads.get(0));
	}

	@Test
	public void anItemInBothAnEventAndTheImportIsAskedAboutOnce() throws Exception
	{
		IconUploader u = start(FAST, NAMED);
		u.collect(loot(1001));
		u.importAll(catalog(1000, 1003));
		List<Integer> asked = new ArrayList<>();
		for (Received r : untilStored(3, 4))
		{
			if (r.isWanted())
			{
				asked.addAll(r.ids);
			}
		}
		Collections.sort(asked);
		assertEquals(Arrays.asList(1000, 1001, 1002), asked);
		assertQuiet();
	}

	@Test
	public void anotherConnectionIsImportedAgain() throws Exception
	{
		server.stored.addAll(range(1000, 1003));
		server.storedNamed.addAll(NAMES);
		IconUploader u = start(FAST, NAMED);
		u.importAll(catalog(1000, 1003));
		assertEquals("Bearer " + KEY, nextWanted().authorization);
		assertQuiet();

		u.updateSettings(new SenderSettings(true, settings.getServerUrl(), OTHER_KEY), true);
		u.importAll(catalog(1000, 1003));
		// Held until the new server is verified.
		assertQuiet();
		u.onServerContract(SemVer.parse(NAMED));
		Received again = nextWanted();
		assertEquals("Bearer " + OTHER_KEY, again.authorization);
		assertEquals(range(1000, 1003), again.ids);
		assertEquals(4, again.names.size());
	}

	@Test
	public void aFailedImportRequestIsRetried() throws Exception
	{
		server.script.add(r -> json(500, "{}"));
		start(FAST, NAMED).importAll(catalog(1000, 1003));
		Received first = nextWanted();
		Received second = nextWanted();
		assertEquals(first.ids, second.ids);
		assertEquals(first.names, second.names);
		untilStored(3, 4);
		assertQuiet();
	}

	private static MockResponse json(int code, String body)
	{
		return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
	}
}
