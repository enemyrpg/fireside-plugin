package com.fireside.bank;

import com.fireside.event.BankSnapshot;
import com.fireside.event.PotionStorageSnapshot;
import com.fireside.loot.ItemLookup;
import com.fireside.loot.ItemPrices;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.Value;
import net.runelite.api.gameval.ItemID;

/**
 * Builds the snapshot payloads from raw container data (04, sections 1 and 2).
 * Pure apart from the item lookups. Client thread only.
 * <ul>
 * <li>Bank items in container order, 1:1 with the game. The first
 * {@code tabSizes[0]} items are tab 1, and so on; the rest is the main tab.</li>
 * <li>A placeholder gets the real item's ID, name and prices,
 * {@code placeholder: true} and quantity 0.</li>
 * <li>A bank filler is an item in its position, with its container quantity
 * and prices 0. It counts in the tab sizes and the hash like any item.</li>
 * <li>Empty slots at the end are trimmed. An empty slot (or a filler or item
 * without a quantity) anywhere else would shift positions or break the
 * contract, so the snapshot is skipped with a reason instead.</li>
 * <li>Tab sizes that add up to more than the items are cut, last tab first.</li>
 * </ul>
 */
public final class BankSnapshots
{
	/** Name used when the game has none (new or unknown items): the contract needs a name. */
	static final String UNKNOWN_NAME_PREFIX = "Item ";
	private static final Pattern DOSE_SUFFIX = Pattern.compile("\\s*\\(\\d\\)$");

	/** Either a snapshot or the reason it was skipped (no item names or contents). */
	@Value
	public static class BankRead
	{
		@Nullable
		BankSnapshot snapshot;
		@Nullable
		String skipReason;
	}

	private final ItemLookup items;
	private final ItemPrices prices;

	public BankSnapshots(ItemLookup items)
	{
		this.items = items;
		this.prices = new ItemPrices(items);
	}

	public BankRead bank(List<BankView.Slot> bank, int[] tabVarbits, List<BankView.Slot> inventory,
		Map<String, BankView.Slot> equipment)
	{
		int end = bank.size();
		while (end > 0 && bank.get(end - 1).getItemId() <= 0)
		{
			end--;
		}
		List<BankSnapshot.Item> bankItems = new ArrayList<>(end);
		for (int i = 0; i < end; i++)
		{
			BankView.Slot slot = bank.get(i);
			int id = slot.getItemId();
			int quantity = slot.getQuantity();
			if (id <= 0)
			{
				return skip("empty slot at position " + (i + 1) + " of " + end);
			}
			if (id == ItemID.BANK_FILLER)
			{
				if (quantity < 1)
				{
					return skip("bank filler with quantity " + quantity + " at position " + (i + 1));
				}
				bankItems.add(new BankSnapshot.Item(id, name(id), quantity, false, 0, 0));
				continue;
			}
			int real = items.placeholderOf(id);
			if (real > 0)
			{
				bankItems.add(new BankSnapshot.Item(real, name(real), 0, true, prices.gePrice(real),
					prices.haPrice(real)));
				continue;
			}
			if (quantity < 1)
			{
				return skip("item with quantity " + quantity + " at position " + (i + 1));
			}
			bankItems.add(new BankSnapshot.Item(id, name(id), quantity, false, prices.gePrice(id),
				prices.haPrice(id)));
		}

		List<Integer> tabSizes = tabSizes(tabVarbits, bankItems.size());

		List<BankSnapshot.InventoryItem> inv = new ArrayList<>();
		for (BankView.Slot slot : inventory)
		{
			if (slot.getItemId() > 0 && slot.getQuantity() > 0)
			{
				int id = slot.getItemId();
				inv.add(new BankSnapshot.InventoryItem(id, name(id), slot.getQuantity(), prices.gePrice(id),
					prices.haPrice(id)));
			}
		}

		List<BankSnapshot.WornItem> worn = new ArrayList<>();
		for (Map.Entry<String, BankView.Slot> entry : equipment.entrySet())
		{
			BankView.Slot slot = entry.getValue();
			if (slot.getItemId() > 0 && slot.getQuantity() > 0)
			{
				int id = slot.getItemId();
				worn.add(new BankSnapshot.WornItem(entry.getKey(), id, name(id), slot.getQuantity(),
					prices.gePrice(id), prices.haPrice(id)));
			}
		}

		String hash = ContentHash.bank(tabSizes, bankItems, inv, worn);
		return new BankRead(new BankSnapshot(tabSizes, bankItems, inv, worn, hash), null);
	}

	/**
	 * Potion storage, in storage order. {@code itemId} is the highest dose
	 * variant that exists, {@code itemDoses} its doses. Vials come last as
	 * {@code VIAL_EMPTY} with one "dose" per vial. A potion seen twice is merged.
	 */
	public PotionStorageSnapshot potions(List<BankView.RawPotion> raw, int vials)
	{
		Map<Integer, int[]> merged = new LinkedHashMap<>();
		for (BankView.RawPotion potion : raw)
		{
			if (potion.getDoses() <= 0)
			{
				continue;
			}
			int[] variants = potion.getVariants();
			int itemDoses = 0;
			for (int d = variants.length; d >= 1; d--)
			{
				if (variants[d - 1] > 0)
				{
					itemDoses = d;
					break;
				}
			}
			if (itemDoses == 0)
			{
				continue;
			}
			int itemId = variants[itemDoses - 1];
			int[] entry = merged.computeIfAbsent(itemId, k -> new int[]{0, 0});
			entry[0] = ItemPrices.clamp((long) entry[0] + potion.getDoses());
			entry[1] = itemDoses;
		}
		if (vials > 0)
		{
			int[] entry = merged.computeIfAbsent(ItemID.VIAL_EMPTY, k -> new int[]{0, 1});
			entry[0] = ItemPrices.clamp((long) entry[0] + vials);
		}
		List<PotionStorageSnapshot.Potion> potions = new ArrayList<>(merged.size());
		for (Map.Entry<Integer, int[]> entry : merged.entrySet())
		{
			int id = entry.getKey();
			String name = DOSE_SUFFIX.matcher(name(id)).replaceFirst("");
			potions.add(new PotionStorageSnapshot.Potion(id, name.isEmpty() ? name(id) : name,
				entry.getValue()[0], entry.getValue()[1], prices.gePrice(id), prices.haPrice(id)));
		}
		return new PotionStorageSnapshot(potions, ContentHash.potions(potions));
	}

	/** The 9 varbit sizes, cut so they never add up to more than {@code itemCount} (last tab first). */
	static List<Integer> tabSizes(int[] varbits, int itemCount)
	{
		int[] sizes = Arrays.copyOf(varbits, BankSnapshot.TAB_COUNT);
		int remaining = itemCount;
		List<Integer> result = new ArrayList<>(BankSnapshot.TAB_COUNT);
		for (int size : sizes)
		{
			int kept = Math.min(Math.max(0, size), remaining);
			remaining -= kept;
			result.add(kept);
		}
		return result;
	}

	private String name(int itemId)
	{
		String name = items.name(itemId);
		if (name == null || name.trim().isEmpty() || "null".equals(name))
		{
			return UNKNOWN_NAME_PREFIX + itemId;
		}
		return name;
	}

	private static BankRead skip(String reason)
	{
		return new BankRead(null, reason);
	}
}
