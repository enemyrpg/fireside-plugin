package com.fireside.queue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.fireside.event.Timestamps;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * The on-disk event queue: one event per line (JSONL) in
 * {@value #PENDING_FILE}, and a local error log of events the server rejected
 * in {@value #REJECTED_FILE}. Both live in the plugin's queue folder.
 * <ul>
 * <li>New events are appended and flushed to disk ({@code force}) right away.</li>
 * <li>Events are removed only by rewriting the file to a temp file and moving it
 * over the old one atomically, so a crash leaves either the old or the new file.</li>
 * <li>Rejected events are written to the error log <em>before</em> they are
 * removed from the queue, so a crash in between can only log them twice.</li>
 * <li>A line that can't be read (e.g. a write cut off by a crash) is moved to the
 * error log when the queue is opened, never silently dropped.</li>
 * </ul>
 * Events are not all kept in memory: the queue keeps a count and reads from the
 * file when a batch is sent. Not thread-safe; use it from the plugin's executor only.
 */
@Slf4j
public class EventQueue
{
	public static final String PENDING_FILE = "pending.jsonl";
	public static final String REJECTED_FILE = "rejected.jsonl";
	static final String TEMP_FILE = "pending.jsonl.tmp";

	private static final String ID_PREFIX = "{\"eventId\":\"";
	private static final int UUID_LENGTH = 36;

	private final Filepath dir;
	private final Filepath pending;
	private final Filepath rejected;
	private final Filepath temp;
	private final Gson gson;
	private final Clock clock;

	private boolean open;
	private int size;
	private long rejectedCount;

	/**
	 * @param dir the queue folder (created by {@link #open()} if missing)
	 */
	public EventQueue(Filepath dir, Gson gson, Clock clock)
	{
		this.dir = dir;
		this.pending = dir.joinSegment(PENDING_FILE);
		this.rejected = dir.joinSegment(REJECTED_FILE);
		this.temp = dir.joinSegment(TEMP_FILE);
		this.gson = gson;
		this.clock = clock;
	}

	/**
	 * Creates the folder, removes a temp file left by a crash, counts the
	 * queued and rejected events and moves unreadable lines to the error log.
	 */
	public void open() throws IOException
	{
		dir.createDirectories();
		temp.deleteIfExists();

		rejectedCount = 0;
		if (rejected.exists())
		{
			endWithNewline(rejected);
			try (BufferedReader reader = rejected.openBufferedReader())
			{
				for (String line; (line = reader.readLine()) != null; )
				{
					if (!line.trim().isEmpty())
					{
						rejectedCount++;
					}
				}
			}
		}

		size = 0;
		if (pending.exists())
		{
			List<String> unreadable = new ArrayList<>();
			boolean tornEnd = !endsWithNewline(pending);
			try (BufferedReader reader = pending.openBufferedReader())
			{
				for (String line; (line = reader.readLine()) != null; )
				{
					if (line.trim().isEmpty())
					{
						continue;
					}
					if (eventIdOf(line) == null)
					{
						unreadable.add(line);
					}
					else
					{
						size++;
					}
				}
			}
			if (!unreadable.isEmpty())
			{
				log.warn("Moving {} unreadable line(s) from the event queue to the error log", unreadable.size());
				appendLines(rejected, toUnreadableLogLines(unreadable));
				rejectedCount += unreadable.size();
			}
			if (!unreadable.isEmpty() || tornEnd)
			{
				rewrite(Collections.emptySet());
			}
		}
		open = true;
		log.debug("Event queue opened: {} queued, {} rejected", size, rejectedCount);
	}

	/** Appends events (JSON lines without line breaks) and flushes them to disk. */
	public void append(List<String> jsonLines) throws IOException
	{
		checkOpen();
		if (jsonLines.isEmpty())
		{
			return;
		}
		for (String line : jsonLines)
		{
			if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0 || eventIdOf(line) == null)
			{
				throw new IllegalArgumentException("not a single-line event");
			}
		}
		appendLines(pending, jsonLines);
		size += jsonLines.size();
	}

	/**
	 * The oldest events, at most {@code maxEvents} and at most {@code maxBodyBytes}
	 * for the whole request body ({@code {"events":[...]}}). Always at least one
	 * event if the queue is not empty, even if that one is too big on its own.
	 */
	public List<QueuedEvent> head(int maxEvents, long maxBodyBytes) throws IOException
	{
		checkOpen();
		List<QueuedEvent> batch = new ArrayList<>();
		if (size == 0 || !pending.exists())
		{
			return batch;
		}
		long bytes = bodyOverhead();
		try (BufferedReader reader = pending.openBufferedReader())
		{
			for (String line; batch.size() < maxEvents && (line = reader.readLine()) != null; )
			{
				String id = eventIdOf(line);
				if (id == null)
				{
					continue;
				}
				QueuedEvent event = new QueuedEvent(id, line);
				long added = event.byteSize() + (batch.isEmpty() ? 0 : 1);
				if (!batch.isEmpty() && bytes + added > maxBodyBytes)
				{
					break;
				}
				bytes += added;
				batch.add(event);
			}
		}
		return batch;
	}

	/** Bytes in {@code {"events":[]}}, without the events and the commas between them. */
	public static int bodyOverhead()
	{
		return "{\"events\":[]}".length();
	}

	/**
	 * Logs {@code rejectedEvents} to the error log, then removes them and every
	 * event in {@code confirmedIds} from the queue. IDs are matched ignoring case.
	 */
	public void remove(Collection<String> confirmedIds, List<RejectedEvent> rejectedEvents) throws IOException
	{
		checkOpen();
		Set<String> ids = new HashSet<>();
		for (String id : confirmedIds)
		{
			ids.add(id.toLowerCase(Locale.ROOT));
		}
		if (!rejectedEvents.isEmpty())
		{
			List<String> lines = new ArrayList<>();
			for (RejectedEvent r : rejectedEvents)
			{
				lines.add(toRejectedLogLine(r));
				ids.add(r.getEvent().getEventId().toLowerCase(Locale.ROOT));
			}
			appendLines(rejected, lines);
			rejectedCount += lines.size();
		}
		if (!ids.isEmpty())
		{
			rewrite(ids);
		}
	}

	public int size()
	{
		return size;
	}

	/** Lines in the error log. */
	public long rejectedCount()
	{
		return rejectedCount;
	}

	/** Copies the queue without the given (lowercase) IDs to the temp file, then moves it into place. */
	private void rewrite(Set<String> removeIds) throws IOException
	{
		int kept = 0;
		try (FileChannel channel = temp.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
			StandardOpenOption.TRUNCATE_EXISTING);
			Writer writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel),
				StandardCharsets.UTF_8)))
		{
			if (pending.exists())
			{
				try (BufferedReader reader = pending.openBufferedReader())
				{
					for (String line; (line = reader.readLine()) != null; )
					{
						String id = eventIdOf(line);
						if (id == null || removeIds.contains(id.toLowerCase(Locale.ROOT)))
						{
							continue;
						}
						writer.write(line);
						writer.write('\n');
						kept++;
					}
				}
			}
			writer.flush();
			channel.force(true);
		}
		try
		{
			temp.moveTo(pending, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			temp.moveTo(pending, StandardCopyOption.REPLACE_EXISTING);
		}
		size = kept;
	}

	/** Appends whole lines and flushes. If the write fails, cuts the file back so no half line is left. */
	private static void appendLines(Filepath file, List<String> lines) throws IOException
	{
		StringBuilder sb = new StringBuilder();
		for (String line : lines)
		{
			sb.append(line).append('\n');
		}
		ByteBuffer bytes = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
		try (FileChannel channel = file.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
			StandardOpenOption.APPEND))
		{
			long before = channel.size();
			try
			{
				while (bytes.hasRemaining())
				{
					channel.write(bytes);
				}
				channel.force(false);
			}
			catch (IOException e)
			{
				try
				{
					channel.truncate(before);
				}
				catch (IOException suppressed)
				{
					e.addSuppressed(suppressed);
				}
				throw e;
			}
		}
	}

	private static boolean endsWithNewline(Filepath file) throws IOException
	{
		try (FileChannel channel = file.openFileChannel(StandardOpenOption.READ))
		{
			long length = channel.size();
			if (length == 0)
			{
				return true;
			}
			ByteBuffer last = ByteBuffer.allocate(1);
			channel.read(last, length - 1);
			return last.get(0) == '\n';
		}
	}

	/** So a line cut off by a crash is not glued to the next one. */
	private static void endWithNewline(Filepath file) throws IOException
	{
		if (!endsWithNewline(file))
		{
			appendLines(file, Collections.singletonList(""));
		}
	}

	/**
	 * The event's ID, or {@code null} if the line is not a readable event.
	 * Lines written by the plugin start with the ID, so most need no parsing.
	 */
	@Nullable
	static String eventIdOf(String line)
	{
		int end = ID_PREFIX.length() + UUID_LENGTH;
		if (line.startsWith(ID_PREFIX) && line.length() > end && line.charAt(end) == '"'
			&& line.endsWith("}") && isUuid(line.substring(ID_PREFIX.length(), end)))
		{
			return line.substring(ID_PREFIX.length(), end);
		}
		try
		{
			JsonElement element = new JsonParser().parse(line);
			if (element.isJsonObject())
			{
				JsonElement id = element.getAsJsonObject().get("eventId");
				if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
					&& isUuid(id.getAsString()))
				{
					return id.getAsString();
				}
			}
		}
		catch (JsonParseException | IllegalStateException e)
		{
			// Unreadable line.
		}
		return null;
	}

	private static boolean isUuid(String s)
	{
		if (s.length() != UUID_LENGTH)
		{
			return false;
		}
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			boolean dash = i == 8 || i == 13 || i == 18 || i == 23;
			if (dash ? c != '-' : Character.digit(c, 16) < 0)
			{
				return false;
			}
		}
		return true;
	}

	private String toRejectedLogLine(RejectedEvent r)
	{
		JsonObject entry = new JsonObject();
		entry.addProperty("loggedAt", Timestamps.format(clock.instant()));
		if (r.getHttpStatus() != null)
		{
			entry.addProperty("httpStatus", r.getHttpStatus());
		}
		entry.addProperty("error", r.getError());
		entry.add("event", new JsonParser().parse(r.getEvent().getJson()));
		return gson.toJson(entry);
	}

	private List<String> toUnreadableLogLines(List<String> lines)
	{
		String now = Timestamps.format(clock.instant());
		List<String> out = new ArrayList<>();
		for (String line : lines)
		{
			JsonObject entry = new JsonObject();
			entry.addProperty("loggedAt", now);
			entry.addProperty("error", "Unreadable line in the queue file (e.g. cut off by a crash)");
			entry.addProperty("line", line);
			out.add(gson.toJson(entry));
		}
		return out;
	}

	private void checkOpen()
	{
		if (!open)
		{
			throw new IllegalStateException("queue not open");
		}
	}
}
