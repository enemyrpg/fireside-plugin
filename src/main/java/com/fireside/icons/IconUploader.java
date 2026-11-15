package com.fireside.icons;

import com.fireside.Contract;
import com.fireside.event.Event;
import com.fireside.net.IconClient;
import com.fireside.net.IconResult;
import com.fireside.net.SemVer;
import com.fireside.net.ServerUrl;
import com.fireside.send.Backoff;
import com.fireside.send.SenderSettings;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;

/**
 * Uploads the icons of the items in queued events to the server (10.3).
 * <p>
 * <b>Flow.</b> {@link #collect} takes the item IDs of every queued event
 * ({@link IconIds}, exactly as in the event). New IDs are asked about with
 * {@code icons/wanted} a short while later, so a loot burst becomes one
 * request. Every ID the server answers for is remembered for the session and
 * never asked about again. Missing icons are loaded in parallel, each with its
 * own timeout, so an image that never loads does not hold back the others. They
 * are encoded to PNG in memory and uploaded with {@code icons} in batches.
 * <p>
 * <b>Lower priority than events.</b> Runs on its own thread, separate from the
 * event sender, and never touches the queue or the sender's state. At most one
 * icon request is in flight, with {@link Timing#minGap} between requests (the
 * server's rate limit is shared with events). {@code 429}, {@code 5xx} and
 * network errors back off; {@code 404} turns icons off for this connection;
 * {@code 401} pauses them until the server is verified again.
 * <p>
 * <b>When.</b> Only while sending is on, the server is configured, "Upload item
 * icons" is on and the server's contract (from the sender's {@code verify}) is
 * at least {@link Contract#ICONS_VERSION}. An older server gets no icon requests.
 */
@Slf4j
public class IconUploader
{
	/** Delays, so tests can run them fast. */
	@Value
	public static class Timing
	{
		public static final Timing DEFAULT = new Timing(Duration.ofSeconds(10), Duration.ofSeconds(1),
			Duration.ofSeconds(2), Duration.ofSeconds(60));

		/** From the first new ID to the {@code wanted} request. */
		Duration collectDelay;
		/** From a loaded image to its upload, so parallel loads go in one batch. */
		Duration gatherDelay;
		/** Least time between two icon requests. */
		Duration minGap;
		/** How long one image may take to load before it is dropped for the session. */
		Duration loadTimeout;
	}

	/** Encoded icons waiting for upload, at most (about 1.5 KB each). */
	static final int MAX_READY = 2000;

	private static final SemVer ICONS_VERSION = SemVer.parse(Contract.ICONS_VERSION);

	private final ScheduledExecutorService executor;
	private final IconClient client;
	private final IconImages images;
	private final Backoff backoff;
	private final Clock clock;
	private final Timing timing;

	// Everything below is only touched on the executor.
	private SenderSettings settings = SenderSettings.OFF;
	private boolean iconsOn;
	@Nullable
	private SemVer serverContract;
	private boolean closed;
	/** 404 from an icon endpoint: off until the connection changes. */
	private boolean unavailable;
	/** 401: paused until the server is verified again or the connection changes. */
	private boolean unauthorized;

	/** Bumped when the connection changes or icons stop, so late results are ignored. */
	private int generation;
	/** IDs the server has answered for (or that were handled) this session. */
	private final Set<Integer> checked = new HashSet<>();
	/** New IDs to ask about. */
	private final Set<Integer> pending = new LinkedHashSet<>();
	/** IDs whose image is loading, with their timeout. */
	private final Map<Integer, ScheduledFuture<?>> loading = new HashMap<>();
	/** Encoded icons waiting for upload. */
	private final Deque<IconClient.Icon> ready = new ArrayDeque<>();

	@Nullable
	private Call call;
	private boolean busy;
	/** IDs of the upload in flight, so they can be unchecked if it is cancelled. */
	private List<Integer> uploading = new ArrayList<>();
	@Nullable
	private Instant notBefore;
	@Nullable
	private ScheduledFuture<?> pumpFuture;
	@Nullable
	private Instant pumpAt;

	public IconUploader(ScheduledExecutorService executor, IconClient client, IconImages images, Backoff backoff,
		Clock clock, Timing timing)
	{
		this.executor = executor;
		this.client = client;
		this.images = images;
		this.backoff = backoff;
		this.clock = clock;
		this.timing = timing;
	}

	// ---- Public API: thread-safe, never blocks --------------------------------

	/** The connection and whether icons are on ("Send data" and "Upload item icons"). */
	public void updateSettings(SenderSettings newSettings, boolean uploadIcons)
	{
		post(() -> applySettings(newSettings, uploadIcons));
	}

	/**
	 * The server's contract version from the sender's {@code verify}, or
	 * {@code null} when it is not known (yet) for the current connection.
	 */
	public void onServerContract(@Nullable SemVer version)
	{
		post(() ->
		{
			serverContract = version;
			if (version != null)
			{
				// verify succeeded, so the key works again.
				unauthorized = false;
				if (!contractOk())
				{
					log.debug("Server contract {} has no item icons; skipping icons", version);
					dropWork();
				}
			}
			pump();
		});
	}

