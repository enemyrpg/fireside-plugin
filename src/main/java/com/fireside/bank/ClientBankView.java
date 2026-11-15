package com.fireside.bank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.RuneScapeProfileType;

/** {@link BankView} backed by the client. Client thread only. */
@Singleton
public class ClientBankView implements BankView
{
	private static final int[] TAB_VARBITS = {
		VarbitID.BANK_TAB_1, VarbitID.BANK_TAB_2, VarbitID.BANK_TAB_3,
		VarbitID.BANK_TAB_4, VarbitID.BANK_TAB_5, VarbitID.BANK_TAB_6,
		VarbitID.BANK_TAB_7, VarbitID.BANK_TAB_8, VarbitID.BANK_TAB_9,
	};

	private final Client client;
	private final PotionStorageReader potions;

	@Inject
	ClientBankView(Client client)
	{
		this.client = client;
		this.potions = new PotionStorageReader(client);
	}

	@Override
	public boolean loggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN;
	}

	@Override
	public boolean standardWorld()
	{
		return RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.STANDARD;
	}

	@Nullable
	@Override
	public List<Slot> bank()
	{
		ItemContainer container = client.getItemContainer(InventoryID.BANK);
		return container != null ? slots(container) : null;
	}

	@Override
	public int[] tabSizes()
	{
		int[] sizes = new int[TAB_VARBITS.length];
		for (int i = 0; i < TAB_VARBITS.length; i++)
		{
			sizes[i] = client.getVarbitValue(TAB_VARBITS[i]);
		}
		return sizes;
	}

	@Override
	public List<Slot> inventory()
	{
		ItemContainer container = client.getItemContainer(InventoryID.INV);
		return container != null ? slots(container) : Collections.emptyList();
	}

	@Override
	public Map<String, Slot> equipment()
	{
		ItemContainer container = client.getItemContainer(InventoryID.WORN);
		Map<String, Slot> worn = new LinkedHashMap<>();
		if (container == null)
		{
			return worn;
		}
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			Item item = container.getItem(slot.getSlotIdx());
			if (item != null && item.getId() > 0)
			{
				worn.put(slot.name(), new Slot(item.getId(), item.getQuantity()));
			}
		}
		return worn;
	}

	@Override
	public List<RawPotion> potionStorage()
	{
		return potions.read();
	}

	@Override
	public int potionStorageVials()
	{
		return client.getVarpValue(VarPlayerID.POTIONSTORE_VIALS);
	}

	private static List<Slot> slots(ItemContainer container)
	{
		Item[] items = container.getItems();
		List<Slot> slots = new ArrayList<>(items.length);
		for (Item item : items)
		{
			slots.add(new Slot(item.getId(), item.getQuantity()));
		}
		return slots;
	}
}
