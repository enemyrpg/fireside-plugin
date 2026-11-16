package com.fireside.pet;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/** {@link PetView} backed by the client. Client thread only. */
@Singleton
public class ClientPetView implements PetView
{
	private final Client client;
	private final ItemManager itemManager;
	/** Static game data, so it is read once. */
	@Nullable
	private Map<Integer, String> pets;

	@Inject
	ClientPetView(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	@Override
	public boolean standardWorld()
	{
		return RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.STANDARD;
	}

	@Override
	public Map<Integer, String> pets()
	{
		if (pets != null)
		{
			return pets;
		}
		Map<Integer, String> read = new LinkedHashMap<>();
		try
		{
			EnumComposition petsEnum = client.getEnum(EnumID.PETS);
			int[] itemIds = petsEnum != null ? petsEnum.getIntVals() : null;
			if (itemIds != null)
			{
				for (int itemId : itemIds)
				{
					String name = itemId > 0 ? itemManager.getItemComposition(itemId).getMembersName() : null;
					if (name != null && !name.isEmpty() && !"null".equals(name))
					{
						read.put(itemId, name);
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			// The enum is missing or changed shape: pets are then not guessed.
			return Collections.emptyMap();
		}
		if (read.isEmpty())
		{
			return Collections.emptyMap();
		}
		pets = Collections.unmodifiableMap(read);
		return pets;
	}

	@Override
	public Map<Integer, Integer> inventory()
	{
		ItemContainer container = client.getItemContainer(InventoryID.INV);
		if (container == null)
		{
			return Collections.emptyMap();
		}
		Map<Integer, Integer> counts = new HashMap<>();
		for (Item item : container.getItems())
		{
			if (item.getId() > 0 && item.getQuantity() > 0)
			{
				counts.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return counts;
	}

	@Override
	@Nullable
	public String followerName()
	{
		NPC follower = client.getFollower();
		String name = follower != null ? follower.getName() : null;
		return name != null ? Text.removeTags(name) : null;
	}
}
