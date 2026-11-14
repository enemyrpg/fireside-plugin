package com.fireside.event;

import lombok.NonNull;
import lombok.Value;

/** One item stack in a {@code loot} event. Prices are per item, from the item manager at drop time. */
@Value
public class LootItem
{
	int itemId;
	@NonNull
	String name;
	int quantity;
	int gePrice;
	int haPrice;
}
