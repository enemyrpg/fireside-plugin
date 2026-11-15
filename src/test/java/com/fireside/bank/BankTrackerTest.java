package com.fireside.bank;

import static com.fireside.bank.BankSnapshotsTest.slot;
import static com.fireside.bank.BankSnapshotsTest.tabs;
import static com.fireside.bank.FakeBankItems.AGS;
import static com.fireside.bank.FakeBankItems.SHARKS;
import static com.fireside.bank.FakeBankItems.SUPER_COMBAT_1;
import static com.fireside.bank.FakeBankItems.SUPER_COMBAT_2;
import static com.fireside.bank.FakeBankItems.SUPER_COMBAT_3;
import static com.fireside.bank.FakeBankItems.SUPER_COMBAT_4;
import static com.fireside.bank.FakeBankItems.WHIP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fireside.FiresideConfig;
import com.fireside.event.BankSnapshot;
import com.fireside.event.Event;
import com.fireside.event.EventPayload;
import com.fireside.event.EventType;
import com.fireside.event.PotionStorageSnapshot;
import com.fireside.event.SessionEnd;
import com.fireside.event.UuidV7Generator;
import com.fireside.net.SemVer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

/** Change detection, throttling, flushing and old servers, with fakes for the game and the config. */
public class BankTrackerTest
{
	private static final long ACCOUNT = 1234567890123456789L;
	private static final long OTHER_ACCOUNT = 42L;
	private static final String PROFILE = "rsprofile.abc";
	private static final String OTHER_PROFILE = "rsprofile.def";
	private static final Instant START = Instant.parse("2026-11-15T18:00:00.000Z");

	private static final class MovingClock extends Clock
	{
		Instant now = START;

		void advance(Duration d)
		{
			now = now.plus(d);
		}

		@Override
		public Instant instant()
		{
			return now;
		}

		@Override
		public ZoneId getZone()
		{
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return this;
		}
	}

	/** The game as the tracker reads it. */
	private static final class FakeView implements BankView
	{
		boolean loggedIn = true;
		boolean standard = true;
		@Nullable
		List<Slot> bank = new ArrayList<>(Arrays.asList(slot(WHIP, 1), slot(AGS, 1)));
		int[] tabs = tabs(1);
		List<Slot> inventory = new ArrayList<>();
		List<RawPotion> potions = new ArrayList<>(Collections.singletonList(
			new RawPotion(new int[]{SUPER_COMBAT_1, SUPER_COMBAT_2, SUPER_COMBAT_3, SUPER_COMBAT_4}, 40)));

		@Override
		public boolean loggedIn()
		{
			return loggedIn;
		}

		@Override
		public boolean standardWorld()
		{
			return standard;
		}

		@Nullable
		@Override
		public List<Slot> bank()
		{
			return bank;
		}

		@Override
		public int[] tabSizes()
		{
			return tabs;
		}

		@Override
		public List<Slot> inventory()
		{
			return inventory;
		}

		@Override
		public Map<String, Slot> equipment()
		{
			return new LinkedHashMap<>();
		}

		@Override
		public List<RawPotion> potionStorage()
		{
			return potions;
		}

		@Override
		public int potionStorageVials()
		{
			return 0;
		}
	}

	/** RS profile config: profile → key → hash. Survives a "restart" (a new tracker). */
	private static final class MapStore implements HashStore
	{
		final Map<String, Map<String, String>> profiles = new HashMap<>();
		String current = PROFILE;

		@Nullable
		@Override
		public String currentProfile()
		{
			return current;
		}

		@Nullable
		@Override
		public String get(String profile, String key)
		{
			return profiles.getOrDefault(profile, Collections.emptyMap()).get(key);
		}

		@Override
		public void set(String profile, String key, String hash)
		{
			profiles.computeIfAbsent(profile, p -> new HashMap<>()).put(key, hash);
		}