	/** Takes the item IDs of a queued event. Cheap: call it right after queueing the event. */
	public void collect(Event event)
	{
		Set<Integer> ids = IconIds.of(event);
		if (!ids.isEmpty())
		{
			post(() -> addIds(ids));
		}
	}

	/** Cancels timers, loads and the request in flight. The owner shuts the executor down (without waiting). */
	public void shutdown()
	{
		post(() ->
		{
			closed = true;
			stopWork();
			cancel(pumpFuture);
			pumpFuture = null;
		});
	}

	// ---- Executor only ----------------------------------------------------------

	private boolean active()
	{
		return !closed && iconsOn && settings.isEnabled() && settings.isConfigured() && !unavailable;
	}

	private boolean contractOk()
	{
		return serverContract != null && serverContract.compareTo(ICONS_VERSION) >= 0;
	}

	private void applySettings(SenderSettings newSettings, boolean uploadIcons)
	{
		boolean connectionChanged = !newSettings.sameConnection(settings);
		settings = newSettings;
		iconsOn = uploadIcons;
		if (connectionChanged)
		{
			// Another server: nothing we know applies to it.
			stopWork();
			checked.clear();
			serverContract = null;
			unavailable = false;
			unauthorized = false;
			backoff.reset();
			notBefore = null;
		}
		else if (!active())
		{
			stopWork();
		}
		pump();
	}

	private void addIds(Collection<Integer> ids)
	{
		if (!active() || (serverContract != null && !contractOk()))
		{
			return;
		}
		boolean added = false;
		for (Integer id : ids)
		{
			if (!checked.contains(id) && !loading.containsKey(id) && pending.add(id))
			{
				added = true;
			}
		}
		if (added)
		{
			schedulePump(timing.getCollectDelay());
		}
	}

	/** Starts the next request if one is due. */
	private void pump()
	{
		if (!active() || busy || unauthorized || !contractOk())
		{
			return;
		}
		if (ready.isEmpty() && pending.isEmpty())
		{
			return;
		}
		Instant now = clock.instant();
		if (notBefore != null && now.isBefore(notBefore))
		{
			schedulePump(Duration.between(now, notBefore));
			return;
		}
		if (!ready.isEmpty())
		{
			startUpload();
		}
		else
		{
			startWanted();
		}
	}

	private void startWanted()
	{
		List<Integer> ids = new ArrayList<>(IconClient.MAX_WANTED_IDS);
		for (Iterator<Integer> it = pending.iterator(); it.hasNext() && ids.size() < IconClient.MAX_WANTED_IDS; )
		{
			ids.add(it.next());
			it.remove();
		}
		int gen = generation;
		busy = true;
		try
		{
			call = client.wanted(settings.url(), settings.getApiKey(), ids,
				result -> post(() -> onWanted(gen, ids, result)));
		}
		catch (RuntimeException e)
		{
			log.warn("Could not start an icon request", e);
			busy = false;
			pending.addAll(ids);
			retryLater(null);
		}
	}

	private void onWanted(int gen, List<Integer> ids, IconResult result)
	{
		if (closed || gen != generation)
		{
			return;
		}
		requestDone();
		if (result.getKind() == IconResult.Kind.OK)
		{
			backoff.reset();
			checked.addAll(ids);
			Set<Integer> asked = new HashSet<>(ids);
			for (Integer id : result.getMissing())
			{
				// Only what we asked about; the server answers in the same order.
				if (asked.remove(id))
				{
					startLoad(id);
				}
			}
			log.debug("Server lacks {} of {} item icon(s)", result.getMissing().size(), ids.size());
			pump();
			return;
		}
		// Asked again later (in front of newer IDs, the order does not matter).
		Set<Integer> again = new LinkedHashSet<>(ids);
		again.addAll(pending);
		pending.clear();
		pending.addAll(again);
		failed(result);
	}

	private void startLoad(int itemId)
	{
		int gen = generation;
		ScheduledFuture<?> timeout = executor.schedule(() -> safely(() ->
		{
			if (gen == generation && loading.remove(itemId) != null)
			{
				// Dropped for the session: it is in checked, so it is not asked about again.
				log.debug("Item icon {} did not load in time; skipped", itemId);
			}
		}), timing.getLoadTimeout().toMillis(), TimeUnit.MILLISECONDS);
		loading.put(itemId, timeout);
		try
		{
			images.load(itemId, image -> post(() -> onLoaded(gen, itemId, image)));
		}
		catch (RuntimeException e)
		{
			log.debug("Could not load item icon {}: {}", itemId, e.getClass().getSimpleName());
			cancel(loading.remove(itemId));
		}
	}

	private void onLoaded(int gen, int itemId, BufferedImage image)
	{
		if (closed || gen != generation)
		{
			return;
		}
		ScheduledFuture<?> timeout = loading.remove(itemId);
		if (timeout == null)
		{
			return; // Timed out already, or loaded twice.
		}
		cancel(timeout);
		byte[] png = PngEncoder.encode(image);
		if (png == null)
		{
			log.debug("Item icon {} is empty or too large; skipped", itemId);
			return;
		}
		if (ready.size() >= MAX_READY)
		{
			// The server has been failing for a long time; ask again when the item shows up again.
			checked.remove(itemId);
			return;
		}
		ready.add(new IconClient.Icon(itemId, png));
		schedulePump(ready.size() >= IconClient.MAX_ICONS_PER_REQUEST ? Duration.ZERO : timing.getGatherDelay());
	}

