package com.osrstracker.loot;

import com.osrstracker.event.EventPayload;
import com.osrstracker.event.Kc;
import com.osrstracker.event.Loot;
import com.osrstracker.event.LootItem;
import com.osrstracker.event.LootSource;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;

/**
 * Turns kill count messages and Loot Tracker loot into {@code kc} and
 * {@code loot} events. Client thread only.
 * <p>
 * A kill count waits until the end of its game tick, because the fight time
 * can come just before or just after it. It is sent at the first tick after
 * it, or right before a loot event, so events are created in game order.
 * A loot event gets the {@code killCount} of a kill count for the same
 * source that arrived at most {@link #LOOT_WINDOW_TICKS} ticks before it.
 * <p>
 * Never logs chat messages or another player's name.
 */
@Slf4j
public class KillTracker
{
	/** How many ticks a kill count may come before its loot (NPC drops appear a few ticks after the kill). */
	static final int LOOT_WINDOW_TICKS = 10;
	/** A fight time sent before the kill count may be this many ticks older. */
	static final int TIME_BEFORE_KC_TICKS = 1;

	/** Kill count names that differ from the Loot Tracker's source name (lower case). */
	private static final Map<String, String> LOOT_NAMES = Collections.singletonMap("barrows chest", "barrows");

	private final Consumer<EventPayload> sink;
	private final BooleanSupplier lootEnabled;
	private final BooleanSupplier kcEnabled;

	@Nullable
	private PendingKc pending;
	@Nullable
	private KillCountParser.FightTime lastTime;
	private int lastTimeTick;
	/** The last kill count sent, until a loot event uses it. */
	@Nullable
	private PendingKc forLoot;

	private static final class PendingKc
	{
		final String boss;
		final int killCount;
		final int tick;
		@Nullable
		KillCountParser.FightTime time;

		PendingKc(String boss, int killCount, int tick)
		{
			this.boss = boss;
			this.killCount = killCount;
			this.tick = tick;
		}
	}

	/**
	 * @param sink sends an event in the current session (drops it when there is none)
	 */
	public KillTracker(Consumer<EventPayload> sink, BooleanSupplier lootEnabled, BooleanSupplier kcEnabled)
	{
		this.sink = sink;
		this.lootEnabled = lootEnabled;
		this.kcEnabled = kcEnabled;
	}

	public void onChatMessage(ChatMessageType type, String message, int tick)
	{
		if (!KillCountParser.isGameMessage(type) || message == null)
		{
			return;
		}
		KillCountParser.KillCount kc = KillCountParser.parseKillCount(message);
		if (kc != null)
		{
			flush();
			pending = new PendingKc(kc.getBoss(), kc.getKillCount(), tick);
			if (lastTime != null && tick - lastTimeTick <= TIME_BEFORE_KC_TICKS)
			{
				pending.time = lastTime;
			}
			lastTime = null;
			return;
		}
		KillCountParser.FightTime time = KillCountParser.parseFightTime(message);
		if (time == null)
		{
			return;
		}
		if (pending != null && pending.time == null && pending.tick == tick)
		{
			pending.time = time;
		}
		else
		{
			lastTime = time;
			lastTimeTick = tick;
		}
	}

	/** Every game tick: sends a kill count from an earlier tick. */
	public void onTick(int tick)
	{
		if (pending != null && tick > pending.tick)
		{
			flush();
		}
		if (lastTime != null && tick - lastTimeTick > TIME_BEFORE_KC_TICKS)
		{
			lastTime = null;
		}
	}

	/**
	 * Loot from the Loot Tracker, already mapped.
	 *
	 * @param amount how many loots the Loot Tracker combined (no kill count unless 1)
	 */
	public void onLoot(LootSource source, List<LootItem> items, int amount, int tick)
	{
		flush();
		if (items.isEmpty())
		{
			return;
		}
		Integer killCount = null;
		if (kcEnabled.getAsBoolean() && amount == 1 && forLoot != null && source.getName() != null
			&& tick - forLoot.tick <= LOOT_WINDOW_TICKS && sameSource(forLoot.boss, source.getName()))
		{
			killCount = forLoot.killCount;
			forLoot = null;
		}
		if (!lootEnabled.getAsBoolean())
		{
			return;
		}
		log.debug("Loot from a {} source{}", source.getType(), killCount != null ? " with kill count" : "");
		sink.accept(new Loot(source, items, killCount));
	}

	/** Sends a waiting kill count now (e.g. before the session ends). */
	public void flush()
	{
		PendingKc kc = pending;
		if (kc == null)
		{
			return;
		}
		pending = null;
		forLoot = kc;
		if (!kcEnabled.getAsBoolean())
		{
			return;
		}
		Kc.KcBuilder event = Kc.builder().boss(kc.boss).killCount(kc.killCount);
		if (kc.time != null)
		{
			event.timeSeconds(kc.time.getSeconds()).personalBest(kc.time.isPersonalBest());
		}
		log.debug("Kill count{}", kc.time != null ? " with fight time" : "");
		sink.accept(event.build());
	}

	/** Forgets everything without sending (logout, hop). */
	public void reset()
	{
		pending = null;
		lastTime = null;
		forLoot = null;
	}

	private static boolean sameSource(String killCountName, String lootName)
	{
		String kc = normalize(killCountName);
		return kc.equals(normalize(lootName)) || normalize(LOOT_NAMES.getOrDefault(kc, kc)).equals(normalize(lootName));
	}

	private static String normalize(String name)
	{
		return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}
}