		@Override
		public void clearAll(String key)
		{
			for (Map<String, String> values : profiles.values())
			{
				values.remove(key);
			}
		}
	}

	private final MovingClock clock = new MovingClock();
	private final UuidV7Generator ids = new UuidV7Generator();
	private final FakeView view = new FakeView();
	private final MapStore store = new MapStore();
	private final List<Event> queued = new ArrayList<>();
	private final List<String> statuses = new ArrayList<>();
	private final String sessionId = ids.next();
	private boolean enabled = true;
	private boolean inSession = true;
	private long account = ACCOUNT;
	private BankTracker tracker;

	@Before
	public void setUp()
	{
		tracker = newTracker();
	}

	private BankTracker newTracker()
	{
		return new BankTracker(view, new BankSnapshots(new FakeBankItems()), store, this::create, queued::add,
			clock, () -> enabled, statuses::add);
	}

	@Nullable
	private Event create(EventPayload payload)
	{
		return inSession ? Event.create(ids, account, clock.instant(), sessionId, payload) : null;
	}

	private List<Event> banks()
	{
		return queued.stream().filter(e -> e.getType() == EventType.BANK_SNAPSHOT).collect(Collectors.toList());
	}

	private List<Event> potionEvents()
	{
		return queued.stream().filter(e -> e.getType() == EventType.POTION_STORAGE_SNAPSHOT)
			.collect(Collectors.toList());
	}

	private static BankSnapshot bankOf(Event event)
	{
		return (BankSnapshot) event.getPayload();
	}

	private void deposit(int itemId, int quantity)
	{
		view.bank.add(slot(itemId, quantity));
	}

	private void tick(Duration after)
	{
		clock.advance(after);
		tracker.onTick();
	}

	@Test
	public void firstChangeIsSentAtOnceAndUnchangedBankNever()
	{
		tracker.onBankClosed();
		assertEquals(1, banks().size());
		assertEquals(1, potionEvents().size());

		for (int i = 0; i < 5; i++)
		{
			tick(Duration.ofMinutes(20));
			tracker.onBankClosed();
		}
		tracker.flush();
		assertEquals("unchanged bank is never sent again", 1, banks().size());
		assertEquals(1, potionEvents().size());
		assertEquals(bankOf(banks().get(0)).getContentHash(), store.get(PROFILE, FiresideConfig.BANK_HASH_KEY));
	}

	@Test
	public void unchangedBankIsNotSentAfterARestartOrANewLogin()
	{
		tracker.onBankClosed();
		tracker.flush();
		queued.clear();

		// New client run: new tracker, same RS profile config.
		tracker = newTracker();
		tracker.onBankClosed();
		tracker.flush();
		assertTrue(queued.isEmpty());

		// Log out and in: flush, then the same bank again.
		tracker.flush();
		tick(Duration.ofSeconds(30));
		tracker.onBankClosed();
		tracker.flush();
		assertTrue(queued.isEmpty());
	}

	@Test
	public void changesInsideTheWindowAreHeldAndOnlyTheLastIsSentWhenItEnds()
	{
		tracker.onBankClosed();
		assertEquals(1, banks().size());

		tick(Duration.ofMinutes(2));
		deposit(SHARKS, 10);
		tracker.onBankClosed();
		Instant lastCapture = clock.instant().plus(Duration.ofMinutes(3));
		tick(Duration.ofMinutes(3));
		view.bank.set(2, slot(SHARKS, 20));
		tracker.onBankClosed();
		assertEquals("held inside the window", 1, banks().size());

		tick(Duration.ofMinutes(9));
		assertEquals("window not over yet (14 min)", 1, banks().size());
		tick(Duration.ofMinutes(1));
		assertEquals(2, banks().size());
		Event sent = banks().get(1);
		assertEquals("the last change", 3, bankOf(sent).getItems().size());
		assertEquals(20, bankOf(sent).getItems().get(2).getQuantity());
		assertEquals("the capture's time, not the send time", lastCapture, sent.getOccurredAt());

		tick(Duration.ofMinutes(30));
		tracker.flush();
		assertEquals(2, banks().size());
	}

