package com.osrstracker.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osrstracker.event.Event;
import com.osrstracker.event.EventJson;
import com.osrstracker.event.SessionEnd;
import com.osrstracker.event.UuidV7Generator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.runelite.client.util.Filepath;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EventQueueTest
{
	static final Instant NOW = Instant.parse("2026-11-13T18:00:00.123Z");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private final Gson gson = RuneLiteAPI.GSON;
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final EventJson json = new EventJson(gson);
	private final UuidV7Generator ids = new UuidV7Generator();

	private Path dir()
	{
		return folder.getRoot().toPath().resolve("queue");
	}

	private EventQueue openQueue() throws IOException
	{
		// Test only: production code gets its folder from Plugin.getPluginDirectory().
		EventQueue queue = new EventQueue(Filepath.Unchecked.getRooted(dir()), gson, clock);
		queue.open();
		return queue;
	}

	private List<String> lines(int count)
	{
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			lines.add(json.toJson(new Event(ids.next(), 42L, NOW, "01933c40-1111-7aaa-bbbb-ccccdddd0001",
				new SessionEnd(SessionEnd.Reason.LOGOUT))));
		}
		return lines;
	}

	private static List<String> idsOf(List<QueuedEvent> events)
	{
		return events.stream().map(QueuedEvent::getEventId).collect(Collectors.toList());
	}

	private static List<String> idsOfLines(List<String> lines)
	{
		return lines.stream().map(EventQueue::eventIdOf).collect(Collectors.toList());
	}

	private List<String> fileLines(String name) throws IOException
	{
		Path file = dir().resolve(name);
		return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : Collections.emptyList();
	}

	@Test
	public void survivesARestart() throws Exception
	{
		List<String> lines = lines(7);
		EventQueue first = openQueue();
		first.append(lines.subList(0, 3));
		first.append(lines.subList(3, 7));
		first.remove(Collections.singletonList(EventQueue.eventIdOf(lines.get(1))), Collections.emptyList());
		assertEquals(6, first.size());

		EventQueue second = openQueue();
		assertEquals(6, second.size());
		List<String> expected = new ArrayList<>(idsOfLines(lines));
		expected.remove(1);
		assertEquals(expected, idsOf(second.head(500, Long.MAX_VALUE)));
		assertEquals(lines.get(0), second.head(1, Long.MAX_VALUE).get(0).getJson());
	}

	@Test
	public void emptyQueueStartsFromNothing() throws Exception
	{
		EventQueue queue = openQueue();
		assertEquals(0, queue.size());
		assertEquals(0, queue.rejectedCount());
		assertTrue(queue.head(500, Long.MAX_VALUE).isEmpty());
		assertTrue(Files.isDirectory(dir()));
	}

	@Test
	public void headRespectsEventAndByteLimits() throws Exception
	{
		List<String> lines = lines(10);
		EventQueue queue = openQueue();
		queue.append(lines);
		assertEquals(4, queue.head(4, Long.MAX_VALUE).size());

		int lineBytes = lines.get(0).getBytes(StandardCharsets.UTF_8).length;
		// Room for exactly three events: overhead + 3 lines + 2 commas.
		long body = EventQueue.bodyOverhead() + 3L * lineBytes + 2;
		assertEquals(3, queue.head(500, body).size());
		assertEquals(2, queue.head(500, body - 1).size());
		// One event is always returned, even if it is too big on its own.
		assertEquals(1, queue.head(500, 10).size());
	}

	@Test
	public void removeMatchesIdsIgnoringCaseAndLogsRejectedEventsFirst() throws Exception
	{
		List<String> lines = lines(5);
		EventQueue queue = openQueue();
		queue.append(lines);
		List<QueuedEvent> head = queue.head(500, Long.MAX_VALUE);

		queue.remove(Arrays.asList(head.get(0).getEventId().toUpperCase(Locale.ROOT), "not-in-the-queue"),
			Collections.singletonList(new RejectedEvent(head.get(2), 200, "payload.reason: Invalid option")));

		assertEquals(Arrays.asList(head.get(1).getEventId(), head.get(3).getEventId(), head.get(4).getEventId()),
			idsOf(queue.head(500, Long.MAX_VALUE)));
		assertEquals(3, queue.size());
		assertEquals(1, queue.rejectedCount());

		List<String> log = fileLines(EventQueue.REJECTED_FILE);
		assertEquals(1, log.size());
		JsonObject entry = new JsonParser().parse(log.get(0)).getAsJsonObject();
		assertEquals("2026-11-13T18:00:00.123Z", entry.get("loggedAt").getAsString());
		assertEquals(200, entry.get("httpStatus").getAsInt());
		assertEquals("payload.reason: Invalid option", entry.get("error").getAsString());
		assertEquals(head.get(2).getEventId(), entry.getAsJsonObject("event").get("eventId").getAsString());

		// The count survives a restart.
		assertEquals(1, openQueue().rejectedCount());
	}

	@Test
	public void lineCutOffByACrashGoesToTheErrorLog() throws Exception
	{
		List<String> lines = lines(3);
		EventQueue queue = openQueue();
		queue.append(lines.subList(0, 2));
		// A crash in the middle of an append: half a line, no newline.
		String torn = lines.get(2).substring(0, 40);
		Files.write(dir().resolve(EventQueue.PENDING_FILE), torn.getBytes(StandardCharsets.UTF_8),
			java.nio.file.StandardOpenOption.APPEND);

		EventQueue reopened = openQueue();
		assertEquals(2, reopened.size());
		assertEquals(1, reopened.rejectedCount());
		JsonObject entry = new JsonParser().parse(fileLines(EventQueue.REJECTED_FILE).get(0)).getAsJsonObject();
		assertEquals(torn, entry.get("line").getAsString());
		assertTrue(entry.get("error").getAsString().contains("Unreadable"));

		// New events are not glued to the broken line.
		List<String> more = lines(1);
		reopened.append(more);
		assertEquals(3, openQueue().size());
		assertEquals(Arrays.asList(lines.get(0), lines.get(1), more.get(0)), fileLines(EventQueue.PENDING_FILE));
	}

	@Test
	public void leftoverTempFileIsIgnoredAndRemoved() throws Exception
	{
		List<String> lines = lines(2);
		EventQueue queue = openQueue();
		queue.append(lines);
		// A crash while writing the temp file: the real file is still complete.
		Files.write(dir().resolve(EventQueue.TEMP_FILE), "{\"eventId\":\"half".getBytes(StandardCharsets.UTF_8));

		EventQueue reopened = openQueue();
		assertEquals(2, reopened.size());
		assertFalse(Files.exists(dir().resolve(EventQueue.TEMP_FILE)));
		assertEquals(0, reopened.rejectedCount());
	}

	@Test
	public void tornErrorLogLineIsNotGluedToTheNextEntry() throws Exception
	{
		Files.createDirectories(dir());
		Files.write(dir().resolve(EventQueue.REJECTED_FILE), "{\"loggedAt\":\"2026".getBytes(StandardCharsets.UTF_8));
		EventQueue queue = openQueue();
		assertEquals(1, queue.rejectedCount());
		List<String> lines = lines(1);
		queue.append(lines);
		QueuedEvent event = queue.head(1, Long.MAX_VALUE).get(0);
		queue.remove(Collections.emptyList(), Collections.singletonList(new RejectedEvent(event, 400, "bad")));
		List<String> log = fileLines(EventQueue.REJECTED_FILE);
		assertEquals(2, log.size());
		assertEquals("bad", new JsonParser().parse(log.get(1)).getAsJsonObject().get("error").getAsString());
	}

	@Test
	public void eventIdOfReadsPluginLinesAndOtherJson()
	{
		String line = lines(1).get(0);
		assertEquals(line.substring(12, 48), EventQueue.eventIdOf(line));
		assertEquals("01933c4e-8a2b-7c3d-9e4f-0a1b2c3d4e5f",
			EventQueue.eventIdOf("{ \"type\": \"kc\", \"eventId\": \"01933c4e-8a2b-7c3d-9e4f-0a1b2c3d4e5f\" }"));
		assertNull(EventQueue.eventIdOf("{\"eventId\":\"01933c4e-8a2b-7c3d"));
		assertNull(EventQueue.eventIdOf("{\"eventId\":42}"));
		assertNull(EventQueue.eventIdOf("not json"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void appendRejectsLinesWithLineBreaks() throws Exception
	{
		openQueue().append(Collections.singletonList(lines(1).get(0) + "\n{}"));
	}
}
