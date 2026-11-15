package com.fireside.bank;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * What the bank tracker reads from the game: item containers and varbits
 * only, never the bank widgets (or what other plugins such as Bank Tags draw
 * in them). Client thread only. {@link ClientBankView} is the real one; tests
 * use a fake.
 */
public interface BankView
{
	/** One container slot as the game has it: id {@code -1} (or 0) is an empty slot. */
	@Value
	class Slot
	{
		int itemId;
		int quantity;
	}

	/**
	 * One potion type from the potion storage enums.
	 * {@code variants[d - 1]} is the item ID for {@code d} doses, or a value
	 * {@code <= 0} if there is none (unfinished potions only have 1).
	 */
	@Value
	class RawPotion
	{
		int[] variants;
		int doses;
	}

	boolean loggedIn();

	/** A standard world: not Leagues, Deadman, beta or another world with its own bank. */
	boolean standardWorld();

	/** The bank container in slot order, or null if the client has none. */
	@Nullable
	List<Slot> bank();

	/** The 9 varbits {@code BANK_TAB_1..9}: the number of slots in tabs 1–9. */
	int[] tabSizes();

	/** The inventory in slot order, empty slots included; empty if there is none. */
	List<Slot> inventory();

	/** Worn items keyed by {@code EquipmentInventorySlot} name, in slot order; only occupied slots. */
	Map<String, Slot> equipment();

	/** Potion storage as the game's potion store scripts see it. */
	List<RawPotion> potionStorage();

	/** Empty vials in potion storage. */
	int potionStorageVials();
}
