package com.fireside.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.NonNull;
import lombok.Value;

/**
 * {@code bank.snapshot} (contract 1.19.0): the bank in container order, with
 * inventory and equipment at the same moment. Item IDs are canonical: a
 * placeholder has the real item's ID, {@code placeholder: true} and quantity 0.
 * Bank fillers are items in their positions, with prices 0. Prices are per item.
 */
@Value
public class BankSnapshot implements EventPayload
{
	/** Tabs 1–9; the items after them are the main tab. */
	public static final int TAB_COUNT = 9;

	/** One bank slot. */
	@Value
	public static class Item
	{
		int itemId;
		@NonNull
		String name;
		/** 0 for a placeholder, at least 1 otherwise. */
		int quantity;
		boolean placeholder;
		int gePrice;
		int haPrice;
	}

	/** One occupied inventory slot. Noted items keep their ID. */
	@Value
	public static class InventoryItem
	{
		int itemId;
		@NonNull
		String name;
		int quantity;
		int gePrice;
		int haPrice;
	}

	/** One worn item; {@code slot} is RuneLite's {@code EquipmentInventorySlot} name. */
	@Value
	public static class WornItem
	{
		@NonNull
		String slot;
		int itemId;
		@NonNull
		String name;
		int quantity;
		int gePrice;
		int haPrice;
	}

	@NonNull
	List<Integer> tabSizes;
	@NonNull
	List<Item> items;
	@NonNull
	List<InventoryItem> inventory;
	@NonNull
	List<WornItem> equipment;
	@NonNull
	String contentHash;

	public BankSnapshot(@NonNull List<Integer> tabSizes, @NonNull List<Item> items,
		@NonNull List<InventoryItem> inventory, @NonNull List<WornItem> equipment, @NonNull String contentHash)
	{
		if (tabSizes.size() != TAB_COUNT)
		{
			throw new IllegalArgumentException("tabSizes must have " + TAB_COUNT + " entries");
		}
		this.tabSizes = Collections.unmodifiableList(new ArrayList<>(tabSizes));
		this.items = Collections.unmodifiableList(new ArrayList<>(items));
		this.inventory = Collections.unmodifiableList(new ArrayList<>(inventory));
		this.equipment = Collections.unmodifiableList(new ArrayList<>(equipment));
		this.contentHash = contentHash;
	}

	@Override
	public EventType type()
	{
		return EventType.BANK_SNAPSHOT;
	}
}