	@Test
	public void aChangeAfterTheWindowIsSentAtOnce()
	{
		tracker.onBankClosed();
		tick(Duration.ofMinutes(15));
		deposit(SHARKS, 1);
		tracker.onBankClosed();
		assertEquals(2, banks().size());
	}

	@Test
	public void goingBackToTheQueuedBankDropsTheHeldChange()
	{
		tracker.onBankClosed();
		deposit(SHARKS, 10);
		tick(Duration.ofMinutes(1));
		tracker.onBankClosed();
		view.bank.remove(view.bank.size() - 1);
		tick(Duration.ofMinutes(1));
		tracker.onBankClosed();

		tick(Duration.ofMinutes(20));
		tracker.flush();
		assertEquals("the server already has this bank", 1, banks().size());
	}

	@Test
	public void logoutHopAndClientCloseQueueTheHeldSnapshotBeforeSessionEnd()
	{
		tracker.onBankClosed();
		deposit(SHARKS, 10);
		tick(Duration.ofMinutes(1));
		tracker.onBankClosed();
		assertEquals(1, banks().size());

		// FiresidePlugin flushes before SessionTracker emits session.end into the same sink.
		tracker.flush();
		queued.add(create(new SessionEnd(SessionEnd.Reason.LOGOUT)));

		assertEquals(2, banks().size());
		int bankAt = queued.indexOf(banks().get(1));
		int endAt = queued.size() - 1;
		assertEquals(EventType.SESSION_END, queued.get(endAt).getType());
		assertTrue("bank before session.end", bankAt < endAt);
		assertEquals(sessionId, banks().get(1).getSessionId());
		assertEquals(bankOf(banks().get(1)).getContentHash(), store.get(PROFILE, FiresideConfig.BANK_HASH_KEY));
	}

	@Test
	public void theHashIsStoredOnTheProfileOfTheCaptureEvenIfTheProfileChangedSince()
	{
		tracker.onBankClosed();
		deposit(SHARKS, 10);
		tick(Duration.ofMinutes(1));
		tracker.onBankClosed();
		// RuneLite may switch or clear the RS profile during logout, before the flush.
		store.current = null;
		tracker.flush();
		assertEquals(bankOf(banks().get(1)).getContentHash(), store.get(PROFILE, FiresideConfig.BANK_HASH_KEY));
	}

	@Test
	public void anotherAccountHasItsOwnHashAndWindow()
	{
		tracker.onBankClosed();
		tracker.flush();
		store.current = OTHER_PROFILE;
		account = OTHER_ACCOUNT;
		tick(Duration.ofMinutes(1));
		tracker.onBankClosed();
		assertEquals("same content, other account: sent at once", 2, banks().size());
		assertEquals(OTHER_ACCOUNT, banks().get(1).getAccountHash());
	}

	@Test
	public void turningBankOffDropsTheHeldSnapshot()
	{
		tracker.onBankClosed();
		deposit(SHARKS, 10);
		tick(Duration.ofMinutes(1));
		tracker.onBankClosed();
		enabled = false;
		tracker.onSettingsChanged();
		tracker.flush();
		tick(Duration.ofMinutes(30));
		tracker.onBankClosed();
		assertEquals(1, banks().size());
	}

	@Test
	public void nothingIsReadOffStandardWorldsWhileLoggedOutWithoutABankOrSession()
	{
		view.standard = false;
		tracker.onBankClosed();
		view.standard = true;
		view.loggedIn = false;
		tracker.onBankClosed();
		view.loggedIn = true;
		List<BankView.Slot> bank = view.bank;
		view.bank = null;
		tracker.onBankClosed();
		view.bank = bank;
		store.current = null;
		tracker.onBankClosed();
		store.current = PROFILE;
		inSession = false;
		tracker.onBankClosed();
		tracker.flush();
		assertTrue(queued.isEmpty());
		assertNull("no hash for what was not queued", store.get(PROFILE, FiresideConfig.BANK_HASH_KEY));

		inSession = true;
		tracker.onBankClosed();
		assertEquals(1, banks().size());
	}