	private void startUpload()
	{
		List<IconClient.Icon> batch = new ArrayList<>();
		int bytes = 16;
		while (!ready.isEmpty() && batch.size() < IconClient.MAX_ICONS_PER_REQUEST)
		{
			IconClient.Icon next = ready.peekFirst();
			if (!batch.isEmpty() && bytes + next.jsonSize() > IconClient.MAX_UPLOAD_BYTES)
			{
				break;
			}
			bytes += next.jsonSize();
			batch.add(ready.pollFirst());
		}
		int gen = generation;
		busy = true;
		uploading = new ArrayList<>();
		for (IconClient.Icon icon : batch)
		{
			uploading.add(icon.getItemId());
		}
		try
		{
			call = client.upload(settings.url(), settings.getApiKey(), batch,
				result -> post(() -> onUploaded(gen, batch, result)));
		}
		catch (RuntimeException e)
		{
			log.warn("Could not start an icon upload", e);
			busy = false;
			uploading = new ArrayList<>();
			putBack(batch);
			retryLater(null);
		}
	}

	private void onUploaded(int gen, List<IconClient.Icon> batch, IconResult result)
	{
		if (closed || gen != generation)
		{
			return;
		}
		requestDone();
		if (result.getKind() == IconResult.Kind.OK)
		{
			backoff.reset();
			// Rejected icons are not tried again this session (03), and neither is anything
			// the answer left out: the IDs stay in checked.
			log.debug("Uploaded {} item icon(s), {} rejected", result.getAccepted().size(),
				Math.max(result.getRejected().size(), result.getRejectedIndexes().size()));
			pump();
			return;
		}
		putBack(batch);
		failed(result);
	}

	private void putBack(List<IconClient.Icon> batch)
	{
		for (int i = batch.size() - 1; i >= 0; i--)
		{
			ready.addFirst(batch.get(i));
		}
	}

	private void failed(IconResult result)
	{
		switch (result.getKind())
		{
			case NOT_FOUND:
				log.debug("The server has no icon endpoints (HTTP 404); icons off for this connection");
				unavailable = true;
				stopWork();
				break;
			case UNAUTHORIZED:
				// The sender stops and tells the user; we wait for the next successful verify.
				unauthorized = true;
				break;
			default:
				retryLater(result.getRetryAfter());
				break;
		}
	}

	private void requestDone()
	{
		busy = false;
		call = null;
		uploading = new ArrayList<>();
		notBefore = clock.instant().plus(timing.getMinGap());
	}

	private void retryLater(@Nullable Duration retryAfter)
	{
		Duration wait = backoff.next(retryAfter);
		log.debug("Icon request failed, retrying in {} ms", wait.toMillis());
		notBefore = clock.instant().plus(wait);
		schedulePump(wait);
	}

	/** Runs {@link #pump} after {@code delay}, or earlier if a pump is already due sooner. */
	private void schedulePump(Duration delay)
	{
		if (closed)
		{
			return;
		}
		Instant at = clock.instant().plus(delay);
		if (pumpFuture != null && !pumpFuture.isDone() && pumpAt != null && !pumpAt.isAfter(at))
		{
			return;
		}
		cancel(pumpFuture);
		pumpAt = at;
		pumpFuture = executor.schedule(() -> safely(() ->
		{
			pumpFuture = null;
			pumpAt = null;
			pump();
		}), Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
	}

	/**
	 * Forgets the work in progress. Icons that were loading or waiting for upload
	 * are no longer checked, so they are asked about again if the item shows up
	 * again (e.g. after "Upload item icons" is turned back on).
	 */
	private void dropWork()
	{
		pending.clear();
		for (IconClient.Icon icon : ready)
		{
			checked.remove(icon.getItemId());
		}
		ready.clear();
		for (Map.Entry<Integer, ScheduledFuture<?>> entry : loading.entrySet())
		{
			checked.remove(entry.getKey());
			cancel(entry.getValue());
		}
		loading.clear();
	}

	/** {@link #dropWork} plus the request in flight. */
	private void stopWork()
	{
		generation++;
		dropWork();
		checked.removeAll(uploading);
		uploading = new ArrayList<>();
		busy = false;
		if (call != null)
		{
			call.cancel();
			call = null;
		}
	}

	private void post(Runnable task)
	{
		try
		{
			executor.execute(() -> safely(task));
		}
		catch (RejectedExecutionException e)
		{
			log.debug("Icon uploader is shut down; task dropped");
		}
	}

	private static void safely(Runnable task)
	{
		try
		{
			task.run();
		}
		catch (RuntimeException e)
		{
			log.warn("Unexpected error in the icon uploader", e);
		}
	}

	private static void cancel(@Nullable Future<?> future)
	{
		if (future != null)
		{
			future.cancel(false);
		}
	}
}
