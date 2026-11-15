package com.fireside.icons;

import com.fireside.event.BankSnapshot;
import com.fireside.event.Event;
import com.fireside.event.EventPayload;
import com.fireside.event.Loot;
import com.fireside.event.LootItem;
import com.fireside.event.PotionStorageSnapshot;
import com.fireside.event.RaidSpecialLoot;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

/**
 * The item IDs an event shows in the dashboard, so their icons can be uploaded.
 * <p>
 * IDs are taken exactly as they are in the event and never canonicalized: a
 * noted item keeps its noted ID. The server stores icons by that ID and the
 * dashboard asks for the ID from the data as it is (03, "Item icons from the
 * plugin"). Add new event types with items here. Bank fillers are left out:
 * the dashboard draws them as empty slots.
 */
public final class IconIds
{
	private IconIds()
	{
	}

	/** The item IDs in the event, in order, without duplicates. Empty for events without items. */
	public static Set<Integer> of(Event event)
	{
		EventPayload payload = event.getPayload();
		Set<Integer> ids = new LinkedHashSet<>();
		if (payload instanceof Loot)
		{
			for (LootItem item : ((Loot) payload).getItems())
			{
				add(ids, item.getItemId());
			}
		}
		else if (payload instanceof RaidSpecialLoot)
		{
			add(ids, ((RaidSpecialLoot) payload).getItem().getItemId());
		}
		else if (payload instanceof BankSnapshot)
		{
			BankSnapshot bank = (BankSnapshot) payload;
			for (BankSnapshot.Item item : bank.getItems())
			{
				if (item.getItemId() != ItemID.BANK_FILLER)
				{
					add(ids, item.getItemId());
				}
			}
			for (BankSnapshot.InventoryItem item : bank.getInventory())
			{
				add(ids, item.getItemId());
			}
			for (BankSnapshot.WornItem item : bank.getEquipment())
			{
				add(ids, item.getItemId());
			}
		}
		else if (payload instanceof PotionStorageSnapshot)
		{
			for (PotionStorageSnapshot.Potion potion : ((PotionStorageSnapshot) payload).getPotions())
			{
				add(ids, potion.getItemId());
			}
		}
		return ids.isEmpty() ? Collections.emptySet() : ids;
	}

	private static void add(Set<Integer> ids, int itemId)
	{
		if (itemId >= 0)
		{
			ids.add(itemId);
		}
	}
}
