package com.fireside.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import com.fireside.bank.ContentHash;
import com.fireside.contract.ContractPackage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

/**
 * Every event type, serialized as the plugin sends it, validates against the
 * pinned contract (schema/event.json and the per-type schema).
 */
public class EventSerializationContractTest
{
	private static final long ACCOUNT = 1234567890123456789L;
	private static final Instant AT = Instant.parse("2026-11-08T19:42:13.512Z");

	/** The same Gson RuneLite injects. */
	private final EventJson json = new EventJson(RuneLiteAPI.GSON);
	private final UuidV7Generator ids = new UuidV7Generator();
	private final String sessionId = ids.next();
	private final JsonSchema eventSchema = ContractPackage.schema("schema/event.json");

	/** Skill names in RuneLite's {@code Skill} order, as the contract requires them. */
	static final List<String> SKILLS = Arrays.asList("ATTACK", "DEFENCE", "STRENGTH", "HITPOINTS", "RANGED",
		"PRAYER", "MAGIC", "COOKING", "WOODCUTTING", "FLETCHING", "FISHING", "FIREMAKING", "CRAFTING", "SMITHING",
		"MINING", "HERBLORE", "AGILITY", "THIEVING", "SLAYER", "FARMING", "RUNECRAFT", "HUNTER", "CONSTRUCTION",
		"SAILING");

