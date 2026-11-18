package com.fireside.icons;

import com.fireside.Contract;
import com.fireside.event.Event;
import com.fireside.net.IconClient;
import com.fireside.net.IconKey;
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
import java.util.EnumMap;
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
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.ImageUtil;
import okhttp3.Call;

/**
 * Uploads icons to the server: those of the items in queued events (10.3), and
 * once per login those of the whole collection log, all skills and all
 * bosses/raids (10.12).
 * <p>
 * <b>Flow.</b> {@link #collect} takes the item IDs of every queued event
 * ({@link IconIds}, exactly as in the event). New IDs are asked about with
 * {@code icons/wanted} a short while later, so a loot burst becomes one
 * request. Every ID the server answers for is remembered for the session and
 * never asked about again. Missing icons are loaded in parallel, each with its
 * own timeout, so an image that never loads does not hold back the others. At
 * most {@link #MAX_LOADS_PER_TICK} loads start per game tick ({@link #onGameTick}),
 * because each one draws a sprite on the client thread and a first bank can
 * bring well over a thousand new IDs at once. They
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
 * <p>
 * <b>The import (10.12).</b> {@link #importAll} takes the icons of the whole
 * collection log, all skills and all bosses/raids ({@link IconCatalog}) through
 * the same flow: {@code wanted} first, then only what the server lacks is
 * loaded and uploaded. It needs contract {@link Contract#NAMED_ICONS_VERSION};
 * an older server gets no import at all, and item icons from events work as
 * before. Keys from queued events always go first, in {@code wanted} and in the
 * loads of a game tick. Skill and boss sprites are padded to
 * {@link #NAMED_SIDE} x {@link #NAMED_SIDE} before they are encoded. An image
 * the game cannot give is skipped; only the counts are logged, when the import
 * is done.
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

	/** Icon loads started per game tick at most (about 40 per second), so the client thread never hitches. */
	static final int MAX_LOADS_PER_TICK = 25;

	/**
	 * Side of a skill or boss icon. The game's sprites are trimmed (16 to 25 px wide); the
	 * dashboard draws them at exactly this size, as RuneLite's Hiscore panel does.
	 */
	static final int NAMED_SIDE = 25;

	private static final SemVer ICONS_VERSION = SemVer.parse(Contract.ICONS_VERSION);
	private static final SemVer NAMED_ICONS_VERSION = SemVer.parse(Contract.NAMED_ICONS_VERSION);

	/** One import: what is still open, and the counts for the log. */
	private static final class ImportRun
	{
		static final int CHECKED = 0;
		static final int MISSING = 1;
		static final int UPLOADED = 2;
		static final int SKIPPED = 3;

		final Instant startedAt;
		/** Keys that are neither answered "has it", uploaded nor skipped yet. */
		final Set<IconKey> open = new HashSet<>();
		final Map<IconKey.Kind, int[]> counts = new EnumMap<>(IconKey.Kind.class);

		ImportRun(Instant startedAt)
		{
			this.startedAt = startedAt;
			for (IconKey.Kind kind : IconKey.Kind.values())
			{
				counts.put(kind, new int[4]);
			}
		}

		void count(IconKey key, int what)
		{
			counts.get(key.getKind())[what]++;
		}
	}

	private final ScheduledExecutorService executor;
	private final IconClient client;
	private final IconImages images;
	private final Backoff backoff;
	private final Clock clock;
	private final Timing timing;
	/** Told the kind of every icon answer, for the connection status. */
	private volatile Consumer<IconResult.Kind> contactListener = kind ->
	{
	};

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
	/** Keys the server has answered for (or that were handled) this session. */
	private final Set<IconKey> checked = new HashSet<>();
	/** New keys from events to ask about. */
	private final Set<IconKey> pending = new LinkedHashSet<>();
	/** Keys from events the server lacks, waiting for a game tick to start loading. */
	private final Set<IconKey> toLoad = new LinkedHashSet<>();
	/** Keys whose image is loading, with their timeout. */
	private final Map<IconKey, ScheduledFuture<?>> loading = new HashMap<>();
	/** The import's keys to ask about, after {@link #pending}. */
	private final Set<IconKey> importPending = new LinkedHashSet<>();
	/** The import's keys the server lacks, loaded after {@link #toLoad}. */
	private final Set<IconKey> importToLoad = new LinkedHashSet<>();
	/** The game sprite of each skill and boss key, from the catalog. */
	private final Map<IconKey, Integer> sprites = new HashMap<>();
	/** Keys the server rejected or the game gave no usable image for: not imported again this session. */
	private final Set<IconKey> unusable = new HashSet<>();
	/** The import in progress, or null. */
	@Nullable
	private ImportRun run;
	/** A catalog that waits for the server's contract version. */
	@Nullable
	private IconCatalog heldImport;
	/** Encoded icons waiting for upload. */
	private final Deque<IconClient.Icon> ready = new ArrayDeque<>();
	/** When the oldest icon in {@link #ready} got there; null when it is empty (or after a failed upload). */
	@Nullable
	private Instant firstReadyAt;

	@Nullable
	private Call call;
	private boolean busy;
	/** Keys of the upload in flight, so they can be unchecked if it is cancelled. */
	private List<IconKey> uploading = new ArrayList<>();
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

	/**
	 * Gets the kind of every answer from an icon endpoint (for the connection
	 * status), on the icon thread. Must not block. Set it before use.
	 */
	public void setContactListener(Consumer<IconResult.Kind> listener)
	{
		contactListener = listener;
	}

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
				else if (!namedOk())
				{
					if (run != null || heldImport != null)
					{
						log.debug("Server contract {} has no skill and boss icons; skipping the icon import", version);
						// Also what was loading: an older server rejects icons with a kind.
						stopWork();
					}
				}
				else if (heldImport != null)
				{
					IconCatalog catalog = heldImport;
					heldImport = null;
					beginImport(catalog);
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

	/**
	 * Checks every icon in the catalog against the server and uploads what it
	 * lacks (10.12). Call it once per login. Ignored while icons are off, the
	 * server is older than {@link Contract#NAMED_ICONS_VERSION} or an import is
	 * already running; held until the server's contract version is known.
	 */
	public void importAll(IconCatalog catalog)
	{
		post(() ->
		{
			if (!active())
			{
				return;
			}
			if (serverContract == null)
			{
				heldImport = catalog;
			}
			else if (namedOk())
			{
				beginImport(catalog);
				pump();
			}
			else
			{
				log.debug("Server contract {} has no skill and boss icons; skipping the icon import", serverContract);
			}
		});
	}

	/**
	 * Every game tick (on the client thread): lets the next
	 * {@link #MAX_LOADS_PER_TICK} icon loads start. Never blocks.
	 */
	public void onGameTick()
	{
		post(this::startLoads);
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

	private boolean namedOk()
	{
		return serverContract != null && serverContract.compareTo(NAMED_ICONS_VERSION) >= 0;
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
			unusable.clear();
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
			IconKey key = IconKey.item(id);
			// An item the import has not asked about yet goes first, as part of the event.
			boolean fromImport = importPending.remove(key);
			if ((fromImport || (!checked.contains(key) && !loading.containsKey(key) && !toLoad.contains(key)))
				&& pending.add(key))
			{
				added = true;
			}
		}
		if (added)
		{
			schedulePump(timing.getCollectDelay());
		}
	}

	private void beginImport(IconCatalog catalog)
	{
		if (run != null)
		{
			log.debug("An icon import is already running; not started again");
			return;
		}
		ImportRun started = new ImportRun(clock.instant());
		sprites.putAll(catalog.getSprites());
		// Keys already on their way (from events) are left to finish as they are.
		Set<IconKey> inFlight = new HashSet<>(pending);
		inFlight.addAll(toLoad);
		inFlight.addAll(loading.keySet());
		inFlight.addAll(uploading);
		for (IconClient.Icon icon : ready)
		{
			inFlight.add(icon.getKey());
		}
		for (IconKey key : catalog.keys())
		{
			if (unusable.contains(key))
			{
				started.count(key, ImportRun.SKIPPED);
			}
			else if (!inFlight.contains(key) && importPending.add(key))
			{
				started.open.add(key);
			}
		}
		run = started;
		if (started.open.isEmpty())
		{
			finishImport();
		}
	}

	/** The import no longer waits for {@code key}. */
	private void resolve(IconKey key, int what)
	{
		if (run != null && run.open.remove(key))
		{
			run.count(key, what);
			if (run.open.isEmpty())
			{
				finishImport();
			}
		}
	}

	private void finishImport()
	{
		ImportRun done = run;
		run = null;
		if (done == null)
		{
			return;
		}
		long ms = Duration.between(done.startedAt, clock.instant()).toMillis();
		for (IconKey.Kind kind : IconKey.Kind.values())
		{
			int[] c = done.counts.get(kind);
			log.debug("Icon import, {}: {} checked, {} missing, {} uploaded, {} skipped ({} ms)", kind,
				c[ImportRun.CHECKED], c[ImportRun.MISSING], c[ImportRun.UPLOADED], c[ImportRun.SKIPPED], ms);
		}
	}

	/** No usable image, or rejected: never again this session. */
	private void skip(IconKey key)
	{
		unusable.add(key);
		resolve(key, ImportRun.SKIPPED);
	}

	/** Starts the next request if one is due. */
	private void pump()
	{
		if (!active() || busy || unauthorized || !contractOk())
		{
			return;
		}
		if (ready.isEmpty() && pending.isEmpty() && importPending.isEmpty())
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
			if (ready.size() < IconClient.MAX_ICONS_PER_REQUEST
				&& (!toLoad.isEmpty() || !importToLoad.isEmpty() || !loading.isEmpty())
				&& firstReadyAt != null && now.isBefore(firstReadyAt.plus(timing.getGatherDelay())))
			{
				// More icons are loading or start on the next game ticks: wait for a fuller batch,
				// but never longer than the gather delay, so an image that never loads holds nothing up.
				schedulePump(Duration.between(now, firstReadyAt.plus(timing.getGatherDelay())));
				return;
			}
			startUpload();
		}
		else
		{
			startWanted();
		}
	}

	private void startWanted()
	{
		// Keys from events first; the import fills what is left of the request.
		List<IconKey> keys = new ArrayList<>(IconClient.MAX_WANTED_IDS);
		take(pending, keys);
		take(importPending, keys);
		int gen = generation;
		busy = true;
		try
		{
			call = client.wanted(settings.url(), settings.getApiKey(), keys,
				result -> post(() -> onWanted(gen, keys, result)));
		}
		catch (RuntimeException e)
		{
			log.warn("Could not start an icon request", e);
			busy = false;
			askAgain(keys);
			retryLater(null);
		}
	}

	private static void take(Set<IconKey> from, List<IconKey> keys)
	{
		for (Iterator<IconKey> it = from.iterator(); it.hasNext() && keys.size() < IconClient.MAX_WANTED_IDS; )
		{
			keys.add(it.next());
			it.remove();
		}
	}

	/** Whether {@code key} is part of the import in progress (those are only counted, not logged one by one). */
	private boolean imported(IconKey key)
	{
		return run != null && run.open.contains(key);
	}

	/** Puts the keys of a failed {@code wanted} back, in front of newer keys (the order does not matter). */
	private void askAgain(List<IconKey> keys)
	{
		Set<IconKey> events = new LinkedHashSet<>();
		Set<IconKey> imports = new LinkedHashSet<>();
		for (IconKey key : keys)
		{
			(imported(key) ? imports : events).add(key);
		}
		events.addAll(pending);
		pending.clear();
		pending.addAll(events);
		imports.addAll(importPending);
		importPending.clear();
		importPending.addAll(imports);
	}

	private void onWanted(int gen, List<IconKey> keys, IconResult result)
	{
		if (closed || gen != generation)
		{
			return;
		}
		requestDone();
		reportContact(result.getKind());
		if (result.getKind() != IconResult.Kind.OK)
		{
			askAgain(keys);
			failed(result);
			return;
		}
		backoff.reset();
		checked.addAll(keys);
		// Only what we asked about counts as missing.
		Set<IconKey> missing = new HashSet<>(result.getMissing());
		int lacking = 0;
		for (IconKey key : new LinkedHashSet<>(keys))
		{
			boolean fromImport = imported(key);
			if (fromImport)
			{
				run.count(key, ImportRun.CHECKED);
			}
			if (missing.contains(key))
			{
				lacking++;
				if (fromImport)
				{
					run.count(key, ImportRun.MISSING);
					importToLoad.add(key);
				}
				else
				{
					toLoad.add(key);
				}
			}
			else if (fromImport)
			{
				// The server has it: nothing more to do for this key.
				run.open.remove(key);
			}
		}
		log.debug("Server lacks {} of {} icon(s)", lacking, keys.size());
		if (run != null && run.open.isEmpty())
		{
			finishImport();
		}
		pump();
	}

	private void startLoads()
	{
		if (!active() || (toLoad.isEmpty() && importToLoad.isEmpty()))
		{
			return;
		}
		// Icons of events first; the import gets what is left of the tick.
		int started = startLoads(toLoad, 0);
		startLoads(importToLoad, started);
	}

	private int startLoads(Set<IconKey> from, int started)
	{
		for (Iterator<IconKey> it = from.iterator(); it.hasNext() && started < MAX_LOADS_PER_TICK; started++)
		{
			IconKey key = it.next();
			it.remove();
			startLoad(key);
		}
		return started;
	}

	/** Starts one load; its timeout counts from now. */
	private void startLoad(IconKey key)
	{
		int gen = generation;
		ScheduledFuture<?> timeout = executor.schedule(() -> safely(() ->
		{
			if (gen == generation && loading.remove(key) != null)
			{
				// Dropped for the session: it is in checked, so it is not asked about again.
				if (!imported(key))
				{
					log.debug("Item icon {} did not load in time; skipped", key.getItemId());
				}
				skip(key);
			}
		}), timing.getLoadTimeout().toMillis(), TimeUnit.MILLISECONDS);
		loading.put(key, timeout);
		try
		{
			if (key.getKind() == IconKey.Kind.ITEM)
			{
				images.load(key.getItemId(), image -> post(() -> onLoaded(gen, key, image)));
				return;
			}
			Integer sprite = sprites.get(key);
			if (sprite != null)
			{
				images.loadSprite(sprite, image -> post(() -> onLoaded(gen, key, image)));
				return;
			}
		}
		catch (RuntimeException e)
		{
			if (!imported(key))
			{
				log.debug("Could not load item icon {}: {}", key.getItemId(), e.getClass().getSimpleName());
			}
		}
		cancel(loading.remove(key));
		skip(key);
	}

	private void onLoaded(int gen, IconKey key, BufferedImage image)
	{
		if (closed || gen != generation)
		{
			return;
		}
		ScheduledFuture<?> timeout = loading.remove(key);
		if (timeout == null)
		{
			return; // Timed out already, or loaded twice.
		}
		cancel(timeout);
		byte[] png = PngEncoder.encode(key.getKind() == IconKey.Kind.ITEM ? image : padded(image));
		if (png == null)
		{
			if (!imported(key))
			{
				log.debug("Item icon {} is empty or too large; skipped", key.getItemId());
			}
			skip(key);
			return;
		}
		if (ready.size() >= MAX_READY)
		{
			// The server has been failing for a long time; ask again when the item shows up again
			// (or at the next import).
			checked.remove(key);
			resolve(key, ImportRun.SKIPPED);
			return;
		}
		if (ready.isEmpty())
		{
			firstReadyAt = clock.instant();
		}
		ready.add(new IconClient.Icon(key, png));
		schedulePump(ready.size() >= IconClient.MAX_ICONS_PER_REQUEST ? Duration.ZERO : timing.getGatherDelay());
	}

	/**
	 * A skill or boss sprite centred on a {@link #NAMED_SIDE} x {@link #NAMED_SIDE}
	 * canvas, as RuneLite's Hiscore panel shows them. A blank sprite stays blank
	 * (and is skipped by the encoder).
	 */
	static BufferedImage padded(BufferedImage sprite)
	{
		return ImageUtil.resizeCanvas(sprite, NAMED_SIDE, NAMED_SIDE);
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
		if (ready.isEmpty())
		{
			firstReadyAt = null;
		}
		int gen = generation;
		busy = true;
		uploading = new ArrayList<>();
		for (IconClient.Icon icon : batch)
		{
			uploading.add(icon.getKey());
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
		reportContact(result.getKind());
		if (result.getKind() == IconResult.Kind.OK)
		{
			backoff.reset();
			// Rejected icons are not tried again this session (03), and neither is anything
			// the answer left out: the keys stay in checked.
			Set<IconKey> accepted = new HashSet<>(result.getAccepted());
			for (IconClient.Icon icon : batch)
			{
				if (accepted.contains(icon.getKey()))
				{
					resolve(icon.getKey(), ImportRun.UPLOADED);
				}
				else
				{
					skip(icon.getKey());
				}
			}
			log.debug("Uploaded {} icon(s), {} rejected", result.getAccepted().size(),
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

	private void reportContact(IconResult.Kind kind)
	{
		try
		{
			contactListener.accept(kind);
		}
		catch (RuntimeException e)
		{
			log.warn("Contact listener failed", e);
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
	 * Forgets the work in progress, the import included. Icons that were loading
	 * or waiting for upload are no longer checked, so they are asked about again
	 * if the item shows up again (e.g. after "Upload item icons" is turned back on).
	 */
	private void dropWork()
	{
		if (run != null)
		{
			log.debug("Icon import stopped with {} icon(s) left", run.open.size());
		}
		run = null;
		heldImport = null;
		importPending.clear();
		pending.clear();
		checked.removeAll(toLoad);
		toLoad.clear();
		checked.removeAll(importToLoad);
		importToLoad.clear();
		for (IconClient.Icon icon : ready)
		{
			checked.remove(icon.getKey());
		}
		ready.clear();
		firstReadyAt = null;
		for (Map.Entry<IconKey, ScheduledFuture<?>> entry : loading.entrySet())
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
