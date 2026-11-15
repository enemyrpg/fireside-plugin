package com.fireside.quest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fireside.contract.ContractPackage;
import com.fireside.event.DiariesSnapshot;
import com.fireside.event.Event;
import com.fireside.event.EventJson;
import com.fireside.event.EventPayload;
import com.fireside.event.QuestsSnapshot;
import com.fireside.event.QuestsSnapshot.State;
import com.fireside.event.UuidV7Generator;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

/**
 * Snapshots of RuneLite's whole {@code Quest} enum and of every diary tier
 * validate against the pinned schemas and keep the rules JSON Schema cannot
 * express (the {@code invalid-semantic/} fixtures): each quest once, each
 * region and tier once.
 */
public class QuestSnapshotContractTest
{
	private static final Instant AT = Instant.parse("2026-11-15T18:00:00.000Z");
	/** Regions the server knows by name ({@code DIARY_REGIONS}). */
	private static final Set<String> REGIONS = new HashSet<>(Arrays.asList("ARDOUGNE", "DESERT", "FALADOR",
		"FREMENNIK", "KANDARIN", "KARAMJA", "KOUREND", "LUMBRIDGE", "MORYTANIA", "VARROCK", "WESTERN",
		"WILDERNESS"));

	private final EventJson json = new EventJson(RuneLiteAPI.GSON);
	private final UuidV7Generator ids = new UuidV7Generator();
	private final String sessionId = ids.next();

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

	/** The enum as {@link ClientQuestView} reads it, with every state and both kinds. */
	private static List<QuestView.RawQuest> wholeEnum(boolean withTypes)
	{
		List<QuestView.RawQuest> raw = new ArrayList<>();
		for (Quest quest : Quest.values())
		{
			State state = State.values()[quest.ordinal() % State.values().length];
			Integer type = !withTypes ? null : quest == Quest.COOKS_ASSISTANT ? Integer.valueOf(0)
				: quest == Quest.ALFRED_GRIMHANDS_BARCRAWL ? Integer.valueOf(1) : Integer.valueOf(quest.ordinal() % 3);
			raw.add(new QuestView.RawQuest(quest.name(), quest.getName(), state, type));
		}
		return raw;
	}

	@Test
	public void theWholeQuestEnumValidates()
	{
		QuestsSnapshot snapshot = QuestSnapshots.quests(284, wholeEnum(true));
		assertEquals(Quest.values().length, snapshot.getQuests().size());
		assertTrue(snapshot.getQuests().size() <= 500);
		Set<String> seen = new HashSet<>();
		boolean quest = false;
		boolean miniquest = false;
		boolean none = false;
		for (QuestsSnapshot.Entry entry : snapshot.getQuests())
		{
			assertTrue("quest twice: " + entry.getQuest(), seen.add(entry.getQuest()));
			quest |= entry.getKind() == QuestsSnapshot.Kind.QUEST;
			miniquest |= entry.getKind() == QuestsSnapshot.Kind.MINIQUEST;
			none |= entry.getKind() == null;
		}
		assertTrue(quest && miniquest && none);
		String line = validate(snapshot);
		assertFalse(line.contains("contentHash"));
	}

	@Test
	public void questsWithoutKindValidate()
	{
		QuestsSnapshot snapshot = QuestSnapshots.quests(0, wholeEnum(false));
		String line = validate(snapshot);
		assertFalse(line.contains("\"kind\""));
	}

	@Test
	public void everyDiaryTierValidatesWithKnownRegionsEachOnce()
	{
		for (int value : new int[]{0, 1, 2})
		{
			DiariesSnapshot snapshot = QuestSnapshots.diaries(id -> value);
			assertEquals(48, snapshot.getDiaries().size());
			Set<String> seen = new HashSet<>();
			for (DiariesSnapshot.Entry entry : snapshot.getDiaries())
			{
				assertTrue(entry.getRegion(), REGIONS.contains(entry.getRegion()));
				assertTrue("tier twice", seen.add(entry.getRegion() + "/" + entry.getTier()));
			}
			validate(snapshot);
		}
	}
}
