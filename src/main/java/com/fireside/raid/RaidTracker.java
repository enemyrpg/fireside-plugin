package com.fireside.raid;

import com.fireside.event.EventPayload;
import com.fireside.event.RaidSpecialLoot;
import com.fireside.loot.ItemLookup;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * Raid state for {@link com.fireside.loot.KillTracker}: the raid that was
 * just completed (its mode, KC and team size), and purples waiting for it.
 * Client thread only.
 * <p>
 * The mode comes from the raid's kill count message. The chest and purples
 * only get a mode from it, so Challenge Mode loot never lands on normal CoX.
 * Never logs messages or names.
 */
@Slf4j
public class RaidTracker
{
	/** How long the completed raid is remembered for its chest and purples (30 minutes). */
	public static final int RAID_MEMORY_TICKS = 3000;
	/** How long a purple waits for its raid's kill count, in case it comes first. */
	public static final int PURPLE_WAIT_TICKS = 10;

	/** The raid that was just completed. */
	@Value
	public static class CompletedRaid
	{
		Raids.Group group;
		/** The kill count name as chat shows it, e.g. {@code Chambers of Xeric Challenge Mode}. */
		String mode;
		int killCount;
		@Nullable
		Integer teamSize;
	}

	private static final class Remembered
	{
		final CompletedRaid raid;
		final int tick;
		boolean chestClaimed;

		Remembered(CompletedRaid raid, int tick)
		{
			this.raid = raid;
			this.tick = tick;
		}
	}

	@Value
	private static class WaitingPurple
	{
		Raids.Group group;
		RaidSpecialLoot.Item item;
		RaidSpecialLoot.Recipient recipient;
		int tick;
	}

	private final Consumer<EventPayload> sink;
	private final RaidView view;
	private final ItemLookup items;
	private final BooleanSupplier purplesEnabled;
	private final BooleanSupplier kcEnabled;

	@Nullable
	private Remembered current;
	private final List<WaitingPurple> waiting = new ArrayList<>();

	/**
	 * @param sink the same sink as the kill tracker's, so events stay in game order
	 */
	public RaidTracker(Consumer<EventPayload> sink, RaidView view, ItemLookup items, BooleanSupplier purplesEnabled,
		BooleanSupplier kcEnabled)
	{
		this.sink = sink;
		this.view = view;
		this.items = items;
		this.purplesEnabled = purplesEnabled;
		this.kcEnabled = kcEnabled;
	}

	/**
	 * The party size the game reports when the raid's kill count message
	 * arrives, or null if unknown. For CoX this is only used to resolve an
	 * interval from the completion message ({@link #coxTeamSize}).
	 */
	@Nullable
	public Integer partySizeAtKc(Raids.Group group)
	{
		switch (group)
		{
			case CHAMBERS_OF_XERIC:
				return validSize(view.coxPartySize());
			case THEATRE_OF_BLOOD:
				return validSize(view.tobPartySize());
			case TOMBS_OF_AMASCUT:
				return validSize(view.toaPartySize());
			default:
				// The Fractured Archive: no known party varbit yet.
				return null;
		}
	}

	/**
	 * The CoX team size to send: the shown size when exact, otherwise the
	 * party size varbit when it lies in the shown interval, otherwise null.
	 */
	@Nullable
	public static Integer coxTeamSize(RaidMessageParser.TeamSize shown, @Nullable Integer partySize)
	{
		if (!shown.isInterval())
		{
			return shown.getFrom();
		}
		return partySize != null && shown.contains(partySize) ? partySize : null;
	}

	/** After a raid's kill count was handled: remembers the raid and sends purples that were waiting for it. */
	public void onRaidKc(CompletedRaid raid, int tick)
	{
		current = new Remembered(raid, tick);
		Iterator<WaitingPurple> it = waiting.iterator();
		while (it.hasNext())
		{
			WaitingPurple purple = it.next();
			if (purple.group == raid.group)
			{
				it.remove();
				send(purple, raid);
			}
		}
	}

	/**
	 * A game message that may announce a purple.
	 *
	 * @param kcPending whether a kill count from this tick is still waiting to be sent (the purple then waits too)
	 * @return whether it was a purple
	 */
	public boolean onChatMessage(String message, int tick, boolean kcPending)
	{
		RaidMessageParser.PurpleMessage parsed = RaidMessageParser.parsePurple(message, view::isLocalPlayer);
		if (parsed == null)
		{
			return false;
		}
		Raids.Purple purple = parsed.getPurple();
		String name = items.name(purple.getItemId());
		RaidSpecialLoot.Item item = new RaidSpecialLoot.Item(purple.getItemId(),
			name == null || name.isEmpty() ? purple.getName() : name, 1, clamp(items.gePrice(purple.getItemId())));
		WaitingPurple waitingPurple = new WaitingPurple(purple.getGroup(), item, parsed.getRecipient(), tick);
		CompletedRaid raid = raid(tick);
		if (!kcPending && raid != null && raid.group == purple.getGroup())
		{
			send(waitingPurple, raid);
		}
		else
		{
			waiting.add(waitingPurple);
		}
		return true;
	}

	/**
	 * The completed raid for this raid's chest, once per raid. Null if the
	 * mode is unknown: the chest then keeps the Loot Tracker's name and gets no KC.
	 */
	@Nullable
	public CompletedRaid claimChest(Raids.Group group, int tick)
	{
		Remembered remembered = current;
		if (raid(tick) == null || remembered.raid.group != group || remembered.chestClaimed)
		{
			return null;
		}
		remembered.chestClaimed = true;
		return remembered.raid;
	}

	/** Every game tick, after waiting kill counts were sent. */
	public void onTick(int tick)
	{
		CompletedRaid raid = raid(tick);
		if (raid != null)
		{
			// E.g. a purple that waited behind another boss's kill count in the same tick.
			Iterator<WaitingPurple> it = waiting.iterator();
			while (it.hasNext())
			{
				WaitingPurple purple = it.next();
				if (purple.group == raid.group)
				{
					it.remove();
					send(purple, raid);
				}
			}
		}
		if (waiting.removeIf(purple -> tick - purple.tick > PURPLE_WAIT_TICKS))
		{
			log.debug("Dropped a raid purple: no kill count for its raid");
		}
	}

	/** Forgets everything without sending (logout, hop). */
	public void reset()
	{
		current = null;
		waiting.clear();
	}

	/** The remembered raid, or null when there is none or it is too old (then forgotten). */
	@Nullable
	private CompletedRaid raid(int tick)
	{
		if (current != null && tick - current.tick > RAID_MEMORY_TICKS)
		{
			current = null;
		}
		return current != null ? current.raid : null;
	}

	private void send(WaitingPurple purple, CompletedRaid raid)
	{
		if (!purplesEnabled.getAsBoolean())
		{
			return;
		}
		log.debug("Raid purple for {}", purple.recipient);
		sink.accept(RaidSpecialLoot.builder()
			.raid(raid.mode)
			.item(purple.item)
			.recipient(purple.recipient)
			.teamSize(raid.teamSize)
			.killCount(kcEnabled.getAsBoolean() ? raid.killCount : null)
			.build());
	}

	@Nullable
	private static Integer validSize(int size)
	{
		return size >= 1 && size <= RaidMessageParser.MAX_TEAM_SIZE ? size : null;
	}

	private static int clamp(long value)
	{
		return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
	}
}
