package com.fireside.bank;

import com.fireside.event.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * Change detection and throttling for one kind of snapshot (bank, potion
 * storage, quests or diaries), per account.
 * <ul>
 * <li>A snapshot whose hash equals the last <i>queued</i> one (stored per
 * account) is never sent.</li>
 * <li>At most one snapshot is queued per account per {@code window}. A change
 * inside the window is held; a newer change replaces it, and the held one is
 * queued when the window ends, or at {@link #flush} (logout, hop, client
 * close, plugin stop), so the last state is never lost.</li>
 * <li>The event is created when the snapshot is read (its ID and time are the
 * capture's), even when it is queued later.</li>
 * </ul>
 * Not thread-safe: the tracker that owns it synchronizes.
 */
public final class SnapshotThrottle
{
	@Value
	private static class Pending
	{
		Event event;
		String hash;
		String profile;
	}

	private final String key;
	private final HashStore store;
	private final Duration window;
	private final Consumer<Event> sink;
	/** When each account last had a snapshot queued (this client run only). */
	private final Map<Long, Instant> queuedAt = new HashMap<>();
	@Nullable
	private Pending pending;

	public SnapshotThrottle(String key, HashStore store, Duration window, Consumer<Event> sink)
	{
		this.key = key;
		this.store = store;
		this.window = window;
		this.sink = sink;
	}

	/**
	 * A snapshot with {@code hash} was just read. {@code create} makes its
	 * event (null without an active session) and is only called if it is new.
	 */
	public void offer(String profile, String hash, Supplier<Event> create, Instant now)
	{
		if (pending != null && !pending.getProfile().equals(profile))
		{
			// Another account (should have been flushed at logout): its last state goes first.
			submitPending(now);
		}
		if (pending != null && pending.getHash().equals(hash))
		{
			return;
		}
		if (hash.equals(store.get(profile, key)))
		{
			// Back to what was last queued: nothing to send, and the held change is outdated.
			pending = null;
			return;
		}
		Event event = create.get();
		if (event == null)
		{
			return;
		}
		if (windowOver(event.getAccountHash(), now))
		{
			pending = null;
			submit(event, hash, profile, now);
		}
		else
		{
			pending = new Pending(event, hash, profile);
		}
	}

	/** Every game tick: queues the held snapshot once its window is over. */
	public void onTick(Instant now)
	{
		if (pending != null && windowOver(pending.getEvent().getAccountHash(), now))
		{
			submitPending(now);
		}
	}

	/** Queues the held snapshot now. */
	public void flush(Instant now)
	{
		if (pending != null)
		{
			submitPending(now);
		}
	}

	/** Forgets the held snapshot (sending turned off). */
	public void drop()
	{
		pending = null;
	}

	/**
	 * The server cannot take these snapshots: forgets the held one, the
	 * windows and every account's stored hash, so the next bank close after
	 * an upgrade sends again (also a snapshot the server rejected).
	 */
	public void forget()
	{
		pending = null;
		queuedAt.clear();
		store.clearAll(key);
	}

	public boolean hasPending()
	{
		return pending != null;
	}

	private boolean windowOver(long accountHash, Instant now)
	{
		Instant last = queuedAt.get(accountHash);
		return last == null || !now.isBefore(last.plus(window));
	}

	private void submitPending(Instant now)
	{
		Pending held = pending;
		pending = null;
		submit(held.getEvent(), held.getHash(), held.getProfile(), now);
	}

	private void submit(Event event, String hash, String profile, Instant now)
	{
		sink.accept(event);
		store.set(profile, key, hash);
		queuedAt.put(event.getAccountHash(), now);
	}
}
