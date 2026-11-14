package com.fireside.loot;

/**
 * Item facts from RuneLite's item manager, as {@link LootMapper} needs them.
 * {@link ClientItemLookup} is the real one; tests use a fake. Client thread only.
 */
public interface ItemLookup
{
	/** The item's name (members name, also on free worlds). */
	String name(int itemId);

	/** Whether the item (its unnoted form, for a note) can be traded. */
	boolean tradeable(int itemId);

	/** RuneLite's GE price per item ({@code ItemManager.getItemPrice}); 0 if unknown. */
	long gePrice(int itemId);

	/** High alchemy value per item (of the unnoted form, for a note). */
	int haPrice(int itemId);
}
