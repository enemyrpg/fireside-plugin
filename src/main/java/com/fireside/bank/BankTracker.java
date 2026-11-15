package com.fireside.bank;

import com.fireside.Contract;
import com.fireside.FiresideConfig;
import com.fireside.event.BankSnapshot;
import com.fireside.event.Event;
import com.fireside.event.EventPayload;
import com.fireside.event.PotionStorageSnapshot;
import com.fireside.net.SemVer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;

/**
 * {@code bank.snapshot} and {@code potion_storage.snapshot} (10.1, 10.2).
 * <p>
 * When the bank closes, the bank, inventory, equipment and potion storage are
 * read from the item containers and varbits (never from widgets or other
 * plugins), and each kind goes through its own {@link SnapshotThrottle}: only
 * a change since the last queued snapshot of that account, at most one per
 * {@link #WINDOW}, and the last change is always queued at {@link #flush}.
 * <p>
 * Only on standard worlds, only while "Bank" is on and a session is active,
 * and not while the server is known to be older than
 * {@link Contract#BANK_VERSION}. {@link #onBankClosed} reads the client and
 * must run on the client thread; the other methods are synchronized and may
 * be called from any thread (plugin stop, client shutdown, the sender's
 * contract listener).
 */
@Slf4j
public class BankTracker
{
	/** At most one snapshot of each kind per account in this time. */
	public static final Duration WINDOW = Duration.ofMinutes(15);

	/** Panel line while the last bank read was skipped. */
	public static final String SKIPPED_STATUS = "Bank not sent: unexpected bank layout. The next bank close tries again.";

	private static final SemVer BANK_VERSION = SemVer.parse(Contract.BANK_VERSION);

	private final BankView view;
	private final BankSnapshots snapshots;
	private final HashStore store;
	private final Function<EventPayload, Event> createInSession;
	private final Clock clock;
	private final BooleanSupplier enabled;
	private final Consumer<String> status;
	private final SnapshotThrottle bank;
	private final SnapshotThrottle potions;

	private boolean serverTooOld;
	@Nullable
	private String shownStatus;

	/**
	 * @param createInSession makes an event in the active session, created now, without queuing it; null
	 *     without an active session
	 * @param sink queues an event
	 * @param enabled "Bank" (and "Send data") is on
	 * @param status the panel line, or null to clear it; called on the client thread
	 */
	public BankTracker(BankView view, BankSnapshots snapshots, HashStore store,
		Function<EventPayload, Event> createInSession, Consumer<Event> sink, Clock clock, BooleanSupplier enabled,
		Consumer<String> status)
	{
		this.view = view;
		this.snapshots = snapshots;
		this.store = store;
		this.createInSession = createInSession;
		this.clock = clock;
		this.enabled = enabled;
		this.status = status;
		this.bank = new SnapshotThrottle(FiresideConfig.BANK_HASH_KEY, store, WINDOW, sink);
		this.potions = new SnapshotThrottle(FiresideConfig.POTION_STORAGE_HASH_KEY, store, WINDOW, sink);
	}

	/** After the bank closed (outside script execution). Reads everything in this one tick. */
	public synchronized void onBankClosed()
	{
		if (!enabled.getAsBoolean() || serverTooOld || !view.loggedIn() || !view.standardWorld())
		{
			return;
		}
		String profile = store.currentProfile();
		List<BankView.Slot> bankSlots = view.bank();
		if (profile == null || bankSlots == null)
		{
			return;
		}
		Instant now = clock.instant();

		BankSnapshots.BankRead read = snapshots.bank(bankSlots, view.tabSizes(), view.inventory(), view.equipment());
		if (read.getSnapshot() == null)
		{
			// Never item names or contents: only the position and quantity that did not fit.
			log.info("Bank snapshot skipped: {}", read.getSkipReason());
			setStatus(SKIPPED_STATUS);
		}
		else
		{
			if (log.isDebugEnabled())
			{
				logRead(read.getSnapshot());
			}
			setStatus(null);
			bank.offer(profile, read.getSnapshot().getContentHash(), () -> createInSession.apply(read.getSnapshot()),
				now);
		}

		PotionStorageSnapshot potionSnapshot = snapshots.potions(view.potionStorage(), view.potionStorageVials());
		potions.offer(profile, potionSnapshot.getContentHash(), () -> createInSession.apply(potionSnapshot), now);
	}

	/** Every game tick: queues held snapshots whose window is over. */
	public synchronized void onTick()
	{
		Instant now = clock.instant();
		bank.onTick(now);
		potions.onTick(now);
	}

	/** Logout, hop, client close or plugin stop: queues held snapshots now, before {@code session.end}. */
	public synchronized void flush()
	{
		Instant now = clock.instant();
		bank.flush(now);
		potions.flush(now);
	}

	/** After a config change: turning "Bank" or "Send data" off forgets held snapshots. */
	public synchronized void onSettingsChanged()
	{
		if (!enabled.getAsBoolean())
		{
			bank.drop();
			potions.drop();
			setStatus(null);
		}
	}

	/**
	 * The server's contract from {@code verify} (null when unknown). A server
	 * older than {@link Contract#BANK_VERSION} rejects these events, so held
	 * snapshots and every stored hash are forgotten, and nothing is read until
	 * a newer server is reported.
	 */
	public synchronized void onServerContract(@Nullable SemVer version)
	{
		boolean tooOld = version != null && version.compareTo(BANK_VERSION) < 0;
		serverTooOld = tooOld;
		if (tooOld)
		{
			log.debug("Server contract {} has no bank snapshots; clearing bank hashes", version);
			bank.forget();
			potions.forget();
		}
	}

	/** Counts and filler quantities only: never item names or contents. */
	private static void logRead(BankSnapshot snapshot)
	{
		Set<Integer> fillerQuantities = new TreeSet<>();
		for (BankSnapshot.Item item : snapshot.getItems())
		{
			if (item.getItemId() == ItemID.BANK_FILLER)
			{
				fillerQuantities.add(item.getQuantity());
			}
		}
		log.debug("Bank read: {} slots, tabs {}, filler quantities {}", snapshot.getItems().size(),
			snapshot.getTabSizes(), fillerQuantities);
	}

	private void setStatus(@Nullable String text)
	{
		if (text == null ? shownStatus != null : !text.equals(shownStatus))
		{
			shownStatus = text;
			status.accept(text);
		}
	}
}
