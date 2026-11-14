package com.fireside.loot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import com.fireside.contract.ContractPackage;
import com.fireside.event.Event;
import com.fireside.event.EventJson;
import com.fireside.event.Loot;
import com.fireside.event.LootItem;
import com.fireside.event.LootSource;
import com.fireside.event.UuidV7Generator;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemStack;
import net.runelite.http.api.RuneLiteAPI;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.Test;

public class LootMapperTest
{
	private static final int VISAGE = ItemID.DRAGONFIRE_VISAGE;
	private static final int BONES = ItemID.DRAGON_BONES;
	private static final int BONES_NOTED = ItemID.Cert.DRAGON_BONES;
	private static final int CLUE = ItemID.TRAIL_CLUE_HARD_MAP001;

	private final FakeItems items = new FakeItems();
	private final LootMapper mapper = new LootMapper(items);

	// Sources

	@Test
	public void npcWithIdFromMetadata()
	{
		assertEquals(LootSource.npc("Vorkath", 8061, 732),
			LootMapper.source(LootRecordType.NPC, "Vorkath", 732, 8061));
	}

	@Test
	public void npcIdFromMapMetadata()
	{
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("id", 1047);
		metadata.put("world", 420);
		assertEquals(LootSource.npc("Cave horror", 1047, 80),
			LootMapper.source(LootRecordType.NPC, "Cave horror", 80, metadata));
	}

	@Test
	public void npcWithUnknownMetadataOrNoCombatLevel()
	{
		assertEquals(LootSource.npc("Goblin", null, null),
			LootMapper.source(LootRecordType.NPC, "Goblin", 0, new Object()));
		assertEquals(LootSource.npc("Goblin", null, null),
			LootMapper.source(LootRecordType.NPC, "Goblin", -1, null));
	}

	@Test
	public void playerLootHasNoName()
	{
		LootSource source = LootMapper.source(LootRecordType.PLAYER, "Some Player", 126, null);
		assertEquals(LootSource.player(), source);
		assertNull(source.getName());
		assertNull(source.getNpcId());
		assertNull(source.getCombatLevel());
	}

	@Test
	public void playerLootIsSentWithoutNameAndMatchesTheSchema()
	{
		LootSource source = LootMapper.source(LootRecordType.PLAYER, "Some Player", 126, null);
		List<LootItem> mapped = mapper.items(Collections.singletonList(new ItemStack(ItemID.COINS, 1500)));
		String line = new EventJson(RuneLiteAPI.GSON).toJson(Event.create(new UuidV7Generator(), 42L,
			Instant.parse("2026-11-13T19:00:00.000Z"), new UuidV7Generator().next(), new Loot(source, mapped, null)));

		assertFalse("No player name anywhere in the event", line.contains("Some Player"));
		JsonObject payload = new JsonParser().parse(line).getAsJsonObject().getAsJsonObject("payload");
		assertEquals("{\"type\":\"PLAYER\",\"name\":null}", payload.get("source").toString());
		for (JsonSchema schema : Arrays.asList(ContractPackage.schema("schema/event.json"),
			ContractPackage.schema("schema/events/loot.json")))
		{
			Set<ValidationMessage> errors = ContractPackage.validate(schema, line);
			assertTrue(errors + "\n" + line, errors.isEmpty());
		}
	}

	@Test
	public void clueCasketsAreClues()
	{
		assertEquals(LootSource.clue("Clue Scroll (Elite)"),
			LootMapper.source(LootRecordType.EVENT, "Clue Scroll (Elite)", -1, null));
		assertEquals(LootSource.clue("Clue Scroll (Beginner)"),
			LootMapper.source(LootRecordType.EVENT, "Clue Scroll (Beginner)", -1, null));
	}

	@Test
	public void otherEventsAreEvents()
	{
		assertEquals(LootSource.event("Barrows"), LootMapper.source(LootRecordType.EVENT, "Barrows", -1, null));
		assertEquals(LootSource.event("Chambers of Xeric"),
			LootMapper.source(LootRecordType.EVENT, "Chambers of Xeric", -1, null));
		assertEquals(LootSource.event("Casket (Tempoross)"),
			LootMapper.source(LootRecordType.EVENT, "Casket (Tempoross)", -1, new int[]{1}));
	}

	@Test
	public void pickpocketAndUnknownAreOther()
	{
		assertEquals(LootSource.other("Master Farmer"),
			LootMapper.source(LootRecordType.PICKPOCKET, "Master Farmer", -1, null));
		assertEquals(LootSource.other("Maggot King"),
			LootMapper.source(LootRecordType.UNKNOWN, "Maggot King", -1, null));
	}

	@Test
	public void sourceWithoutNameIsSkipped()
	{
		assertNull(LootMapper.source(LootRecordType.NPC, null, 10, null));
		assertNull(LootMapper.source(LootRecordType.EVENT, " ", -1, null));
		assertNull(LootMapper.source(null, "Goblin", 2, null));
	}

