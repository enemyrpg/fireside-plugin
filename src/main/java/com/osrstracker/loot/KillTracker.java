package com.osrstracker.loot;

import com.osrstracker.event.EventPayload;
import com.osrstracker.event.Kc;
import com.osrstracker.event.Loot;
import com.osrstracker.event.LootItem;
import com.osrstracker.event.LootSource;
import com.osrstracker.raid.RaidMessageParser;
import com.osrstracker.raid.RaidTracker;
import com.osrstracker.raid.Raids;
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
 * Raids: a raid's kill count also gets the team size (and for Chambers of
 * Xeric the time and PB from the completion message). The raid chest is sent
 * under the mode from the kill count, with its KC, through {@link RaidTracker};
 * without a known mode it keeps the Loot Tracker's name and gets no KC.
 * Purple messages go to {@link RaidTracker}.
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
	private final RaidTracker raids;

	@Nullable
	private PendingKc pending;
	@Nullable
	private KillCountParser.FightTime lastTime;
	/** The CoX team size shown with {@link #lastTime}, if it came from the CoX completion message. */
	@Nullable
	private RaidMessageParser.TeamSize lastCoxTeam;
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
		/** Null unless this is a raid. */
		@Nullable
		final Raids.Group raid;
		/** The party size from varbits when the KC message arrived (raids only). */
		@Nullable
		final Integer partySize;
		@Nullable
		RaidMessageParser.TeamSize coxTeam;

		PendingKc(String boss, int killCount, int tick, @Nullable Raids.Group raid, @Nullable Integer partySize)
		{
			this.boss = boss;
			this.killCount = killCount;
			this.tick = tick;
			this.raid = raid;
			this.partySize = partySize;
		}

		void setTime(KillCountParser.FightTime time, @Nullable RaidMessageParser.TeamSize coxTeam)
		{
			this.time = time;
			this.coxTeam = coxTeam;
		}
	}

	/**
	 * @param sink sends an event in the current session (drops it when there is none)
	 */
	public KillTracker(Consumer<EventPayload> sink, BooleanSupplier lootEnabled, BooleanSupplier kcEnabled,
		RaidTracker raids)
	{
		this.sink = sink;
		this.lootEnabled = lootEnabled;
		this.kcEnabled = kcEnabled;
		this.raids = raids;
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
			Raids.Group raid = Raids.groupOfKc(kc.getBoss());
			// Varbits are read now: the party may be gone by the next tick.
			pending = new PendingKc(kc.getBoss(), kc.getKillCount(), tick, raid,
				raid != null ? raids.partySizeAtKc(raid) : null);
			if (lastTime != null && tick - lastTimeTick <= TIME_BEFORE_KC_TICKS)
			{
				pending.setTime(lastTime, lastCoxTeam);
			}
			lastTime = null;
			lastCoxTeam = null;
			return;
		}
		// Before the generic time: the CoX message also has the team size.
		RaidMessageParser.CoxCompletion cox = RaidMessageParser.parseCoxCompletion(message);
		KillCountParser.FightTime time = cox != null ? cox.getTime() : KillCountParser.parseFightTime(message);
		if (time != null)
		{
			RaidMessageParser.TeamSize coxTeam = cox != null ? cox.getTeamSize() : null;
			if (pending != null && pending.time == null && pending.tick == tick)
			{
				pending.setTime(time, coxTeam);
			}
			else
			{
				lastTime = time;
				lastCoxTeam = coxTeam;
				lastTimeTick = tick;
			}
			return;
		}
		raids.onChatMessage(message, tick, pending != null);
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
			lastCoxTeam = null;
		}
		raids.onTick(tick);
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
		Raids.Group chest = source.getType() == LootSource.Type.EVENT && source.getName() != null
			? Raids.groupOfChest(source.getName()) : null;
		if (chest != null)
		{
			// Only the raid's own KC message knows the mode (e.g. Challenge Mode).
			RaidTracker.CompletedRaid raid = amount == 1 ? raids.claimChest(chest, tick) : null;
			if (raid != null)
			{
				source = LootSource.event(raid.getMode());
				killCount = kcEnabled.getAsBoolean() ? raid.getKillCount() : null;
			}
		}
		else if (kcEnabled.getAsBoolean() && amount == 1 && forLoot != null && source.getName() != null
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
		forLoot = kc.raid == null ? kc : null;
		Integer teamSize = teamSize(kc);
		if (kcEnabled.getAsBoolean())
		{
			Kc.KcBuilder event = Kc.builder().boss(kc.boss).killCount(kc.killCount).teamSize(teamSize);
			if (kc.time != null)
			{
				event.timeSeconds(kc.time.getSeconds()).personalBest(kc.time.isPersonalBest())
					// A CoX PB for 11+ players is per interval, not for this exact size.
					.personalBestSeconds(kc.coxTeam != null && kc.coxTeam.isInterval()
						? null : kc.time.getPersonalBestSeconds());
			}
			log.debug("Kill count{}{}", kc.time != null ? " with fight time" : "",
				teamSize != null ? " and team size" : "");
			sink.accept(event.build());
		}
		if (kc.raid != null)
		{
			// Also with kill counts off: the chest and purples need the mode.
			raids.onRaidKc(new RaidTracker.CompletedRaid(kc.raid, kc.boss, kc.killCount, teamSize), kc.tick);
		}
	}

	@Nullable
	private static Integer teamSize(PendingKc kc)
	{
		if (kc.raid == null)
		{
			return null;
		}
		if (kc.raid == Raids.Group.CHAMBERS_OF_XERIC)
		{
			// CoX: only with the completion message; the varbit only resolves an interval.
			return kc.coxTeam != null ? RaidTracker.coxTeamSize(kc.coxTeam, kc.partySize) : null;
		}
		return kc.partySize;
	}

	/** Forgets everything without sending (logout, hop). */
	public void reset()
	{
		pending = null;
		lastTime = null;
		lastCoxTeam = null;
		forLoot = null;
		raids.reset();
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
