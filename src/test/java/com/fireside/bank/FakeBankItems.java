package com.fireside.bank;

import com.fireside.loot.ItemLookup;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

/** Item facts for the bank tests, as RuneLite's item manager would give them. */
final class FakeBankItems implements ItemLookup
{
	static final int WHIP = 4151;
	static final int AGS = 11802;
	static final int TBOW = 20997;
	/** The bank placeholder of the twisted bow. */
	static final int TBOW_PLACEHOLDER = 20998;
	static final int SHARKS = 385;
	static final int SHARKS_NOTED = 386;
	static final int FIRE_CAPE = 6570;
	static final int SUPER_COMBAT_4 = 12695;
	static final int SUPER_COMBAT_3 = 12697;
	static final int SUPER_COMBAT_2 = 12699;
	static final int SUPER_COMBAT_1 = 12701;
	static final int RANARR_UNF = 99;

	final Map<Integer, String> names = new HashMap<>();
	final Map<Integer, Long> ge = new HashMap<>();
	final Map<Integer, Integer> ha = new HashMap<>();
	final Set<Integer> untradeable = new HashSet<>();
	final Map<Integer, Integer> placeholders = new HashMap<>();

	FakeBankItems()
	{
		item(WHIP, "Abyssal whip", 1_500_000, 72_000);
		item(AGS, "Armadyl godsword", 9_800_000, 750_000);
		item(TBOW, "Twisted bow", 1_450_000_000, 720_000);
		names.put(TBOW_PLACEHOLDER, "Twisted bow");
		placeholders.put(TBOW_PLACEHOLDER, TBOW);
		item(SHARKS, "Shark", 900, 300);
		item(SHARKS_NOTED, "Shark", 900, 300);
		item(FIRE_CAPE, "Fire cape", 0, 0);
		untradeable.add(FIRE_CAPE);
		ha.put(FIRE_CAPE, 25_000);
		item(SUPER_COMBAT_4, "Super combat potion(4)", 12_400, 0);
		item(SUPER_COMBAT_3, "Super combat potion(3)", 9_000, 0);
		item(SUPER_COMBAT_2, "Super combat potion(2)", 6_000, 0);
		item(SUPER_COMBAT_1, "Super combat potion(1)", 3_000, 0);
		item(RANARR_UNF, "Ranarr potion (unf)", 7_000, 3);
		item(ItemID.VIAL_EMPTY, "Vial", 4, 1);
		item(ItemID.COINS, "Coins", 1, 0);
		item(ItemID.PLATINUM, "Platinum token", 1000, 0);
		// RuneLite's item manager gives the filler a price; the snapshot must still say 0.
		item(ItemID.BANK_FILLER, "Bank filler", 5, 5);
	}

	private void item(int id, String name, long gePrice, int haPrice)
	{
		names.put(id, name);
		ge.put(id, gePrice);
		ha.put(id, haPrice);
	}

	@Override
	public String name(int itemId)
	{
		return names.getOrDefault(itemId, "null");
	}

	@Override
	public boolean tradeable(int itemId)
	{
		return !untradeable.contains(itemId);
	}

	@Override
	public long gePrice(int itemId)
	{
		return ge.getOrDefault(itemId, 0L);
	}

	@Override
	public int haPrice(int itemId)
	{
		return ha.getOrDefault(itemId, 0);
	}

	@Override
	public int placeholderOf(int itemId)
	{
		return placeholders.getOrDefault(itemId, -1);
	}
}
