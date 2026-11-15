package com.fireside.bank;

import static com.fireside.bank.FakeBankItems.AGS;
import static com.fireside.bank.FakeBankItems.FIRE_CAPE;
import static com.fireside.bank.FakeBankItems.RANARR_UNF;
import static com.fireside.bank.FakeBankItems.SHARKS;
import static com.fireside.bank.FakeBankItems.SHARKS_NOTED;
import static com.fireside.bank.FakeBankItems.SUPER_COMBAT_1;
import static com.fireside.bank.FakeBankItems.SUPER_COMBAT_2;
import static com.fireside.bank.FakeBankItems.SUPER_COMBAT_3;
import static com.fireside.bank.FakeBankItems.SUPER_COMBAT_4;
import static com.fireside.bank.FakeBankItems.TBOW;
import static com.fireside.bank.FakeBankItems.TBOW_PLACEHOLDER;
import static com.fireside.bank.FakeBankItems.WHIP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fireside.event.BankSnapshot;
import com.fireside.event.PotionStorageSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** Tab assignment, placeholders, fillers, prices and potion storage. */
public class BankSnapshotsTest
{
	private final FakeBankItems items = new FakeBankItems();
	private final BankSnapshots snapshots = new BankSnapshots(items);

	static BankView.Slot slot(int id, int quantity)
	{
		return new BankView.Slot(id, quantity);
	}

	static int[] tabs(int... sizes)
	{
		return Arrays.copyOf(sizes, 9);
	}

	private BankSnapshot bank(List<BankView.Slot> bank, int[] tabs)
	{
		BankSnapshots.BankRead read = snapshots.bank(bank, tabs, Collections.emptyList(), Collections.emptyMap());
		assertNull(read.getSkipReason());
		assertNotNull(read.getSnapshot());
		return read.getSnapshot();
	}

	private String skipReason(List<BankView.Slot> bank, int[] tabs)
	{
		BankSnapshots.BankRead read = snapshots.bank(bank, tabs, Collections.emptyList(), Collections.emptyMap());
		assertNull(read.getSnapshot());
		assertNotNull(read.getSkipReason());
		return read.getSkipReason();
	}

	private static List<Integer> ids(BankSnapshot snapshot)
	{
		List<Integer> ids = new ArrayList<>();
		for (BankSnapshot.Item item : snapshot.getItems())
		{
			ids.add(item.getItemId());
		}
		return ids;
	}

	@Test
	public void itemsKeepContainerOrderAndTabSizesComeFromTheVarbits()
	{
		// Tab 1: whip, AGS. Tab 2: empty. Tab 3: sharks. Main tab: coins.
		BankSnapshot snapshot = bank(Arrays.asList(slot(WHIP, 1), slot(AGS, 1), slot(SHARKS, 250),
			slot(ItemID.COINS, 125_000)), tabs(2, 0, 1));
		assertEquals(Arrays.asList(WHIP, AGS, SHARKS, ItemID.COINS), ids(snapshot));
		assertEquals(Arrays.asList(2, 0, 1, 0, 0, 0, 0, 0, 0), snapshot.getTabSizes());
		// Everything after the 3 tabbed items is the main tab.
		int tabbed = snapshot.getTabSizes().stream().mapToInt(Integer::intValue).sum();
		assertEquals(1, snapshot.getItems().size() - tabbed);
	}

	@Test
	public void allNineTabsAndTheMainTab()
	{
		List<BankView.Slot> bank = new ArrayList<>();
		for (int i = 0; i < 10; i++)
		{
			bank.add(slot(SHARKS, i + 1));
		}
		BankSnapshot snapshot = bank(bank, tabs(1, 1, 1, 1, 1, 1, 1, 1, 1));
		assertEquals(Collections.nCopies(9, 1), snapshot.getTabSizes());
		assertEquals(10, snapshot.getItems().size());
		assertEquals(10, snapshot.getItems().get(9).getQuantity());
	}

	@Test
	public void tabSizesAreCutWhenTheyAddUpToMoreThanTheItems()
	{
		BankSnapshot snapshot = bank(Arrays.asList(slot(WHIP, 1), slot(AGS, 1), slot(SHARKS, 3)),
			tabs(2, 2, 2));
		assertEquals(Arrays.asList(2, 1, 0, 0, 0, 0, 0, 0, 0), snapshot.getTabSizes());
		assertEquals(Arrays.asList(0, 0, 0, 0, 0, 0, 0, 0, 0),
			BankSnapshots.tabSizes(new int[]{3, -1, 0, 0, 0, 0, 0, 0, 0}, 0));
	}

