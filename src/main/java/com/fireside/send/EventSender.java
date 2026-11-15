package com.fireside.send;

import com.fireside.event.Event;
import com.fireside.event.EventJson;
import com.fireside.net.IngestClient;
import com.fireside.net.IngestResponse;
import com.fireside.net.IngestResult;
import com.fireside.net.SemVer;
import com.fireside.net.VerifyClient;
import com.fireside.net.VerifyResult;
import com.fireside.queue.EventQueue;
import com.fireside.queue.QueuedEvent;
import com.fireside.queue.RejectedEvent;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;

/**
 * Writes events to the on-disk queue and sends them to the server in batches.
 * <p>
 * <b>Threading.</b> All queue and state changes happen on one single-thread
 * executor owned by the plugin; every public method only posts a task to it
 * and returns at once. HTTP runs on OkHttp's thread pool ({@code enqueue}),
 * and its callback posts the result back to the executor. So disk writes never
 * wait for the network, and at most one request (verify or batch) is in flight.
 * <p>
 * <b>When it sends:</b> every {@link #SEND_INTERVAL}, when the queue reaches
 * {@link #SEND_THRESHOLD}, at logout, and on "Send now". Batches hold at most
 * {@link #MAX_BATCH_EVENTS} events and {@link #MAX_BATCH_BYTES} bytes. After a
 * successful batch, the events that were already queued behind it go at once.
 * <p>
 * <b>Statuses</b> (03, "POST /ingest", and the M7 decisions in 07):
 * <ul>
 * <li>200: confirmed events ({@code accepted} and {@code duplicates}) are removed;
 * {@code rejected} ones go to the error log. Nothing else is ever removed.</li>
 * <li>400: the whole batch goes to the error log (kept, not deleted).</li>
 * <li>401, 404 and redirects: sending stops until the URL or key changes or
 * "Test connection" succeeds.</li>
 * <li>413: the batch size is halved and it is tried again; a single event
 * that is still too large goes to the error log.</li>
 * <li>429, 5xx, network errors: exponential backoff (at most 5 min, with
 * jitter, at least {@code Retry-After}).</li>
 * </ul>
 * Before the first batch, the server's contract version is checked with
 * {@code verify}. If it is too old, the queue is held (not sent, not moved to
 * the error log) and checked again every {@link #HELD_RECHECK}.
 */
@Slf4j
public class EventSender
{
	public static final Duration SEND_INTERVAL = Duration.ofSeconds(30);
	public static final int SEND_THRESHOLD = 50;
	public static final int MAX_BATCH_EVENTS = 500;
	/** Under the server's 1 MiB body limit, with room to spare. */
	public static final long MAX_BATCH_BYTES = 1_000_000;
	public static final Duration HELD_RECHECK = Duration.ofMinutes(5);

	private enum Contract
	{
		UNKNOWN,
		OK,
		TOO_OLD,
	}

	private final ScheduledExecutorService executor;
	private final EventQueue queue;
	private final EventJson json;
	private final IngestClient ingest;
	private final VerifyClient verifier;
	private final Backoff backoff;
	private final Clock clock;
	private final Duration sendInterval;
	private final Consumer<SenderStatus> listener;

	private volatile SenderStatus status = SenderStatus.STARTING_STATUS;
	/** Told the server's contract version after each verify, and null when the connection changes. */
	private volatile Consumer<SemVer> contractListener = version ->
	{
	};

	// Everything below is only touched on the executor.
	private SenderSettings settings = SenderSettings.OFF;
	private boolean queueOpen;
	private boolean closed;
	/** Events serialized but not yet on disk (only while the disk fails). */
	private final List<String> unsaved = new ArrayList<>();
	@Nullable
	private String storageError;

	/** Bumped when the connection changes, so late results for the old one are ignored. */
	private int generation;
	private boolean busy;
	private boolean verifying;
	@Nullable
	private Call call;

	private Contract contract = Contract.UNKNOWN;
	@Nullable
	private String serverContract;
	@Nullable
	private Instant heldRecheckAt;

	@Nullable
	private SenderStatus.StopReason stopReason;
	@Nullable
	private String stopDetail;

	@Nullable
	private Instant retryAt;
	@Nullable
	private String retryDetail;
	@Nullable
	private ScheduledFuture<?> retryFuture;
	@Nullable
	private ScheduledFuture<?> tickFuture;

