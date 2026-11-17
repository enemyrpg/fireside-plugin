package com.fireside.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fireside.Contract;
import com.fireside.event.Event;
import com.fireside.event.EventJson;
import com.fireside.event.Loot;
import com.fireside.event.LootItem;
import com.fireside.event.LootSource;
import com.fireside.event.UuidV7Generator;
import com.fireside.icons.IconCatalog;
import com.fireside.icons.IconImages;
import com.fireside.icons.IconUploader;
import com.fireside.net.IconClient;
import com.fireside.net.IconKey;
import com.fireside.net.IngestClient;
import com.fireside.net.VerifyClient;
import com.fireside.queue.EventQueue;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.runelite.client.util.Filepath;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The sender and the icon uploader wired as in the plugin, against one server
 * whose icon endpoints fail: events must be sent exactly as without icons.
 */
public class EventSenderWithIconsTest
{
	private static final Duration WAIT = Duration.ofSeconds(15);

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private final OkHttpClient http = new OkHttpClient();
	private final UuidV7Generator ids = new UuidV7Generator();
	private final String sessionId = ids.next();
	private final LinkedBlockingQueue<SenderStatus> statuses = new LinkedBlockingQueue<>();
	private final List<SenderStatus.State> states = new ArrayList<>();

	private FakeIngestServer server;
	private ScheduledThreadPoolExecutor senderExecutor;
	private ScheduledThreadPoolExecutor iconExecutor;
	private EventSender sender;
	private IconUploader icons;
	private Consumer<Event> sink;

	@Before
	public void setUp() throws Exception
	{
		server = new FakeIngestServer().start();
		server.contractVersion = "1.18.0";
		senderExecutor = new ScheduledThreadPoolExecutor(1);
		iconExecutor = new ScheduledThreadPoolExecutor(1);
	}

	@After
	public void tearDown() throws Exception
	{
		if (sender != null)
		{
			sender.shutdown();
		}
		if (icons != null)
		{
			icons.shutdown();
		}
		senderExecutor.shutdown();
		iconExecutor.shutdown();
		server.stop();
	}

	private void start()
	{
		SenderSettings settings = new SenderSettings(true, server.url(), FakeIngestServer.KEY);
		EventQueue queue = new EventQueue(Filepath.Unchecked.getRooted(folder.getRoot().toPath().resolve("queue")),
			RuneLiteAPI.GSON, Clock.systemUTC());
		sender = new EventSender(senderExecutor, queue, new EventJson(RuneLiteAPI.GSON),
			new IngestClient(http, RuneLiteAPI.GSON), new VerifyClient(http, RuneLiteAPI.GSON),
			new Backoff(Duration.ofMillis(40), Duration.ofMillis(200), new Random(1)), Clock.systemUTC(),
			Duration.ofHours(1), s ->
			{
				synchronized (states)
				{
					states.add(s.getState());
				}
				statuses.add(s);
			});
		IconImages images = new IconImages()
		{
			@Override
			public void load(int itemId, Consumer<BufferedImage> onLoaded)
			{
				onLoaded.accept(image());
			}

			@Override
			public void loadSprite(int spriteId, Consumer<BufferedImage> onLoaded)
			{
				onLoaded.accept(image());
			}
		};
		icons = new IconUploader(iconExecutor, new IconClient(http, RuneLiteAPI.GSON), images,
			new Backoff(Duration.ofMillis(20), Duration.ofMillis(50), new Random(1)), Clock.systemUTC(),
			new IconUploader.Timing(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(1),
				Duration.ofSeconds(5)));
		icons.updateSettings(settings, true);
		sender.setContractListener(icons::onServerContract);
		sender.start(settings);
		EventSender s = sender;
		IconUploader i = icons;
		sink = event ->
		{
			s.submit(event);
			i.collect(event);
		};
	}

	private static BufferedImage image()
	{
		BufferedImage image = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(10, 10, 0xffff0000);
		return image;
	}

