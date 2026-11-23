package com.fireside.quest;

import com.fireside.Contract;
import com.fireside.FiresideConfig;
import com.fireside.bank.HashStore;
import com.fireside.bank.SnapshotThrottle;
import com.fireside.event.DiariesSnapshot;
import com.fireside.event.Event;
import com.fireside.event.EventPayload;
import com.fireside.event.QuestsSnapshot;
import com.fireside.net.SemVer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * {@code quests.snapshot} and {@code diaries.snapshot}.
 * <p>
 * <b>Never before the login data is complete.</b> The server treats an
 * account's first snapshot as the baseline ("finished before tracking"), so
 * an empty or partial one would put a false date on everything in the next.
 * After login nothing is read until a session is active and the stats have
 * been loaded for {@link #SETTLE_TICKS} game ticks and {@link #SETTLE_TIME}
 * (game ticks can come in a burst right after login). Then everything is read twice,
 * {@link #CONFIRM_TICKS} apart, and only when both reads are identical and
 * the quest points fit the quest states are the snapshots offered. Until then
 * the reads repeat.
 * <p>
 * <b>After that, reads only on a trigger</b>, never every tick: a diary
 * varbit changed (48 varbit reads on the next tick); the quest points or a
 * completed-count varbit changed (a quest read {@link #TRIGGER_DELAY} later,
 * at most one per {@link #MIN_GAP}); and every {@link #INTERVAL}, because
 * there is no cheap signal for a quest that was only started.
 * <p>
 * A quest read runs one client script per quest. It is timed, and it stops
 * for the tick once {@link #TICK_BUDGET_NANOS} is used and continues on the
 * next, so it cannot cause a hitch however slow the scripts are.
 * <p>
 * Each snapshot goes through a {@link SnapshotThrottle}: only a change since
 * the account's last queued one (hash kept across restarts), and at most one
 * per {@link #WINDOW}. Only on standard worlds, and not while the server is
 * known to be older than {@link Contract#QUESTS_VERSION}.
 * <p>
 * {@link #onTick} and {@link #onVarChanged} read the client and must run on
 * the client thread; the other methods may be called from any thread.
 */
@Slf4j
public class QuestTracker
{
	/** Game ticks with the stats loaded before the first read after login. */
	public static final int SETTLE_TICKS = 5;
	/** Time with the stats loaded before the first read after login: what the settle ticks normally take. */
	public static final Duration SETTLE_TIME = Duration.ofMillis(600L * (SETTLE_TICKS - 1));
	/** Game ticks between the reads that must agree. */
	public static final int CONFIRM_TICKS = 3;
	/** At most one snapshot of each kind per account in this time. */
	public static final Duration WINDOW = Duration.ofMinutes(1);
	/** How often the quests are read without a trigger. */
	public static final Duration INTERVAL = Duration.ofMinutes(5);
	/** From a trigger to the read: the game may set several variables over a tick or two. */
	public static final Duration TRIGGER_DELAY = Duration.ofMillis(1200);
	/** At least this long between two quest reads. */
	public static final Duration MIN_GAP = Duration.ofSeconds(10);
	/** Time a quest read may use in one game tick before it continues on the next. */
	static final long TICK_BUDGET_NANOS = 2_000_000;
	/** Quests read per tick whatever the time, so a read always ends. */
	static final int MIN_PER_TICK = 8;

	private static final SemVer QUESTS_VERSION = SemVer.parse(Contract.QUESTS_VERSION);
	private static final Set<Integer> DIARY_VARBITS = new HashSet<>();
	/** Varbits that change when a quest or miniquest is finished. */
	private static final Set<Integer> QUEST_VARBITS = new HashSet<>();

	static
	{
		for (DiaryVarbits.Entry diary : DiaryVarbits.ALL)
		{
			DIARY_VARBITS.add(diary.getVarbit());
		}
		QUEST_VARBITS.add(VarbitID.QUESTS_COMPLETED_COUNT);
		QUEST_VARBITS.add(VarbitID.MINIQUESTS_COMPLETED_COUNT);
	}

	private enum Phase
	{
		/** After login: waiting for a session and loaded stats. */
		WAITING,
		/** Reading until two reads agree. */
		CONFIRMING,
		/** The baseline was offered; reads on triggers. */
		READY,
	}

	private final QuestView view;
	private final HashStore store;
	private final Function<EventPayload, Event> createInSession;
	private final BooleanSupplier sessionActive;
	private final Clock clock;
	private final BooleanSupplier enabled;
	private final LongSupplier nanoTime;
	private final SnapshotThrottle quests;
	private final SnapshotThrottle diaries;

	private boolean serverTooOld;
	private Phase phase = Phase.WAITING;
	private int settledTicks;
	/** When the stats were first seen loaded in this wait. */
	@Nullable
	private Instant settledSince;
	/** Ticks until the next confirming read. */
	private int confirmWait;
	@Nullable
	private String firstQuestsHash;
	@Nullable
	private String firstDiariesHash;

	/** The quest read in progress, or null. */
	@Nullable
	private List<QuestView.RawQuest> reading;
	private long readNanos;
	private int readTicks;

	// While READY.
	@Nullable
	private Instant questsDue;
	@Nullable
	private Instant lastQuestRead;
	private boolean diariesDirty;
	/** A trigger arrived during a read: that read is dropped and the quests are read again. */
	private boolean changedDuringRead;

	/**
	 * @param createInSession makes an event in the active session, created now, without queuing it; null
	 *     without an active session
	 * @param sessionActive a session is running
	 * @param sink queues an event
	 * @param enabled "Quests and diaries" (and "Send data") is on
	 * @param nanoTime {@code System::nanoTime}
	 */
	public QuestTracker(QuestView view, HashStore store, Function<EventPayload, Event> createInSession,
		BooleanSupplier sessionActive, Consumer<Event> sink, Clock clock, BooleanSupplier enabled,
		LongSupplier nanoTime)
	{
		this.view = view;
		this.store = store;
		this.createInSession = createInSession;
		this.sessionActive = sessionActive;
		this.clock = clock;
		this.enabled = enabled;
		this.nanoTime = nanoTime;
		this.quests = new SnapshotThrottle(FiresideConfig.QUESTS_HASH_KEY, store, WINDOW, sink);
		this.diaries = new SnapshotThrottle(FiresideConfig.DIARIES_HASH_KEY, store, WINDOW, sink);
	}

	/** Every game tick. */
	public synchronized void onTick()
	{
		Instant now = clock.instant();
		quests.onTick(now);
		diaries.onTick(now);
		if (!canRead())
		{
			resetProgress();
			return;
		}
		switch (phase)
		{
			case WAITING:
				if (!view.statsLoaded())
				{
					settledTicks = 0;
					settledSince = null;
					return;
				}
				if (settledSince == null)
				{
					settledSince = now;
				}
				if (++settledTicks < SETTLE_TICKS || now.isBefore(settledSince.plus(SETTLE_TIME)))
				{
					return;
				}
				phase = Phase.CONFIRMING;
				startRead();
				continueRead(now);
				return;
			case CONFIRMING:
				if (reading == null)
				{
					if (--confirmWait > 0)
					{
						return;
					}
					startRead();
				}
				continueRead(now);
				return;
			default:
				if (diariesDirty)
				{
					diariesDirty = false;
					offerDiaries(QuestSnapshots.diaries(view::varbit), now);
				}
				if (reading == null)
				{
					if (questsDue == null || now.isBefore(questsDue)
						|| (lastQuestRead != null && now.isBefore(lastQuestRead.plus(MIN_GAP))))
					{
						return;
					}
					startRead();
				}
				continueRead(now);
		}
	}

	/**
	 * {@code VarbitChanged}: two set lookups, nothing is read here.
	 *
	 * @param varbitId -1 for a varplayer
	 */
	public synchronized void onVarChanged(int varpId, int varbitId)
	{
		if (phase != Phase.READY)
		{
			// The confirming reads see every change up to the baseline.
			return;
		}
		if (varbitId == -1 ? varpId == VarPlayerID.QP : QUEST_VARBITS.contains(varbitId))
		{
			if (reading != null)
			{
				changedDuringRead = true;
			}
			else
			{
				Instant due = clock.instant().plus(TRIGGER_DELAY);
				if (questsDue == null || due.isBefore(questsDue))
				{
					questsDue = due;
				}
			}
		}
		else if (DIARY_VARBITS.contains(varbitId))
		{
			diariesDirty = true;
		}
	}

	/** Logout or hop: the next login waits for its own data. Call after {@link #flush}. */
	public synchronized void reset()
	{
		resetProgress();
	}

	/** Logout, hop, client close or plugin stop: queues held snapshots now, before {@code session.end}. */
	public synchronized void flush()
	{
		Instant now = clock.instant();
		quests.flush(now);
		diaries.flush(now);
	}

	/** After a config change: turning "Quests and diaries" or "Send data" off forgets held snapshots. */
	public synchronized void onSettingsChanged()
	{
		if (!enabled.getAsBoolean())
		{
			quests.drop();
			diaries.drop();
			resetProgress();
		}
	}

	/**
	 * The server's contract from {@code verify} (null when unknown). A server
	 * older than {@link Contract#QUESTS_VERSION} rejects these events, so held
	 * snapshots and every stored hash are forgotten, and nothing is read until
	 * a newer server is reported.
	 */
	public synchronized void onServerContract(@Nullable SemVer version)
	{
		boolean tooOld = version != null && version.compareTo(QUESTS_VERSION) < 0;
		serverTooOld = tooOld;
		if (tooOld)
		{
			log.debug("Server contract {} has no quest snapshots; clearing quest hashes", version);
			quests.forget();
			diaries.forget();
			resetProgress();
		}
	}

	private boolean canRead()
	{
		return enabled.getAsBoolean() && !serverTooOld && view.loggedIn() && view.standardWorld()
			&& store.currentProfile() != null && sessionActive.getAsBoolean();
	}

	private void resetProgress()
	{
		phase = Phase.WAITING;
		settledTicks = 0;
		settledSince = null;
		confirmWait = 0;
		firstQuestsHash = null;
		firstDiariesHash = null;
		reading = null;
		questsDue = null;
		lastQuestRead = null;
		diariesDirty = false;
		changedDuringRead = false;
	}

	private void startRead()
	{
		reading = new ArrayList<>(view.questCount());
		readNanos = 0;
		readTicks = 0;
	}

	/** Reads quests until the tick's time budget is used; finishes the read when all are in. */
	private void continueRead(Instant now)
	{
		int count = view.questCount();
		long start = nanoTime.getAsLong();
		long used = 0;
		int readNow = 0;
		while (reading.size() < count && (readNow < MIN_PER_TICK || used < TICK_BUDGET_NANOS))
		{
			reading.add(view.quest(reading.size()));
			readNow++;
			used = nanoTime.getAsLong() - start;
		}
		readNanos += used;
		readTicks++;
		if (reading.size() < count)
		{
			return;
		}
		List<QuestView.RawQuest> raw = reading;
		reading = null;
		log.debug("Quest read: {} entries in {} µs over {} tick(s)", raw.size(), readNanos / 1000, readTicks);
		if (phase == Phase.CONFIRMING)
		{
			confirm(raw, now);
		}
		else
		{
			onQuestsRead(raw, now);
		}
	}

	/** One of the reads after login: the baseline is offered when two in a row agree. */
	private void confirm(List<QuestView.RawQuest> raw, Instant now)
	{
		confirmWait = CONFIRM_TICKS;
		QuestsSnapshot questsSnapshot = QuestSnapshots.quests(view.questPoints(), raw);
		if (!view.statsLoaded() || !QuestSnapshots.consistent(questsSnapshot))
		{
			log.debug("Quest data not complete yet; reading again");
			firstQuestsHash = null;
			firstDiariesHash = null;
			return;
		}
		DiariesSnapshot diariesSnapshot = QuestSnapshots.diaries(view::varbit);
		String questsHash = QuestSnapshots.hash(questsSnapshot);
		String diariesHash = QuestSnapshots.hash(diariesSnapshot);
		if (!questsHash.equals(firstQuestsHash) || !diariesHash.equals(firstDiariesHash))
		{
			firstQuestsHash = questsHash;
			firstDiariesHash = diariesHash;
			return;
		}
		phase = Phase.READY;
		firstQuestsHash = null;
		firstDiariesHash = null;
		lastQuestRead = now;
		questsDue = now.plus(INTERVAL);
		if (log.isDebugEnabled())
		{
			// Type values and counts only: never names.
			log.debug("Quest types (COL_TYPE value=entries): {}, quest anchor {}, miniquest anchor {}",
				QuestSnapshots.typeHistogram(raw), QuestSnapshots.typeOf(raw, QuestSnapshots.QUEST_ANCHOR),
				QuestSnapshots.typeOf(raw, QuestSnapshots.MINIQUEST_ANCHOR));
		}
		offerQuests(questsSnapshot, questsHash, now);
		offerDiaries(diariesSnapshot, now);
	}

	private void onQuestsRead(List<QuestView.RawQuest> raw, Instant now)
	{
		lastQuestRead = now;
		if (changedDuringRead)
		{
			// Spread over ticks, it may mix the state before and after the change: read again instead.
			changedDuringRead = false;
			questsDue = now.plus(TRIGGER_DELAY);
			return;
		}
		questsDue = now.plus(INTERVAL);
		QuestsSnapshot snapshot = QuestSnapshots.quests(view.questPoints(), raw);
		if (!QuestSnapshots.consistent(snapshot))
		{
			log.debug("Quest points do not fit the quest states; skipping this read");
			return;
		}
		offerQuests(snapshot, QuestSnapshots.hash(snapshot), now);
	}

	private void offerQuests(QuestsSnapshot snapshot, String hash, Instant now)
	{
		String profile = store.currentProfile();
		if (profile != null)
		{
			quests.offer(profile, hash, () -> createInSession.apply(snapshot), now);
		}
	}

	private void offerDiaries(DiariesSnapshot snapshot, Instant now)
	{
		String profile = store.currentProfile();
		if (profile != null)
		{
			diaries.offer(profile, QuestSnapshots.hash(snapshot), () -> createInSession.apply(snapshot), now);
		}
	}
}