	@Test
	public void potionStorageIsThrottledOnItsOwn()
	{
		tracker.onBankClosed();
		tick(Duration.ofMinutes(1));
		view.potions = new ArrayList<>(Collections.singletonList(new BankView.RawPotion(
			new int[]{SUPER_COMBAT_1, SUPER_COMBAT_2, SUPER_COMBAT_3, SUPER_COMBAT_4}, 36)));
		tracker.onBankClosed();
		assertEquals("bank unchanged", 1, banks().size());
		assertEquals("held", 1, potionEvents().size());
		tick(Duration.ofMinutes(14));
		assertEquals(2, potionEvents().size());
		assertEquals(36, ((PotionStorageSnapshot) potionEvents().get(1).getPayload()).getPotions().get(0)
			.getDoses());
		assertEquals(1, banks().size());
	}

	@Test
	public void aSkippedBankShowsAStatusUntilTheNextGoodSnapshot()
	{
		view.bank = new ArrayList<>(Arrays.asList(slot(WHIP, 1), slot(ItemID.BANK_FILLER, 0)));
		tracker.onBankClosed();
		assertTrue(banks().isEmpty());
		assertEquals("potion storage is still sent", 1, potionEvents().size());
		assertEquals(Collections.singletonList(BankTracker.SKIPPED_STATUS), statuses);
		assertNull(store.get(PROFILE, FiresideConfig.BANK_HASH_KEY));

		tracker.onBankClosed();
		assertEquals("shown once", 1, statuses.size());

		view.bank = new ArrayList<>(Arrays.asList(slot(WHIP, 1), slot(ItemID.BANK_FILLER, 1)));
		tracker.onBankClosed();
		assertEquals(1, banks().size());
		assertEquals(Arrays.asList(BankTracker.SKIPPED_STATUS, null), statuses);
	}

	@Test
	public void anOldServerClearsTheHashesSoTheBankIsSentAgainAfterTheUpgrade()
	{
		// Version unknown (offline): queued as normal, hash stored.
		tracker.onServerContract(null);
		tracker.onBankClosed();
		assertEquals(1, banks().size());
		String hash = bankOf(banks().get(0)).getContentHash();
		assertEquals(hash, store.get(PROFILE, FiresideConfig.BANK_HASH_KEY));
		store.set(OTHER_PROFILE, FiresideConfig.BANK_HASH_KEY, "sha256:other");
		// A held change too.
		deposit(SHARKS, 1);
		tick(Duration.ofMinutes(1));
		tracker.onBankClosed();

		// verify: the server is older than 1.19.0, so it rejects these events.
		tracker.onServerContract(SemVer.parse("1.18.0"));
		assertNull(store.get(PROFILE, FiresideConfig.BANK_HASH_KEY));
		assertNull(store.get(PROFILE, FiresideConfig.POTION_STORAGE_HASH_KEY));
		assertNull("every account", store.get(OTHER_PROFILE, FiresideConfig.BANK_HASH_KEY));
		tracker.flush();
		assertEquals("the held change is dropped", 1, banks().size());

		// Nothing is read while the server is old.
		tick(Duration.ofMinutes(30));
		tracker.onBankClosed();
		tracker.flush();
		assertEquals(1, banks().size());
		assertNull(store.get(PROFILE, FiresideConfig.BANK_HASH_KEY));

		// Upgraded: the next close sends the same bank again, at once.
		tracker.onServerContract(SemVer.parse("1.19.0"));
		tracker.onBankClosed();
		assertEquals(2, banks().size());
		assertEquals(2, potionEvents().size());
	}
}