	private List<String> submitLoot(int count)
	{
		List<String> submitted = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			Event event = Event.create(ids, 1234567890123456789L, Instant.now(), sessionId,
				new Loot(LootSource.npc("Vorkath", 8061, 732),
					Arrays.asList(new LootItem(4152 + i, "Item", 1, 1, 0), new LootItem(995, "Coins", 1000, 1, 0)),
					null));
			submitted.add(event.getEventId());
			sink.accept(event);
		}
		return submitted;
	}

	private void await(String what, BooleanSupplier condition) throws Exception
	{
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (!condition.getAsBoolean())
		{
			if (System.nanoTime() > deadline)
			{
				fail("Timed out waiting for " + what);
			}
			statuses.poll(20, TimeUnit.MILLISECONDS);
		}
	}

	private void assertEventsUnaffected(List<String> submitted) throws Exception
	{
		await("all events stored", () -> new HashSet<>(server.stored).containsAll(submitted));
		await("empty queue", () -> sender.getStatus().getQueueSize() == 0
			&& sender.getStatus().getState() == SenderStatus.State.IDLE);
		SenderStatus status = sender.getStatus();
		assertEquals(0, status.getRejectedCount());
		synchronized (states)
		{
			assertFalse("sender stopped or retried: " + states, states.contains(SenderStatus.State.STOPPED)
				|| states.contains(SenderStatus.State.RETRYING) || states.contains(SenderStatus.State.HELD));
		}
	}

	@Test
	public void iconEndpointsAnswering404DoNotAffectEvents() throws Exception
	{
		server.iconStatus = 404;
		start();
		List<String> submitted = submitLoot(3);
		sender.sendNow();
		await("an icon request", () -> !server.iconCalls.isEmpty());
		assertEventsUnaffected(submitted);

		// More events after icons turned themselves off.
		List<String> more = submitLoot(2);
		sender.sendNow();
		assertEventsUnaffected(more);
		assertEquals("no icon retries after a 404", 1, server.iconCalls.size());
	}

	@Test
	public void iconEndpointsAnswering500DoNotAffectEvents() throws Exception
	{
		server.iconStatus = 500;
		start();
		List<String> submitted = submitLoot(3);
		sender.sendNow();
		await("icon retries", () -> server.iconCalls.size() >= 3);
		assertEventsUnaffected(submitted);

		List<String> more = submitLoot(2);
		sender.sendNow();
		assertEventsUnaffected(more);
		assertTrue(server.batches.size() >= 2);
	}

	/** The whole collection log plus skills and bosses, as the import after a login hands it over. */
	private static IconCatalog fullCatalog()
	{
		List<Integer> itemIds = new ArrayList<>();
		for (int i = 0; i < 1740; i++)
		{
			itemIds.add(20_000 + i);
		}
		Map<IconKey, Integer> sprites = new LinkedHashMap<>();
		sprites.put(IconKey.skill("OVERALL"), 1);
		for (int i = 0; i < 24; i++)
		{
			sprites.put(IconKey.skill("SKILL_" + i), 100 + i);
		}
		for (int i = 0; i < 70; i++)
		{
			sprites.put(IconKey.boss("Boss " + i), 200 + i);
		}
		return IconCatalog.of(itemIds, sprites);
	}

	@Test
	public void aFullIconImportAgainstAFailingIconEndpointDoesNotAffectEvents() throws Exception
	{
		server.contractVersion = Contract.NAMED_ICONS_VERSION;
		server.iconStatus = 500;
		start();
		List<String> submitted = submitLoot(3);
		sender.sendNow();
		await("the server's contract", () -> sender.getStatus().getState() == SenderStatus.State.IDLE
			&& sender.getStatus().getQueueSize() == 0);
		icons.importAll(fullCatalog());
		await("import retries", () -> server.iconCalls.size() >= 3);
		assertEventsUnaffected(submitted);

		// Events queued while the import keeps trying.
		List<String> more = submitLoot(5);
		sender.sendNow();
		assertEventsUnaffected(more);
	}

	@Test
	public void aServerBeforeNamedIconsGetsEventsAndNoImport() throws Exception
	{
		server.contractVersion = "1.28.0";
		server.iconStatus = 500;
		start();
		sender.sendNow();
		await("the server's contract", () -> sender.getStatus().getState() == SenderStatus.State.IDLE);
		statuses.poll(200, TimeUnit.MILLISECONDS);
		icons.importAll(fullCatalog());
		statuses.poll(400, TimeUnit.MILLISECONDS);
		assertTrue("import requests to a 1.28 server: " + server.iconCalls, server.iconCalls.isEmpty());

		List<String> submitted = submitLoot(2);
		sender.sendNow();
		assertEventsUnaffected(submitted);
	}

	@Test
	public void olderServerStillGetsEventsButNoIconRequests() throws Exception
	{
		server.contractVersion = "1.17.0";
		start();
		List<String> submitted = submitLoot(3);
		sender.sendNow();
		assertEventsUnaffected(submitted);
		statuses.poll(300, TimeUnit.MILLISECONDS);
		assertTrue("icon requests to a 1.17 server: " + server.iconCalls, server.iconCalls.isEmpty());
	}
}