	/** Lowered after a 413 (e.g. a proxy with a smaller limit), until the connection changes. */
	private int batchEventLimit = MAX_BATCH_EVENTS;

	@Nullable
	private Instant lastSentAt;
	private int lastSentCount;

	/**
	 * @param listener gets a new status after every change, on the executor
	 */
	public EventSender(ScheduledExecutorService executor, EventQueue queue, EventJson json, IngestClient ingest,
		VerifyClient verifier, Backoff backoff, Clock clock, Duration sendInterval, Consumer<SenderStatus> listener)
	{
		this.executor = executor;
		this.queue = queue;
		this.json = json;
		this.ingest = ingest;
		this.verifier = verifier;
		this.backoff = backoff;
		this.clock = clock;
		this.sendInterval = sendInterval;
		this.listener = listener;
	}

	// ---- Public API: thread-safe, never blocks --------------------------------

	/**
	 * Gets the server's contract version after every successful {@code verify}
	 * (any version, also one too old for events), and {@code null} when the
	 * connection changes. Called on the executor; must not block. Set it before
	 * {@link #start}. Used by the icon uploader.
	 */
	public void setContractListener(Consumer<SemVer> listener)
	{
		contractListener = listener;
	}

	/** Opens the queue and starts the send timer. */
	public void start(SenderSettings initial)
	{
		post(() ->
		{
			settings = initial;
			openQueue();
			long interval = sendInterval.toMillis();
			tickFuture = executor.scheduleWithFixedDelay(() -> safely(() -> trySend(false)), interval, interval,
				TimeUnit.MILLISECONDS);
			trySend(false);
			publish();
		});
	}

	public void updateSettings(SenderSettings newSettings)
	{
		post(() -> applySettings(newSettings));
	}

	/** Queues an event. Create the event (and its ID) when it happens, then call this. */
	public void submit(Event event)
	{
		post(() ->
		{
			String line;
			try
			{
				line = json.toJson(event);
			}
			catch (RuntimeException e)
			{
				log.warn("Could not serialize a {} event", event.getType().wireName(), e);
				return;
			}
			unsaved.add(line);
			persist();
			if (queueOpen && queue.size() >= SEND_THRESHOLD)
			{
				trySend(false);
			}
			publish();
		});
	}

	/** "Send now": skips a backoff wait and checks a held server again. */
	public void sendNow()
	{
		post(() ->
		{
			clearRetry();
			trySend(true);
			publish();
		});
	}

	/** At logout. Queue {@code session.end} and the last snapshot before calling this. */
	public void onLogout()
	{
		post(() -> trySend(false));
	}

	/** Result of the panel's "Test connection". Success clears a stop and updates the contract check. */
	public void onConnectionTested(VerifyResult result)
	{
		post(() ->
		{
			switch (result.getOutcome())
			{
				case OK:
					stopReason = null;
					stopDetail = null;
					storeVerified(result);
					clearRetry();
					backoff.reset();
					trySend(false);
					break;
				case UNAUTHORIZED:
					stop(SenderStatus.StopReason.UNAUTHORIZED, result.getMessage());
					break;
				case NOT_FOUND:
					stop(SenderStatus.StopReason.NOT_FOUND, result.getMessage());
					break;
				default:
					break;
			}
			publish();
		});
	}

	/**
	 * Completes when every event submitted before this call is on disk. Never
	 * waits for the network. For {@code ClientShutdown.waitFor}.
	 */
	public Future<?> flush()
	{
		try
		{
			return executor.submit(() -> safely(this::persist));
		}
		catch (RejectedExecutionException e)
		{
			return CompletableFuture.completedFuture(null);
		}
	}

	/**
	 * Writes what is left, cancels timers and the request in flight. The owner
	 * shuts the executor down afterwards (without waiting for it).
	 */
	public void shutdown()
	{
		post(() ->
		{
			persist();
			closed = true;
			cancel(tickFuture);
			tickFuture = null;
			clearRetry();
			cancelInFlight();
		});
	}

	/** The latest status (any thread). */
	public SenderStatus getStatus()
	{
		return status;
	}

	// ---- Executor only ----------------------------------------------------------

	private void openQueue()
	{
		try
		{
			queue.open();
			queueOpen = true;
			storageError = null;
		}
		catch (IOException e)
		{
			storageProblem("Can't open the event queue", e);
		}
	}

