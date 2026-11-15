package com.fireside.bank;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.fireside.event.BankSnapshot;
import com.fireside.event.PotionStorageSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** What the content hash covers: content in order, never names or prices. */
public class ContentHashTest
{
	private static final List<Integer> TABS = Arrays.asList(2, 0, 0, 0, 0, 0, 0, 0, 0);

	private static BankSnapshot.Item item(int id, int quantity)
	{
		return new BankSnapshot.Item(id, "Item " + id, quantity, false, 100, 10);
	}

	private static BankSnapshot.Item placeholder(int id)
	{
		return new BankSnapshot.Item(id, "Item " + id, 0, true, 100, 10);
	}

	private static List<BankSnapshot.Item> bank()
	{
		return new ArrayList<>(Arrays.asList(item(4151, 1), item(ItemID.BANK_FILLER, 1), item(385, 250),
			placeholder(20997)));
	}

	private static List<BankSnapshot.InventoryItem> inventory()
	{
		return new ArrayList<>(Collections.singletonList(new BankSnapshot.InventoryItem(995, "Coins", 5000, 1, 1)));
	}

	private static List<BankSnapshot.WornItem> worn()
	{
		return new ArrayList<>(Collections.singletonList(
			new BankSnapshot.WornItem("WEAPON", 4151, "Abyssal whip", 1, 1, 1)));
	}

	private static String hash(List<Integer> tabs, List<BankSnapshot.Item> items)
	{
		return ContentHash.bank(tabs, items, inventory(), worn());
	}

	@Test
	public void formatIsSha256WithLowercaseHex()
	{
		assertTrue(hash(TABS, bank()).matches("^sha256:[0-9a-f]{64}$"));
		// A fixed value, so an accidental format change (which resends every bank) is noticed.
		assertEquals("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
			ContentHash.sha256(""));
		assertEquals(hash(TABS, bank()), hash(TABS, bank()));
		// The canonical text "v1|tabs:1,0,…|bank:4151:1:i;20998:0:p;|inv:995:5000;|worn:WEAPON:4151:1;".
		assertEquals("sha256:3d17b67309fa8a852f6f095ddc9592032861651aefbde7d3d2363a8ed21a1ff2",
			ContentHash.bank(Arrays.asList(1, 0, 0, 0, 0, 0, 0, 0, 0),
				Arrays.asList(item(4151, 1), placeholder(20998)), inventory(), worn()));
	}

	@Test
	public void namesAndPricesDoNotCount()
	{
		List<BankSnapshot.Item> repriced = bank();
		repriced.set(0, new BankSnapshot.Item(4151, "Renamed whip", 1, false, 9_999_999, 0));
		assertEquals(hash(TABS, bank()), hash(TABS, repriced));
		List<BankSnapshot.InventoryItem> inv = Collections.singletonList(
			new BankSnapshot.InventoryItem(995, "Gold", 5000, 7, 7));
		assertEquals(hash(TABS, bank()), ContentHash.bank(TABS, bank(), inv, worn()));
	}

	@Test
	public void everyContentChangeCounts()
	{
		String base = hash(TABS, bank());
		List<BankSnapshot.Item> quantity = bank();
		quantity.set(2, item(385, 249));
		assertNotEquals(base, hash(TABS, quantity));

		List<BankSnapshot.Item> id = bank();
		id.set(0, item(4152, 1));
		assertNotEquals(base, hash(TABS, id));

		List<BankSnapshot.Item> nowPlaceholder = bank();
		nowPlaceholder.set(0, placeholder(4151));
		List<BankSnapshot.Item> nowItem = bank();
		nowItem.set(0, item(4151, 0));
		assertNotEquals("placeholder flag", hash(TABS, nowPlaceholder), hash(TABS, nowItem));

		assertNotEquals("tab size", base, hash(Arrays.asList(1, 1, 0, 0, 0, 0, 0, 0, 0), bank()));

		List<BankSnapshot.Item> order = bank();
		Collections.swap(order, 0, 2);
		assertNotEquals("order", base, hash(TABS, order));

		List<BankSnapshot.WornItem> otherSlot = Collections.singletonList(
			new BankSnapshot.WornItem("SHIELD", 4151, "Abyssal whip", 1, 1, 1));
		assertNotEquals("slot", base, ContentHash.bank(TABS, bank(), inventory(), otherSlot));

		List<BankSnapshot.InventoryItem> inv = Collections.singletonList(
			new BankSnapshot.InventoryItem(995, "Coins", 5001, 1, 1));
		assertNotEquals("inventory", base, ContentHash.bank(TABS, bank(), inv, worn()));
	}

	@Test
	public void movingABankFillerIsAChange()
	{
		List<BankSnapshot.Item> moved = bank();
		Collections.swap(moved, 1, 2);
		assertNotEquals(hash(TABS, bank()), hash(TABS, moved));
		List<BankSnapshot.Item> removed = bank();
		removed.remove(1);
		assertNotEquals(hash(TABS, bank()), hash(TABS, removed));
	}

	@Test
	public void potionHashCoversItemsAndDosesOnly()
	{
		List<PotionStorageSnapshot.Potion> potions = Collections.singletonList(
			new PotionStorageSnapshot.Potion(12695, "Super combat potion", 312, 4, 12_400, 0));
		List<PotionStorageSnapshot.Potion> repriced = Collections.singletonList(
			new PotionStorageSnapshot.Potion(12695, "Other name", 312, 4, 1, 1));
		List<PotionStorageSnapshot.Potion> moreDoses = Collections.singletonList(
			new PotionStorageSnapshot.Potion(12695, "Super combat potion", 313, 4, 12_400, 0));
		assertEquals(ContentHash.potions(potions), ContentHash.potions(repriced));
		assertNotEquals(ContentHash.potions(potions), ContentHash.potions(moreDoses));
		assertNotEquals("its own hash", ContentHash.potions(Collections.emptyList()),
			ContentHash.bank(Collections.nCopies(9, 0), Collections.emptyList(), Collections.emptyList(),
				Collections.emptyList()));
	}
}
