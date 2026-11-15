package com.fireside.loot;

/**
 * Item facts from RuneLite's item manager, as loot and the bank need them.
 * {@link ClientItemLookup} is the real one; tests use a fake. Client thread only.
 */
public interface ItemLookup
{
	/** The item's name (members name, also on free worlds). */
	String name(int itemId);

	/**
	 * RuneLite's GE price per item ({@code ItemManager.getItemPrice}): mapped
	 * untradeables get the price of their tradeable parts; 0 if unknown.
	 */
	long gePrice(int itemId);

	/** High alchemy value per item (of the unnoted form, for a note). */
	int haPrice(int itemId);

	/** For a bank placeholder, the real item's ID; -1 if {@code itemId} is not a placeholder. */
	default int placeholderOf(int itemId)
	{
		return -1;
	}
}