	@Test
	public void placeholderHasTheRealItemIdNamePricesAndQuantityZero()
	{
		BankSnapshot snapshot = bank(Arrays.asList(slot(WHIP, 1), slot(TBOW_PLACEHOLDER, 0)), tabs(2));
		BankSnapshot.Item placeholder = snapshot.getItems().get(1);
		assertEquals(TBOW, placeholder.getItemId());
		assertEquals("Twisted bow", placeholder.getName());
		assertTrue(placeholder.isPlaceholder());
		assertEquals(0, placeholder.getQuantity());
		assertEquals(1_450_000_000, placeholder.getGePrice());
		assertEquals(720_000, placeholder.getHaPrice());
		assertFalse(snapshot.getItems().get(0).isPlaceholder());
	}

	@Test
	public void bankFillersStayInTheirPositionsAndCountInTheTabs()
	{
		BankSnapshot snapshot = bank(Arrays.asList(slot(WHIP, 1), slot(ItemID.BANK_FILLER, 1),
			slot(ItemID.BANK_FILLER, 1), slot(AGS, 1), slot(ItemID.BANK_FILLER, 1)), tabs(3));
		assertEquals(Arrays.asList(WHIP, ItemID.BANK_FILLER, ItemID.BANK_FILLER, AGS, ItemID.BANK_FILLER),
			ids(snapshot));
		assertEquals(Arrays.asList(3, 0, 0, 0, 0, 0, 0, 0, 0), snapshot.getTabSizes());
		BankSnapshot.Item filler = snapshot.getItems().get(1);
		assertFalse(filler.isPlaceholder());
		assertEquals(1, filler.getQuantity());
		assertEquals("Bank filler", filler.getName());
		assertEquals("never valued", 0, filler.getGePrice());
		assertEquals(0, filler.getHaPrice());
	}

	@Test
	public void aFillerWithoutQuantitySkipsTheSnapshot()
	{
		String reason = skipReason(Arrays.asList(slot(WHIP, 1), slot(ItemID.BANK_FILLER, 0)), tabs());
		assertEquals("bank filler with quantity 0 at position 2", reason);
	}

	@Test
	public void anEmptySlotInTheMiddleSkipsTheSnapshotButTrailingOnesAreTrimmed()
	{
		assertEquals("empty slot at position 2 of 3",
			skipReason(Arrays.asList(slot(WHIP, 1), slot(-1, 0), slot(AGS, 1)), tabs()));
		BankSnapshot snapshot = bank(Arrays.asList(slot(WHIP, 1), slot(AGS, 1), slot(-1, 0), slot(-1, 0)),
			tabs(1));
		assertEquals(Arrays.asList(WHIP, AGS), ids(snapshot));
	}

	@Test
	public void skipReasonsNeverNameItems()
	{
		String reason = skipReason(Arrays.asList(slot(WHIP, 0)), tabs());
		assertFalse(reason.contains("whip"));
		assertEquals("item with quantity 0 at position 1", reason);
	}

	@Test
	public void pricesFollowTheLootRules()
	{
		BankSnapshot snapshot = bank(Arrays.asList(slot(FIRE_CAPE, 1), slot(ItemID.COINS, 10),
			slot(ItemID.PLATINUM, 3)), tabs());
		BankSnapshot.Item cape = snapshot.getItems().get(0);
		assertEquals("untradeable", 0, cape.getGePrice());
		assertEquals(25_000, cape.getHaPrice());
		assertEquals(1, snapshot.getItems().get(1).getGePrice());
		assertEquals(1, snapshot.getItems().get(1).getHaPrice());
		assertEquals(1000, snapshot.getItems().get(2).getGePrice());
		assertEquals(1000, snapshot.getItems().get(2).getHaPrice());
	}

	@Test
	public void unknownItemsGetAName()
	{
		BankSnapshot snapshot = bank(Collections.singletonList(slot(99_999, 1)), tabs());
		assertEquals("Item 99999", snapshot.getItems().get(0).getName());
	}

	@Test
	public void inventoryKeepsNotedIdsAndSkipsEmptySlots()
	{
		List<BankView.Slot> inventory = Arrays.asList(slot(SHARKS_NOTED, 500), slot(-1, 0), slot(ItemID.COINS,
			125_000));
		BankSnapshot snapshot = snapshots.bank(Collections.emptyList(), tabs(), inventory, Collections.emptyMap())
			.getSnapshot();
		assertNotNull(snapshot);
		assertEquals(2, snapshot.getInventory().size());
		assertEquals(SHARKS_NOTED, snapshot.getInventory().get(0).getItemId());
		assertEquals(500, snapshot.getInventory().get(0).getQuantity());
		assertEquals(900, snapshot.getInventory().get(0).getGePrice());
		assertEquals(ItemID.COINS, snapshot.getInventory().get(1).getItemId());
	}

