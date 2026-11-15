package com.fireside.loot;

import net.runelite.api.gameval.ItemID;

/**
 * Per-item prices as the contract wants them, shared by loot and the bank.
 * Client thread only (through {@link ItemLookup}).
 * <ul>
 * <li>Coins: GE and HA 1. Platinum tokens: GE and HA 1000.</li>
 * <li>GE price from RuneLite's item manager, 0 for untradeable items.</li>
 * <li>HA price from the item composition (of the unnoted form).</li>
 * </ul>
 */
public final class ItemPrices
{
	private static final int PLATINUM_VALUE = 1000;

	private final ItemLookup items;

	public ItemPrices(ItemLookup items)
	{
		this.items = items;
	}

	public int gePrice(int itemId)
	{
		if (itemId == ItemID.COINS)
		{
			return 1;
		}
		if (itemId == ItemID.PLATINUM)
		{
			return PLATINUM_VALUE;
		}
		return items.tradeable(itemId) ? clamp(items.gePrice(itemId)) : 0;
	}

	public int haPrice(int itemId)
	{
		if (itemId == ItemID.COINS)
		{
			return 1;
		}
		if (itemId == ItemID.PLATINUM)
		{
			return PLATINUM_VALUE;
		}
		return Math.max(0, items.haPrice(itemId));
	}

	/** {@code value} limited to 0 … {@link Integer#MAX_VALUE}. */
	public static int clamp(long value)
	{
		return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
	}
}