	private void persist()
	{
		if (unsaved.isEmpty())
		{
			return;
		}
		if (!queueOpen)
		{
			openQueue();
			if (!queueOpen)
			{
				return;
			}
		}
		try
		{
			queue.append(new ArrayList<>(unsaved));
			unsaved.clear();
			storageError = null;
		}
		catch (IOException e)
		{
			storageProblem("Can't write the event queue", e);
		}
	}

	private void applySettings(SenderSettings newSettings)
	{
		SenderSettings old = settings;
		settings = newSettings;
		boolean connectionChanged = !newSettings.sameConnection(old);
		if (connectionChanged || !newSettings.isEnabled())
		{
			cancelInFlight();
		}
		if (connectionChanged)
		{
			// A stop, a contract check or a backoff says nothing about the new server or key.
			stopReason = null;
			stopDetail = null;
			contract = Contract.UNKNOWN;
			serverContract = null;
			notifyContract(null);
			clearRetry();
			backoff.reset();
			batchEventLimit = MAX_BATCH_EVENTS;
		}
		trySend(false);
		publish();
	}

	private void trySend(boolean manual)
	{
		if (closed || !queueOpen || busy || stopReason != null
			|| !settings.isEnabled() || !settings.isConfigured())
		{
			return;
		}
		Instant now = clock.instant();
		if (!manual && retryAt != null && now.isBefore(retryAt))
		{
			return;
		}
		if (contract == Contract.TOO_OLD)
		{
			if (manual || heldRecheckAt == null || !now.isBefore(heldRecheckAt))
			{
				startVerify();
			}
			return;
		}
		if (queue.size() == 0)
		{
			return;
		}
		if (contract == Contract.UNKNOWN)
		{
			startVerify();
			return;
		}
		List<QueuedEvent> batch;
		try
		{
			batch = queue.head(batchEventLimit, MAX_BATCH_BYTES);
		}
		catch (IOException e)
		{
			storageProblem("Can't read the event queue", e);
			scheduleRetry("Can't read the event queue file.", null);
			return;
		}
		if (!batch.isEmpty())
		{
			startIngest(batch);
		}
	}

	private void startVerify()
	{
		int gen = generation;
		busy = true;
		verifying = true;
		publish();
		try
		{
			call = verifier.verify(settings.getServerUrl(), settings.getApiKey(),
				result -> post(() -> onVerified(gen, result)));
		}
		catch (RuntimeException e)
		{
			requestNotStarted(e);
		}
	}

	private void onVerified(int gen, VerifyResult result)
	{
		if (closed || gen != generation)
		{
			return;
		}
		busy = false;
		verifying = false;
		call = null;
		switch (result.getOutcome())
		{
			case OK:
				storeVerified(result);
				if (contract == Contract.OK)
				{
					trySend(true);
				}
				break;
			case UNAUTHORIZED:
				stop(SenderStatus.StopReason.UNAUTHORIZED, result.getMessage());
				break;
			case NOT_FOUND:
				stop(SenderStatus.StopReason.NOT_FOUND, result.getMessage());
				break;
			case NOT_CONFIGURED:
			case INVALID_URL:
			case INVALID_KEY:
				// Shown as "not configured"; checked again when the config changes.
				break;
			default:
				scheduleRetry(result.getMessage(), null);
				break;
		}
		publish();
	}

	private void storeVerified(VerifyResult result)
	{
		if (result.getResponse() == null)
		{
			return;
		}
		contract = result.isContractTooOld() ? Contract.TOO_OLD : Contract.OK;
		serverContract = result.getResponse().getContractVersion().toString();
		notifyContract(result.getResponse().getContractVersion());
		heldRecheckAt = clock.instant().plus(HELD_RECHECK);
		if (contract == Contract.TOO_OLD)
		{
			log.debug("Server contract {} is too old; holding the queue", serverContract);
		}
	}

	private void notifyContract(@Nullable SemVer version)
	{
		try
		{
			contractListener.accept(version);
		}
		catch (RuntimeException e)
		{
			// Icons must never get in the way of events.
			log.warn("Contract listener failed", e);
		}
	}

