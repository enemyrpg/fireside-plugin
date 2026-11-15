package com.fireside.bank;

import static com.fireside.bank.BankSnapshotsTest.slot;
import static com.fireside.bank.BankSnapshotsTest.tabs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fireside.contract.ContractPackage;
import com.fireside.event.BankSnapshot;
import com.fireside.event.Event;
import com.fireside.event.EventJson;
import com.fireside.event.EventPayload;
import com.fireside.event.PotionStorageSnapshot;
import com.fireside.event.UuidV7Generator;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.ItemID;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

/**
 * Snapshots as {@link BankSnapshots} builds them validate against the pinned
 * schemas and keep the rules JSON Schema cannot express (the
 * {@code invalid-semantic/} fixtures): tab sizes never sum to more than the
 * items, each equipment slot once, each potion item once.
 */
public class BankSnapshotContractTest
{
	private static final Instant AT = Instant.parse("2026-11-15T18:00:00.000Z");

	private final EventJson json = new EventJson(RuneLiteAPI.GSON);
	private final UuidV7Generator ids = new UuidV7Generator();
	private final String sessionId = ids.next();
	private final BankSnapshots snapshots = new BankSnapshots(new FakeBankItems());

	private String validate(EventPayload payload)
	{
		String line = json.toJson(new Event(ids.next(), 1234567890123456789L, AT, sessionId, payload));
		List<Set<ValidationMessage>> errors = new ArrayList<>();
		for (JsonSchema schema : Arrays.asList(ContractPackage.schema("schema/event.json"),
			ContractPackage.schema("schema/events/" + payload.type().wireName() + ".json")))
		{
			Set<ValidationMessage> e = ContractPackage.validate(schema, line);
			if (!e.isEmpty())
			{
				errors.add(e);
			}
		}
		assertTrue(errors.toString(), errors.isEmpty());
		return line;
	}

	private static void assertSemanticRules(BankSnapshot snapshot)
	{
		int tabbed = snapshot.getTabSizes().stream().mapToInt(Integer::intValue).sum();
		assertTrue("tab sizes sum to more than the items", tabbed <= snapshot.getItems().size());
		Set<String> slots = new HashSet<>();
		for (BankSnapshot.WornItem item : snapshot.getEquipment())
		{
			assertTrue("equipment slot twice: " + item.getSlot(), slots.add(item.getSlot()));
		}
	}

	@Test
	public void aFullBankWithEverythingValidatesAndFitsInABatch()
	{
		List<BankView.Slot> bank = new ArrayList<>();
		for (int i = 0; i < 1410; i++)
		{
			switch (i % 5)
			{
				case 0:
					bank.add(slot(FakeBankItems.TBOW_PLACEHOLDER, 0));
					break;
				case 1:
					bank.add(slot(ItemID.BANK_FILLER, 1));
					break;
				case 2:
					bank.add(slot(ItemID.COINS, Integer.MAX_VALUE));
					break;
				case 3:
					bank.add(slot(30_000 + i, 1));
					break;
				default:
					bank.add(slot(FakeBankItems.SHARKS, i));
			}
		}
		List<BankView.Slot> inventory = new ArrayList<>();
		for (int i = 0; i < 28; i++)
		{
			inventory.add(slot(FakeBankItems.SHARKS_NOTED, i + 1));
		}
		Map<String, BankView.Slot> worn = new LinkedHashMap<>();
		for (EquipmentInventorySlot s : EquipmentInventorySlot.values())
		{
			worn.put(s.name(), slot(FakeBankItems.WHIP, 1));
		}
		// More in the varbits than in the bank: cut to fit.
		BankSnapshot snapshot = snapshots.bank(bank, tabs(400, 400, 400, 400), inventory, worn).getSnapshot();
		assertNotNull(snapshot);
		assertSemanticRules(snapshot);
		String line = validate(snapshot);
		int bytes = line.getBytes(StandardCharsets.UTF_8).length;
		assertTrue("one snapshot is " + bytes + " bytes", bytes < 300_000);
	}

	@Test
	public void emptyOnlyPlaceholdersAndFillersValidate()
	{
		for (List<BankView.Slot> bank : Arrays.asList(
			Collections.<BankView.Slot>emptyList(),
			Collections.singletonList(slot(FakeBankItems.TBOW_PLACEHOLDER, 0)),
			Arrays.asList(slot(ItemID.BANK_FILLER, 1), slot(ItemID.BANK_FILLER, 1), slot(FakeBankItems.WHIP, 1))))
		{
			BankSnapshot snapshot = snapshots.bank(bank, tabs(2), Collections.emptyList(), Collections.emptyMap())
				.getSnapshot();
			assertNotNull(snapshot);
			assertSemanticRules(snapshot);
			validate(snapshot);
		}
	}

	@Test
	public void potionStorageValidatesAndEachItemOnce()
	{
		List<BankView.RawPotion> raw = Arrays.asList(
			new BankView.RawPotion(new int[]{FakeBankItems.SUPER_COMBAT_1, FakeBankItems.SUPER_COMBAT_2,
				FakeBankItems.SUPER_COMBAT_3, FakeBankItems.SUPER_COMBAT_4}, 312),
			new BankView.RawPotion(new int[]{FakeBankItems.SUPER_COMBAT_1, FakeBankItems.SUPER_COMBAT_2,
				FakeBankItems.SUPER_COMBAT_3, FakeBankItems.SUPER_COMBAT_4}, 4),
			new BankView.RawPotion(new int[]{FakeBankItems.RANARR_UNF, -1, -1, -1}, 20));
		PotionStorageSnapshot snapshot = snapshots.potions(raw, 120);
		Set<Integer> seen = new HashSet<>();
		for (PotionStorageSnapshot.Potion potion : snapshot.getPotions())
		{
			assertTrue("potion twice: " + potion.getItemId(), seen.add(potion.getItemId()));
		}
		assertEquals(3, snapshot.getPotions().size());
		validate(snapshot);
		validate(snapshots.potions(Collections.emptyList(), 0));
	}
}