	static Map<String, EventPayload> examples()
	{
		Map<String, EventPayload> examples = new LinkedHashMap<>();
		examples.put("session.start", new SessionStart("Zezima", AccountType.IRONMAN, 420, "0.1.0"));
		examples.put("session.heartbeat", new SessionHeartbeat(12342, 420, Collections.singletonList("MEMBERS"),
			"GERMANY", "Trade - Members", false));
		examples.put("session.heartbeat (world list not loaded)", new SessionHeartbeat(12342, 420,
			Collections.emptyList(), null, null, true));
		examples.put("session.heartbeat (empty activity)", new SessionHeartbeat(0, 302,
			Arrays.asList("MEMBERS", "PVP"), "UNITED_KINGDOM", "", false));
		examples.put("session.afk", new SessionAfk(Instant.parse("2026-11-08T19:10:00Z"),
			Instant.parse("2026-11-08T19:24:30.250Z")));
		for (SessionEnd.Reason reason : SessionEnd.Reason.values())
		{
			examples.put("session.end (" + reason + ")", new SessionEnd(reason));
		}
		Map<String, XpSnapshot.SkillXp> skills = new LinkedHashMap<>();
		for (String skill : SKILLS)
		{
			skills.put(skill, new XpSnapshot.SkillXp(13_034_431, 99));
		}
		skills.put("ATTACK", new XpSnapshot.SkillXp(0, 1));
		skills.put("SLAYER", new XpSnapshot.SkillXp(200_000_000, 99));
		for (XpSnapshot.Reason reason : XpSnapshot.Reason.values())
		{
			examples.put("xp.snapshot (" + reason + ")", new XpSnapshot(reason, skills));
		}
		Map<String, XpSnapshot.SkillXp> withNewSkill = new LinkedHashMap<>(skills);
		withNewSkill.put("NEW_SKILL", new XpSnapshot.SkillXp(0, 1));
		examples.put("xp.snapshot (unknown extra skill)", new XpSnapshot(XpSnapshot.Reason.LOGIN, withNewSkill));

		List<LootItem> items = Arrays.asList(
			new LootItem(11286, "Draconic visage", 1, 2_850_000, 60_000),
			new LootItem(536, "Dragon bones", 2, 2100, 0));
		examples.put("loot (NPC)", new Loot(LootSource.npc("Vorkath", 8061, 732), items, 412));
		examples.put("loot (NPC minimal)", new Loot(LootSource.npc("Goblin", null, null), items, null));
		examples.put("loot (PLAYER)", new Loot(LootSource.player(),
			Collections.singletonList(new LootItem(995, "Coins", 1500, 1, 0)), null));
		examples.put("loot (EVENT)", new Loot(LootSource.event("Chambers of Xeric"), items, 25));
		examples.put("loot (CLUE)", new Loot(LootSource.clue("Clue Scroll (Elite)"), items, null));
		examples.put("loot (OTHER)", new Loot(LootSource.other("Herbiboar"), items, null));

		examples.put("kc", Kc.builder().boss("Vorkath").killCount(412).timeSeconds(74.4).personalBest(false)
			.personalBestSeconds(63.0).build());
		examples.put("kc (new personal best)", Kc.builder().boss("Zulrah").killCount(5).timeSeconds(96.4)
			.personalBest(true).personalBestSeconds(96.4).build());
		examples.put("kc (time without PB value)", Kc.builder().boss("Zulrah").killCount(6).timeSeconds(117.0)
			.personalBest(false).build());
		examples.put("kc (minimal)", Kc.builder().boss("Zulrah").killCount(1).build());
		examples.put("kc (raid)", Kc.builder().boss("Tombs of Amascut: Expert Mode").killCount(140)
			.timeSeconds(1500.6).personalBest(true).personalBestSeconds(1500.6).teamSize(3).build());

		RaidSpecialLoot.Item fang = new RaidSpecialLoot.Item(26219, "Osmumten's fang", 1, 24_500_000);
		examples.put("raid.special_loot (SELF)", RaidSpecialLoot.builder().raid("Tombs of Amascut: Expert Mode")
			.item(fang).recipient(RaidSpecialLoot.Recipient.SELF).teamSize(3).killCount(140).build());
		examples.put("raid.special_loot (TEAMMATE minimal)", RaidSpecialLoot.builder().raid("Chambers of Xeric")
			.item(fang).recipient(RaidSpecialLoot.Recipient.TEAMMATE).build());

		examples.put("pb.records", new PbRecords("Fractured Archive", Arrays.asList(
			new PbRecords.Record(PbRecords.OVERALL, 1, 1905.2),
			new PbRecords.Record(PbRecords.OVERALL, 3, 1088.4),
			new PbRecords.Record("The Dagon'hai", 3, 105.0))));

		List<Integer> tabs = Arrays.asList(2, 0, 1, 0, 0, 0, 0, 0, 0);
		List<BankSnapshot.Item> bankItems = Arrays.asList(
			new BankSnapshot.Item(11802, "Armadyl godsword", 1, false, 9_800_000, 750_000),
			new BankSnapshot.Item(20997, "Twisted bow", 0, true, 1_450_000_000, 720_000),
			new BankSnapshot.Item(20594, "Bank filler", 1, false, 0, 0),
			new BankSnapshot.Item(995, "Coins", 2_147_000_000, false, 1, 1));
		List<BankSnapshot.InventoryItem> inventory = Arrays.asList(
			new BankSnapshot.InventoryItem(995, "Coins", 125_000, 1, 1),
			new BankSnapshot.InventoryItem(386, "Shark", 500, 900, 300));
		List<BankSnapshot.WornItem> equipment = Arrays.asList(
			new BankSnapshot.WornItem("WEAPON", 4151, "Abyssal whip", 1, 1_500_000, 72_000),
			new BankSnapshot.WornItem("AMMO", 11212, "Dragon arrow", 3000, 1200, 120));
		examples.put("bank.snapshot", new BankSnapshot(tabs, bankItems, inventory, equipment,
			ContentHash.bank(tabs, bankItems, inventory, equipment)));
		List<Integer> noTabs = Collections.nCopies(BankSnapshot.TAB_COUNT, 0);
		examples.put("bank.snapshot (empty)", new BankSnapshot(noTabs, Collections.emptyList(),
			Collections.emptyList(), Collections.emptyList(), ContentHash.bank(noTabs,
				Collections.emptyList(), Collections.emptyList(), Collections.emptyList())));
		List<BankSnapshot.Item> placeholders = Collections.singletonList(
			new BankSnapshot.Item(20997, "Twisted bow", 0, true, 1_450_000_000, 720_000));
		examples.put("bank.snapshot (only placeholders)", new BankSnapshot(noTabs, placeholders,
			Collections.emptyList(), Collections.emptyList(), ContentHash.bank(noTabs,
				placeholders, Collections.emptyList(), Collections.emptyList())));

		List<PotionStorageSnapshot.Potion> potions = Arrays.asList(
			new PotionStorageSnapshot.Potion(12695, "Super combat potion", 312, 4, 12_400, 0),
			new PotionStorageSnapshot.Potion(99, "Ranarr potion (unf)", 20, 1, 7000, 3),
			new PotionStorageSnapshot.Potion(229, "Vial", 120, 1, 4, 1));
		examples.put("potion_storage.snapshot", new PotionStorageSnapshot(potions,
			ContentHash.potions(potions)));
		examples.put("potion_storage.snapshot (empty)", new PotionStorageSnapshot(Collections.emptyList(),
			ContentHash.potions(Collections.emptyList())));

		examples.put("quests.snapshot", new QuestsSnapshot(4, Arrays.asList(
			new QuestsSnapshot.Entry("COOKS_ASSISTANT", "Cook's Assistant", QuestsSnapshot.State.FINISHED,
				QuestsSnapshot.Kind.QUEST),
			new QuestsSnapshot.Entry("DRAGON_SLAYER_II", "Dragon Slayer II", QuestsSnapshot.State.IN_PROGRESS,
				QuestsSnapshot.Kind.QUEST),
			new QuestsSnapshot.Entry("BEAR_YOUR_SOUL", "Bear Your Soul", QuestsSnapshot.State.NOT_STARTED,
				QuestsSnapshot.Kind.MINIQUEST))));
		examples.put("quests.snapshot (without kind)", new QuestsSnapshot(0, Collections.singletonList(
			new QuestsSnapshot.Entry("X_MARKS_THE_SPOT", "X Marks the Spot", QuestsSnapshot.State.NOT_STARTED,
				null))));
		examples.put("diaries.snapshot", new DiariesSnapshot(Arrays.asList(
			new DiariesSnapshot.Entry("ARDOUGNE", DiariesSnapshot.Tier.HARD, true),
			new DiariesSnapshot.Entry("KOUREND", DiariesSnapshot.Tier.ELITE, false))));
		return examples;
	}