	private void startIngest(List<QueuedEvent> batch)
	{
		String body = IngestClient.body(batch);
		// Events already queued but not in this batch go right after it.
		int backlog = queue.size() - batch.size();
		int gen = generation;
		busy = true;
		publish();
		try
		{
			call = ingest.send(settings.url(), settings.getApiKey(), body,
				result -> post(() -> onIngested(gen, batch, backlog, result)));
		}
		catch (RuntimeException e)
		{
			requestNotStarted(e);
		}
	}

	private void requestNotStarted(RuntimeException e)
	{
		log.warn("Could not start a request", e);
		busy = false;
		verifying = false;
		call = null;
		scheduleRetry("Could not start a request (" + e.getClass().getSimpleName() + ").", null);
		publish();
	}

	private void onIngested(int gen, List<QueuedEvent> batch, int backlog, IngestResult result)
	{
		if (closed)
		{
			return;
		}
		boolean stale = gen != generation;
		if (!stale)
		{
			busy = false;
			call = null;
		}
		if (result.getKind() == IngestResult.Kind.OK)
		{
			// Applied even for an old connection: that server has stored the events.
			boolean progress = confirm(batch, result.getResponse());
			if (!stale)
			{
				if (progress)
				{
					backoff.reset();
					clearRetry();
					if (backlog > 0)
					{
						trySend(false);
					}
				}
				else
				{
					scheduleRetry("The server did not confirm any of the events.", null);
				}
			}
			publish();
			return;
		}
		if (stale)
		{
			return;
		}
		switch (result.getKind())
		{
			case BAD_REQUEST:
				log.debug("Server rejected a batch of {} events (HTTP 400); moving it to the error log", batch.size());
				toErrorLog(batch, 400, result.getMessage());
				backoff.reset();
				clearRetry();
				break;
			case UNAUTHORIZED:
				stop(SenderStatus.StopReason.UNAUTHORIZED, result.getMessage());
				break;
			case NOT_FOUND:
				stop(SenderStatus.StopReason.NOT_FOUND, result.getMessage());
				break;
			case REDIRECT:
				stop(SenderStatus.StopReason.REDIRECT, result.getMessage());
				break;
			case TOO_LARGE:
				if (batch.size() == 1)
				{
					toErrorLog(batch, 413, "Too large for the server (HTTP 413), even on its own.");
				}
				else
				{
					// Halved again on every 413, down to one event per batch.
					batchEventLimit = Math.max(1, batch.size() / 2);
					log.debug("Batch too large (HTTP 413); now at most {} events per batch", batchEventLimit);
				}
				trySend(true);
				break;
			default:
				scheduleRetry(result.getMessage(), result.getRetryAfter());
				break;
		}
		publish();
	}

	/**
	 * Removes confirmed events and logs rejected ones. Returns true if the
	 * server accounted for at least one event of the batch.
	 */
	private boolean confirm(List<QueuedEvent> batch, IngestResponse response)
	{
		Map<String, QueuedEvent> byId = new HashMap<>();
		for (QueuedEvent event : batch)
		{
			byId.put(lower(event.getEventId()), event);
		}
		Set<String> confirmed = new LinkedHashSet<>();
		List<String> ids = new ArrayList<>(response.getAccepted());
		ids.addAll(response.getDuplicates());
		for (String id : ids)
		{
			if (byId.containsKey(lower(id)))
			{
				confirmed.add(lower(id));
			}
		}
		Set<String> handled = new HashSet<>(confirmed);
		List<RejectedEvent> rejected = new ArrayList<>();
		for (IngestResponse.Rejection r : response.getRejected())
		{
			QueuedEvent event = r.getEventId() != null ? byId.get(lower(r.getEventId())) : null;
			if (event == null && r.getIndex() < batch.size())
			{
				event = batch.get(r.getIndex());
			}
			if (event != null && handled.add(lower(event.getEventId())))
			{
				rejected.add(new RejectedEvent(event, 200, r.getError()));
			}
		}
		if (handled.isEmpty())
		{
			return false;
		}
		try
		{
			queue.remove(confirmed, rejected);
			storageError = null;
		}
		catch (IOException e)
		{
			// They stay queued and are sent again; the server reports them as duplicates.
			storageProblem("Can't update the event queue", e);
		}
		if (!confirmed.isEmpty())
		{
			lastSentAt = clock.instant();
			lastSentCount = confirmed.size();
		}
		if (!rejected.isEmpty())
		{
			log.debug("Server rejected {} event(s); moved to the error log", rejected.size());
		}
		if (handled.size() < batch.size())
		{
			log.debug("{} event(s) missing from the server's answer stay queued", batch.size() - handled.size());
		}
		return true;
	}

