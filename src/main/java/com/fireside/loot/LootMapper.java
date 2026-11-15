package com.fireside.loot;

import com.fireside.event.LootItem;
import com.fireside.event.LootSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.client.game.ItemStack;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Maps the Loot Tracker's {@code LootReceived} to our {@code loot} source and
 * items. The mapping is documented in {@code 03-datamodell-og-api.md}.
 * <ul>
 * <li>{@code NPC} → {@code NPC}, with {@code npcId} when the metadata holds it</li>
 * <li>{@code PLAYER} → {@code PLAYER}, without the player's name</li>
 * <li>{@code EVENT} named {@code Clue Scroll (…)} → {@code CLUE}, other events → {@code EVENT}</li>
 * <li>{@code PICKPOCKET}, {@code UNKNOWN} and future types → {@code OTHER}</li>
 * </ul>
 */
public class LootMapper
{
	static final String CLUE_PREFIX = "Clue Scroll (";
	private final ItemLookup items;
	private final ItemPrices prices;

	public LootMapper(ItemLookup items)
	{
		this.items = items;
		this.prices = new ItemPrices(items);
	}

	/**
	 * @param name the Loot Tracker's name; for {@code PLAYER} the other
	 *     player's name, which is dropped here and never sent or logged
	 * @return null if the source has no name (nothing useful to send)
	 */
	@Nullable
	public static LootSource source(@Nullable LootRecordType type, @Nullable String name, int combatLevel,
		@Nullable Object metadata)
	{
		if (type == LootRecordType.PLAYER)
		{
			return LootSource.player();
		}
		if (type == null || name == null || name.trim().isEmpty())
		{
			return null;
		}
		name = name.trim();
		switch (type)
		{
			case NPC:
				return LootSource.npc(name, npcId(metadata), combatLevel > 0 ? combatLevel : null);
			case EVENT:
				return name.startsWith(CLUE_PREFIX) ? LootSource.clue(name) : LootSource.event(name);
			default:
				// PICKPOCKET (kept apart from killing the same NPC), UNKNOWN and anything new.
				return LootSource.other(name);
		}
	}

	/** The NPC id when the Loot Tracker's metadata holds it: an Integer, or a Map with "id". */
	@Nullable
	private static Integer npcId(@Nullable Object metadata)
	{
		Object id = metadata instanceof Map ? ((Map<?, ?>) metadata).get("id") : metadata;
		if (id instanceof Integer && (Integer) id >= 0)
		{
			return (Integer) id;
		}
		return null;
	}

	/**
	 * Items with names and per-item prices. Invalid stacks are skipped and
	 * stacks of the same item merged, in first-seen order. Empty if nothing
	 * is left. Client thread only.
	 * Prices follow {@link ItemPrices}.
	 */
	public List<LootItem> items(@Nullable Collection<ItemStack> stacks)
	{
		if (stacks == null || stacks.isEmpty())
		{
			return Collections.emptyList();
		}
		Map<Integer, Long> quantities = new LinkedHashMap<>();
		for (ItemStack stack : stacks)
		{
			if (stack == null || stack.getId() <= 0 || stack.getQuantity() <= 0)
			{
				continue;
			}
			quantities.merge(stack.getId(), (long) stack.getQuantity(), Long::sum);
		}
		List<LootItem> result = new ArrayList<>(quantities.size());
		for (Map.Entry<Integer, Long> entry : quantities.entrySet())
		{
			int itemId = entry.getKey();
			int quantity = ItemPrices.clamp(entry.getValue());
			result.add(new LootItem(itemId, items.name(itemId), quantity, prices.gePrice(itemId),
				prices.haPrice(itemId)));
		}
		return result;
	}
}