	@Test
	public void everyExampleValidatesAgainstThePinnedSchema()
	{
		Set<EventType> covered = EnumSet.noneOf(EventType.class);
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, EventPayload> example : examples().entrySet())
		{
			EventPayload payload = example.getValue();
			covered.add(payload.type());
			String line = json.toJson(new Event(ids.next(), ACCOUNT, AT, sessionId, payload));
			JsonSchema typeSchema = ContractPackage.schema("schema/events/" + payload.type().wireName() + ".json");
			for (JsonSchema schema : Arrays.asList(eventSchema, typeSchema))
			{
				Set<ValidationMessage> errors = ContractPackage.validate(schema, line);
				if (!errors.isEmpty())
				{
					problems.add(example.getKey() + ": " + errors + "\n  " + line);
				}
			}
		}
		assertTrue(String.join("\n", problems), problems.isEmpty());
		assertEquals("Every event type has an example", EnumSet.allOf(EventType.class), covered);
	}

	@Test
	public void bankItemsHaveExactlyTheContractFields()
	{
		BankSnapshot.Item filler = new BankSnapshot.Item(20594, "Bank filler", 1, false, 0, 0);
		List<Integer> tabs = Collections.nCopies(BankSnapshot.TAB_COUNT, 0);
		String line = json.toJson(new Event(ids.next(), ACCOUNT, AT, sessionId, new BankSnapshot(tabs,
			Collections.singletonList(filler), Collections.emptyList(), Collections.emptyList(), "sha256:x")));
		assertTrue(line, line.contains("\"items\":[{\"itemId\":20594,\"name\":\"Bank filler\",\"quantity\":1,"
			+ "\"placeholder\":false,\"gePrice\":0,\"haPrice\":0}]"));
		assertTrue(line, line.contains("\"tabSizes\":[0,0,0,0,0,0,0,0,0]"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void bankNeedsNineTabSizes()
	{
		new BankSnapshot(Arrays.asList(1, 2), Collections.emptyList(), Collections.emptyList(),
			Collections.emptyList(), "sha256:x");
	}

	@Test
	public void envelopeHasExactlyTheContractFields()
	{
		Event event = new Event(ids.next(), -42L, AT, sessionId, new SessionEnd(SessionEnd.Reason.HOP));
		String line = json.toJson(event);
		assertEquals("{\"eventId\":\"" + event.getEventId() + "\",\"schemaVersion\":1,\"type\":\"session.end\","
			+ "\"accountHash\":\"-42\",\"occurredAt\":\"2026-11-08T19:42:13.512Z\",\"sessionId\":\"" + sessionId
			+ "\",\"payload\":{\"reason\":\"hop\"}}", line);
	}

	@Test
	public void playerLootIsSentWithoutAName()
	{
		JsonObject payload = payloadOf(new Loot(LootSource.player(),
			Collections.singletonList(new LootItem(995, "Coins", 1500, 1, 0)), null));
		assertEquals("{\"type\":\"PLAYER\",\"name\":null}", payload.get("source").toString());
		assertFalse(payload.has("killCount"));
	}

	@Test
	public void requiredNullsAreWrittenAndOptionalNullsLeftOut()
	{
		JsonObject heartbeat = payloadOf(new SessionHeartbeat(1, 2, Collections.emptyList(), null, null, true));
		assertTrue(heartbeat.has("worldLocation") && heartbeat.get("worldLocation").isJsonNull());
		assertTrue(heartbeat.has("worldActivity") && heartbeat.get("worldActivity").isJsonNull());
		assertEquals("[]", heartbeat.get("worldTypes").toString());

		JsonObject kc = payloadOf(Kc.builder().boss("Zulrah").killCount(1).build());
		assertEquals("{\"boss\":\"Zulrah\",\"killCount\":1}", kc.toString());

		JsonObject npc = payloadOf(new Loot(LootSource.npc("Goblin", null, null),
			Collections.singletonList(new LootItem(526, "Bones", 1, 100, 0)), null));
		assertEquals("{\"type\":\"NPC\",\"name\":\"Goblin\"}", npc.get("source").toString());
	}

	@Test
	public void pbRecordsHaveOnlyScopeTeamSizeAndSeconds()
	{
		JsonObject payload = payloadOf(new PbRecords("Fractured Archive",
			Collections.singletonList(new PbRecords.Record(PbRecords.OVERALL, 3, 1088.4))));
		assertEquals("{\"boss\":\"Fractured Archive\",\"records\":[{\"scope\":\"overall\",\"teamSize\":3,"
			+ "\"seconds\":1088.4}]}", payload.toString());
	}

	@Test(expected = IllegalArgumentException.class)
	public void pbRecordsNeedARecord()
	{
		new PbRecords("Fractured Archive", Collections.emptyList());
	}

	@Test
	public void lineHasNoLineBreaksAndNoHtmlEscaping()
	{
		String line = json.toJson(new Event(ids.next(), ACCOUNT, AT, sessionId,
			new SessionStart("A\nB <o'b>", AccountType.NORMAL, 301, "0.1.0")));
		assertFalse(line.contains("\n"));
		assertTrue(line, line.contains("\"A\\nB <o'b>\""));
	}

	@Test
	public void afkTimesUseTheContractFormat()
	{
		JsonObject afk = payloadOf(new SessionAfk(Instant.parse("2026-11-08T19:10:00Z"),
			Instant.parse("2026-11-08T19:24:30.123456Z")));
		assertEquals("2026-11-08T19:10:00.000Z", afk.get("startedAt").getAsString());
		assertEquals("2026-11-08T19:24:30.123Z", afk.get("endedAt").getAsString());
	}

	@Test(expected = IllegalArgumentException.class)
	public void afkCannotEndBeforeItStarts()
	{
		new SessionAfk(AT, AT.minusMillis(1));
	}

	@Test(expected = IllegalArgumentException.class)
	public void noEventsWithoutAnAccount()
	{
		new Event(ids.next(), Event.NO_ACCOUNT, AT, sessionId, new SessionEnd(SessionEnd.Reason.LOGOUT));
	}

	@Test(expected = IllegalArgumentException.class)
	public void lootNeedsItems()
	{
		new Loot(LootSource.other("Nothing"), Collections.emptyList(), null);
	}

	private JsonObject payloadOf(EventPayload payload)
	{
		String line = json.toJson(new Event(ids.next(), ACCOUNT, AT, sessionId, payload));
		return new JsonParser().parse(line).getAsJsonObject().getAsJsonObject("payload");
	}
}
