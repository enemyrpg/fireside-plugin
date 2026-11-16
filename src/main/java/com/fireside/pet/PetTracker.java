package com.fireside.pet;

import com.fireside.Contract;
import com.fireside.event.Event;
import com.fireside.event.PetDrop;
import com.fireside.loot.KillCountParser;
import com.fireside.net.SemVer;
import com.fireside.session.XpGainTracker;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;

/**
 * Turns the game's pet messages into {@code pet.drop} events.
 * <p>
 * The event's ID and time are taken when the message arrives. The event is
 * queued {@link #HOLD_TICKS} ticks later, because the kill count, the loot,
 * the collection log line and the follower can come just after the message.
 * <p>
 * The pet is only named when the game says which it is: the collection log's
 * "new item" line, a pet appearing in the inventory, or the new follower's
 * name, each checked against the game's own list of pets. The source is a
 * guess: the kill count, loot or skill XP closest in time to the message.
 * <p>
 * Called on the client thread, except {@link #flush()} at client shutdown,
 * which reads nothing from the game. Synchronized for that reason. Never
 * logs or keeps a chat message.
 */
@Slf4j
public class PetTracker
{
	/** How long a pet message waits for what comes after it. */
	static final int HOLD_TICKS = 3;
	/** A kill count or loot may be this many ticks older than the pet message. */
	static final int ACTIVITY_BEFORE_TICKS = 10;
	/** An XP gain may be this many ticks older than the pet message. */
	static final int SKILL_BEFORE_TICKS = 2;

	/** The collection log line may be this many ticks older than the pet message. */
	static final int COLLECTION_LOG_BEFORE_TICKS = 1;