	private void toErrorLog(List<QueuedEvent> batch, int status, String error)
	{
		List<RejectedEvent> rejected = new ArrayList<>();
		for (QueuedEvent event : batch)
		{
			rejected.add(new RejectedEvent(event, status, error));
		}
		try
		{
			queue.remove(Collections.emptyList(), rejected);
			storageError = null;
		}
		catch (IOException e)
		{
			storageProblem("Can't write the error log", e);
		}
	}

	private void stop(SenderStatus.StopReason reason, String detail)
	{
		log.debug("Sending stopped: {}", reason);
		stopReason = reason;
		stopDetail = detail;
		clearRetry();
		backoff.reset();
	}

	private void scheduleRetry(String detail, @Nullable Duration retryAfter)
	{
		Duration wait = backoff.next(retryAfter);
		log.debug("Sending failed, retrying in {} ms (attempt {})", wait.toMillis(), backoff.attempts());
		retryAt = clock.instant().plus(wait);
		retryDetail = detail;
		cancel(retryFuture);
		retryFuture = executor.schedule(() -> safely(() ->
		{
			retryFuture = null;
			retryAt = null;
			trySend(false);
			publish();
		}), wait.toMillis(), TimeUnit.MILLISECONDS);
	}

	private void clearRetry()
	{
		retryAt = null;
		retryDetail = null;
		cancel(retryFuture);
		retryFuture = null;
	}

	private void cancelInFlight()
	{
		generation++;
		busy = false;
		verifying = false;
		if (call != null)
		{
			call.cancel();
			call = null;
		}
	}

	private void storageProblem(String what, IOException e)
	{
		storageError = what + " (" + e.getClass().getSimpleName()
			+ (e.getMessage() != null ? ": " + e.getMessage() : "") + ").";
		log.warn("{}", what, e);
	}

	private void publish()
	{
		SenderStatus.State state;
		if (!queueOpen)
		{
			state = SenderStatus.State.STARTING;
		}
		else if (!settings.isEnabled())
		{
			state = SenderStatus.State.OFF;
		}
		else if (!settings.isConfigured())
		{
			state = SenderStatus.State.NOT_CONFIGURED;
		}
		else if (stopReason != null)
		{
			state = SenderStatus.State.STOPPED;
		}
		else if (busy)
		{
			state = verifying ? SenderStatus.State.CHECKING : SenderStatus.State.SENDING;
		}
		else if (contract == Contract.TOO_OLD)
		{
			state = SenderStatus.State.HELD;
		}
		else if (retryAt != null)
		{
			state = SenderStatus.State.RETRYING;
		}
		else
		{
			state = SenderStatus.State.IDLE;
		}
		String detail = state == SenderStatus.State.STOPPED ? stopDetail
			: state == SenderStatus.State.RETRYING ? retryDetail : null;
		SenderStatus next = new SenderStatus(state, queueOpen ? queue.size() + unsaved.size() : unsaved.size(),
			queueOpen ? queue.rejectedCount() : 0, lastSentAt, lastSentCount,
			state == SenderStatus.State.RETRYING ? retryAt : null, detail,
			state == SenderStatus.State.STOPPED ? stopReason : null,
			contract == Contract.TOO_OLD ? serverContract : null, storageError);
		if (!next.equals(status))
		{
			status = next;
			listener.accept(next);
		}
	}

	/** Runs a task on the executor. After shutdown, tasks (and late HTTP results) are dropped. */
	private void post(Runnable task)
	{
		try
		{
			executor.execute(() -> safely(task));
		}
		catch (RejectedExecutionException e)
		{
			log.debug("Sender is shut down; task dropped");
		}
	}

	/** An exception must not kill the timer or go unnoticed. */
	private static void safely(Runnable task)
	{
		try
		{
			task.run();
		}
		catch (RuntimeException e)
		{
			log.warn("Unexpected error in the event sender", e);
		}
	}

	private static void cancel(@Nullable Future<?> future)
	{
		if (future != null)
		{
			future.cancel(false);
		}
	}

	private static String lower(String id)
	{
		return id.toLowerCase(Locale.ROOT);
	}
}