	@Test
	public void equipmentHasSlotNames()
	{
		Map<String, BankView.Slot> worn = new LinkedHashMap<>();
		worn.put("CAPE", slot(FIRE_CAPE, 1));
		worn.put("WEAPON", slot(WHIP, 1));
		BankSnapshot snapshot = snapshots.bank(Collections.emptyList(), tabs(), Collections.emptyList(), worn)
			.getSnapshot();
		assertNotNull(snapshot);
		assertEquals(2, snapshot.getEquipment().size());
		assertEquals("CAPE", snapshot.getEquipment().get(0).getSlot());
		assertEquals("WEAPON", snapshot.getEquipment().get(1).getSlot());
		assertEquals("Abyssal whip", snapshot.getEquipment().get(1).getName());
	}

	@Test
	public void emptyBank()
	{
		BankSnapshot snapshot = bank(Collections.emptyList(), tabs(4, 2));
		assertTrue(snapshot.getItems().isEmpty());
		assertEquals(Collections.nCopies(9, 0), snapshot.getTabSizes());
		assertTrue(snapshot.getContentHash().matches("sha256:[0-9a-f]{64}"));
	}

	// ---- Potion storage -----------------------------------------------------

	private static BankView.RawPotion potion(int doses, int... variants)
	{
		return new BankView.RawPotion(Arrays.copyOf(variants, 4), doses);
	}

	private static BankView.RawPotion superCombat(int doses)
	{
		return potion(doses, SUPER_COMBAT_1, SUPER_COMBAT_2, SUPER_COMBAT_3, SUPER_COMBAT_4);
	}

	@Test
	public void potionUsesTheHighestDoseVariantAndItsPrice()
	{
		PotionStorageSnapshot snapshot = snapshots.potions(Collections.singletonList(superCombat(312)), 0);
		assertEquals(1, snapshot.getPotions().size());
		PotionStorageSnapshot.Potion p = snapshot.getPotions().get(0);
		assertEquals(SUPER_COMBAT_4, p.getItemId());
		assertEquals("Super combat potion", p.getName());
		assertEquals(312, p.getDoses());
		assertEquals(4, p.getItemDoses());
		assertEquals("price of the whole 4-dose item", 12_400, p.getGePrice());
	}

	@Test
	public void potionWhoseLargestVariantHasThreeDoses()
	{
		PotionStorageSnapshot snapshot = snapshots.potions(Collections.singletonList(
			potion(7, SUPER_COMBAT_1, SUPER_COMBAT_2, SUPER_COMBAT_3, -1)), 0);
		assertEquals(SUPER_COMBAT_3, snapshot.getPotions().get(0).getItemId());
		assertEquals(3, snapshot.getPotions().get(0).getItemDoses());
	}

	@Test
	public void unfinishedPotionsHaveOneDoseAndKeepTheirName()
	{
		PotionStorageSnapshot snapshot = snapshots.potions(Collections.singletonList(potion(20, RANARR_UNF)), 0);
		PotionStorageSnapshot.Potion p = snapshot.getPotions().get(0);
		assertEquals(RANARR_UNF, p.getItemId());
		assertEquals(1, p.getItemDoses());
		assertEquals("Ranarr potion (unf)", p.getName());
	}

	@Test
	public void vialsComeLastAsOneDoseEach()
	{
		PotionStorageSnapshot snapshot = snapshots.potions(Collections.singletonList(superCombat(4)), 120);
		assertEquals(2, snapshot.getPotions().size());
		PotionStorageSnapshot.Potion vials = snapshot.getPotions().get(1);
		assertEquals(ItemID.VIAL_EMPTY, vials.getItemId());
		assertEquals("Vial", vials.getName());
		assertEquals(120, vials.getDoses());
		assertEquals(1, vials.getItemDoses());
	}

	@Test
	public void emptyPotionsAreLeftOutAndDuplicatesMerged()
	{
		PotionStorageSnapshot snapshot = snapshots.potions(Arrays.asList(superCombat(0), potion(5),
			superCombat(3), superCombat(2)), 0);
		assertEquals(1, snapshot.getPotions().size());
		assertEquals(5, snapshot.getPotions().get(0).getDoses());
	}

	@Test
	public void emptyPotionStorage()
	{
		PotionStorageSnapshot snapshot = snapshots.potions(Collections.emptyList(), 0);
		assertTrue(snapshot.getPotions().isEmpty());
		assertTrue(snapshot.getContentHash().matches("sha256:[0-9a-f]{64}"));
	}
}