	/** Skills that get XP from every kill, so they say nothing about a pet. Other (and new) skills count. */
	private static final Set<String> COMBAT_SKILLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"ATTACK", "STRENGTH", "DEFENCE", "RANGED", "MAGIC", "HITPOINTS", "PRAYER", "SLAYER")));

	private static final SemVer PETS_VERSION = SemVer.parse(Contract.PETS_VERSION);

	/** Where a source guess came from; on equal distance the lowest ordinal wins. */
	private enum Kind
	{
		KILL_COUNT,
		LOOT,
		SKILL,
	}

	private static final class Seen
	{
		final Kind kind;
		final String name;
		final int tick;

		Seen(Kind kind, String name, int tick)
		{
			this.kind = kind;
			this.name = name;
			this.tick = tick;
		}
	}

	private static final class Pending
	{
		final Event.Reserved envelope;
		final PetDrop.Message message;
		final int tick;
		/** Pets in the inventory at the last tick before the message. */
		final Map<Integer, Integer> inventoryBefore;
		/** The closest of each kind, before or after the message. */
		final Seen[] sources = new Seen[Kind.values().length];
		@Nullable
		PetDrop.GuessedPet fromCollectionLog;
		@Nullable
		PetDrop.GuessedPet fromGame;

		Pending(Event.Reserved envelope, PetDrop.Message message, int tick, Map<Integer, Integer> inventoryBefore)
		{
			this.envelope = envelope;
			this.message = message;
			this.tick = tick;
			this.inventoryBefore = inventoryBefore;
		}
	}

	private final PetView view;
	private final Supplier<Event.Reserved> envelopes;
	private final Consumer<Event> sink;
	private final BooleanSupplier enabled;
	private final XpGainTracker xp = new XpGainTracker();
	/** The last of each kind seen, for a pet message that comes after it. */
	private final Seen[] last = new Seen[Kind.values().length];

	private boolean serverTooOld;
	/** Pets in the inventory at the last tick. */
	private Map<Integer, Integer> inventoryPets = Collections.emptyMap();
	@Nullable
	private Pending pending;
	/** The last pet the collection log announced, for a pet message that comes right after it. */
	@Nullable
	private PetDrop.GuessedPet lastCollectionLogPet;
	private int lastCollectionLogTick;

	/**
	 * @param envelopes reserves an event in the current session (null when there is none)
	 * @param sink queues a finished event
	 * @param enabled the "Pets" toggle and "Send data"
	 */
	public PetTracker(PetView view, Supplier<Event.Reserved> envelopes, Consumer<Event> sink,
		BooleanSupplier enabled)
	{
		this.view = view;
		this.envelopes = envelopes;
		this.sink = sink;
		this.enabled = enabled;
	}

	/** The server's contract from {@code verify} (null when unknown). An older server rejects {@code pet.drop}. */
	public synchronized void onServerContract(@Nullable SemVer version)
	{
		serverTooOld = version != null && version.compareTo(PETS_VERSION) < 0;
	}

	public synchronized void onChatMessage(ChatMessageType type, String message, int tick)
	{
		if (message == null || !enabled.getAsBoolean())
		{
			return;
		}
		if (KillCountParser.isGameMessage(type))
		{
			KillCountParser.KillCount kc = KillCountParser.parseKillCount(message);
			if (kc != null)
			{
				see(new Seen(Kind.KILL_COUNT, kc.getBoss(), tick));
				return;
			}
		}
		if (!PetMessageParser.isGameMessage(type))
		{
			return;
		}
		PetDrop.Message petMessage = PetMessageParser.parsePetMessage(message);
		if (petMessage != null)
		{
			onPetMessage(petMessage, tick);
			return;
		}
		String item = PetMessageParser.parseCollectionLogItem(message);
		PetDrop.GuessedPet pet = item != null ? petNamed(item, false) : null;
		if (pet == null)
		{
			return;
		}
		if (pending != null && pending.fromCollectionLog == null)
		{
			pending.fromCollectionLog = pet;
		}
		else if (pending == null)
		{
			// The line may come just before the pet message.
			lastCollectionLogPet = pet;
			lastCollectionLogTick = tick;
		}
	}

	/** Loot from the Loot Tracker; {@code source} is null for another player's loot. */
	public synchronized void onLoot(@Nullable String source, int tick)
	{
		if (source != null && !source.isEmpty() && enabled.getAsBoolean())
		{
			see(new Seen(Kind.LOOT, source, tick));
		}
	}

	/** A skill's XP from {@code StatChanged}. Only a gain counts, not the values loaded at login. */
	public synchronized void onXp(String skill, int xpValue, int tick)
	{
		if (xp.onXp(skill, xpValue) && !COMBAT_SKILLS.contains(skill) && enabled.getAsBoolean())
		{
			see(new Seen(Kind.SKILL, skill, tick));
		}
	}

	/** Every game tick. */
	public synchronized void onTick(int tick)
	{
		if (!enabled.getAsBoolean())
		{
			inventoryPets = Collections.emptyMap();
			return;
		}
		Map<Integer, Integer> inventoryNow = petsIn(view.inventory());
		if (pending != null)
		{
			lookForPet(pending, inventoryNow);
			if (tick - pending.tick >= HOLD_TICKS)
			{
				flush();
			}
		}
		inventoryPets = inventoryNow;
	}

	/** Queues a held pet drop now, with what is known (before the session ends). Reads nothing from the game. */
	public synchronized void flush()
	{
		Pending drop = pending;
		if (drop == null)
		{
			return;
		}
		pending = null;
		PetDrop.GuessedPet pet = drop.message == PetDrop.Message.DUPLICATE ? null
			: drop.fromCollectionLog != null ? drop.fromCollectionLog : drop.fromGame;
		PetDrop.GuessedSource source = source(drop);
		log.debug("Pet drop ({}): pet {}, source {}", drop.message, pet != null ? "known" : "unknown",
			source != null ? source.getType() : "unknown");
		sink.accept(drop.envelope.with(new PetDrop(drop.message, pet, source)));
	}

	/** Forgets everything without sending (logout, hop). */
	public synchronized void reset()
	{
		pending = null;
		lastCollectionLogPet = null;
		Arrays.fill(last, null);
		inventoryPets = Collections.emptyMap();
		xp.reset();
	}

	private void onPetMessage(PetDrop.Message message, int tick)
	{
		flush();
		if (serverTooOld)
		{
			log.debug("Pet drop not sent: the server's contract is older than {}", Contract.PETS_VERSION);
			return;
		}
		if (!view.standardWorld())
		{
			return;
		}
		Event.Reserved envelope = envelopes.get();
		if (envelope == null)
		{
			return;
		}
		Pending drop = new Pending(envelope, message, tick, inventoryPets);
		for (Seen seen : last)
		{
			if (seen != null && tick - seen.tick <= (seen.kind == Kind.SKILL ? SKILL_BEFORE_TICKS
				: ACTIVITY_BEFORE_TICKS))
			{
				drop.sources[seen.kind.ordinal()] = seen;
			}
		}
		if (lastCollectionLogPet != null && tick - lastCollectionLogTick <= COLLECTION_LOG_BEFORE_TICKS)
		{
			drop.fromCollectionLog = lastCollectionLogPet;
		}
		lastCollectionLogPet = null;
		pending = drop;
		// The pet may already be there in the tick of the message.
		lookForPet(drop, petsIn(view.inventory()));
	}

	private void see(Seen seen)
	{
		last[seen.kind.ordinal()] = seen;
		Pending drop = pending;
		if (drop == null)
		{
			return;
		}
		Seen held = drop.sources[seen.kind.ordinal()];
		// The first one after the message is the closest after it.
		if (held == null || distance(drop, seen) < distance(drop, held))
		{
			drop.sources[seen.kind.ordinal()] = seen;
		}
	}

	private static int distance(Pending drop, Seen seen)
	{
		return Math.abs(seen.tick - drop.tick);
	}

	@Nullable
	private static PetDrop.GuessedSource source(Pending drop)
	{
		Seen best = null;
		for (Seen seen : drop.sources)
		{
			// In Kind order, so on a tie a kill count beats loot, and both beat a skill.
			if (seen != null && (best == null || distance(drop, seen) < distance(drop, best)))
			{
				best = seen;
			}
		}
		if (best == null)
		{
			return null;
		}
		return best.kind == Kind.SKILL ? PetDrop.GuessedSource.skill(best.name)
			: PetDrop.GuessedSource.activity(best.name);
	}

	/** The pet as the game shows it: in the inventory for a backpack message, as the follower otherwise. */
	private void lookForPet(Pending drop, Map<Integer, Integer> inventoryNow)
	{
		if (drop.fromGame != null)
		{
			return;
		}
		if (drop.message == PetDrop.Message.BACKPACK)
		{
			Integer added = null;
			for (Map.Entry<Integer, Integer> pet : inventoryNow.entrySet())
			{
				if (pet.getValue() > drop.inventoryBefore.getOrDefault(pet.getKey(), 0))
				{
					if (added != null)
					{
						// Two different pets appeared: never guess.
						return;
					}
					added = pet.getKey();
				}
			}
			String name = added != null ? view.pets().get(added) : null;
			if (name != null)
			{
				drop.fromGame = new PetDrop.GuessedPet(added, name);
			}
		}
		else if (drop.message == PetDrop.Message.FOLLOWED)
		{
			String follower = view.followerName();
			if (follower != null)
			{
				drop.fromGame = petNamed(follower, true);
			}
		}
	}

	/** The pet with this item name, or null if none or more than one has it. */
	@Nullable
	private PetDrop.GuessedPet petNamed(String name, boolean ignoreCase)
	{
		PetDrop.GuessedPet found = null;
		for (Map.Entry<Integer, String> pet : view.pets().entrySet())
		{
			if (ignoreCase ? pet.getValue().equalsIgnoreCase(name.trim()) : pet.getValue().equals(name))
			{
				if (found != null)
				{
					return null;
				}
				found = new PetDrop.GuessedPet(pet.getKey(), pet.getValue());
			}
		}
		return found;
	}

	/** The pets among the inventory's items, item ID to quantity. */
	private Map<Integer, Integer> petsIn(Map<Integer, Integer> inventory)
	{
		if (inventory.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<Integer, String> pets = view.pets();
		Map<Integer, Integer> found = null;
		for (Map.Entry<Integer, Integer> item : inventory.entrySet())
		{
			if (pets.containsKey(item.getKey()))
			{
				if (found == null)
				{
					found = new HashMap<>();
				}
				found.put(item.getKey(), item.getValue());
			}
		}
		return found != null ? found : Collections.emptyMap();
	}
}