	// Items and prices

	@Test
	public void itemsGetNameAndPricesPerItem()
	{
		List<LootItem> mapped = mapper.items(Arrays.asList(new ItemStack(VISAGE, 1), new ItemStack(BONES, 2)));
		assertEquals(Arrays.asList(
			new LootItem(VISAGE, "Draconic visage", 1, 2_850_000, 60_000),
			new LootItem(BONES, "Dragon bones", 2, 2_100, 0)), mapped);
	}

	@Test
	public void notedItemsUseTheUnnotedTradeabilityAndPrice()
	{
		assertEquals(Collections.singletonList(new LootItem(BONES_NOTED, "Dragon bones", 50, 2_100, 0)),
			mapper.items(Collections.singletonList(new ItemStack(BONES_NOTED, 50))));
	}

	@Test
	public void coinsAndPlatinumTokens()
	{
		List<LootItem> mapped = mapper.items(Arrays.asList(new ItemStack(ItemID.COINS, 25_000),
			new ItemStack(ItemID.PLATINUM, 3)));
		assertEquals(Arrays.asList(
			new LootItem(ItemID.COINS, "Coins", 25_000, 1, 1),
			new LootItem(ItemID.PLATINUM, "Platinum token", 3, 1000, 1000)), mapped);
	}

	@Test
	public void untradeablesHaveNoGePrice()
	{
		items.price.put(CLUE, 5_000L);
		assertEquals(Collections.singletonList(new LootItem(CLUE, "Clue scroll (hard)", 1, 0, 0)),
			mapper.items(Collections.singletonList(new ItemStack(CLUE, 1))));
	}

	@Test
	public void pricesAreClampedToTheIntRange()
	{
		items.price.put(VISAGE, 5_000_000_000L);
		items.ha.put(VISAGE, -5);
		LootItem item = mapper.items(Collections.singletonList(new ItemStack(VISAGE, 1))).get(0);
		assertEquals(Integer.MAX_VALUE, item.getGePrice());
		assertEquals(0, item.getHaPrice());
	}

	@Test
	public void stacksOfTheSameItemAreMergedInFirstSeenOrder()
	{
		List<LootItem> mapped = mapper.items(Arrays.asList(new ItemStack(BONES, 1), new ItemStack(VISAGE, 1),
			new ItemStack(BONES, 2)));
		assertEquals(2, mapped.size());
		assertEquals(BONES, mapped.get(0).getItemId());
		assertEquals(3, mapped.get(0).getQuantity());
		assertEquals(VISAGE, mapped.get(1).getItemId());
	}

	@Test
	public void mergedQuantityDoesNotOverflow()
	{
		List<LootItem> mapped = mapper.items(Arrays.asList(new ItemStack(ItemID.COINS, Integer.MAX_VALUE),
			new ItemStack(ItemID.COINS, 10)));
		assertEquals(Integer.MAX_VALUE, mapped.get(0).getQuantity());
	}

	@Test
	public void invalidStacksAreSkipped()
	{
		assertTrue(mapper.items(Arrays.asList(new ItemStack(-1, 1), new ItemStack(BONES, 0),
			new ItemStack(BONES, -3))).isEmpty());
		assertTrue(mapper.items(Collections.emptyList()).isEmpty());
		assertTrue(mapper.items(null).isEmpty());
	}

	/** Item facts as RuneLite's item manager would give them. */
	private static final class FakeItems implements ItemLookup
	{
		final Map<Integer, String> names = new HashMap<>();
		final Map<Integer, Long> price = new HashMap<>();
		final Map<Integer, Integer> ha = new HashMap<>();
		final Set<Integer> untradeable = new HashSet<>();

		FakeItems()
		{
			names.put(VISAGE, "Draconic visage");
			price.put(VISAGE, 2_850_000L);
			ha.put(VISAGE, 60_000);
			names.put(BONES, "Dragon bones");
			names.put(BONES_NOTED, "Dragon bones");
			price.put(BONES, 2_100L);
			price.put(BONES_NOTED, 2_100L);
			names.put(ItemID.COINS, "Coins");
			names.put(ItemID.PLATINUM, "Platinum token");
			names.put(CLUE, "Clue scroll (hard)");
			untradeable.add(CLUE);
		}

		@Override
		public String name(int itemId)
		{
			return names.getOrDefault(itemId, "Unknown");
		}

		@Override
		public boolean tradeable(int itemId)
		{
			return !untradeable.contains(itemId);
		}

		@Override
		public long gePrice(int itemId)
		{
			return price.getOrDefault(itemId, 0L);
		}

		@Override
		public int haPrice(int itemId)
		{
			return ha.getOrDefault(itemId, 0);
		}
	}
}
