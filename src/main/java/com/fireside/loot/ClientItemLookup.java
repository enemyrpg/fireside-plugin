package com.fireside.loot;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

/** {@link ItemLookup} backed by RuneLite's {@link ItemManager}. Client thread only. */
@Singleton
public class ClientItemLookup implements ItemLookup
{
	private final ItemManager itemManager;

	@Inject
	ClientItemLookup(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	@Override
	public String name(int itemId)
	{
		return itemManager.getItemComposition(itemId).getMembersName();
	}

	@Override
	public boolean tradeable(int itemId)
	{
		return unnoted(itemId).isTradeable();
	}

	@Override
	public long gePrice(int itemId)
	{
		return itemManager.getItemPrice(itemId);
	}

	@Override
	public int haPrice(int itemId)
	{
		return unnoted(itemId).getHaPrice();
	}

	private ItemComposition unnoted(int itemId)
	{
		ItemComposition composition = itemManager.getItemComposition(itemId);
		if (composition.getNote() != -1)
		{
			return itemManager.getItemComposition(composition.getLinkedNoteId());
		}
		return composition;
	}
}
