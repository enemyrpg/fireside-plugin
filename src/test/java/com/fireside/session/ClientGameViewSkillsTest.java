package com.fireside.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fireside.contract.ContractPackage;
import com.fireside.event.XpSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;
import org.junit.Test;

public class ClientGameViewSkillsTest
{
	/** The {@code required} skill list of the pinned contract, in contract order. */
	private static List<String> contractSkills()
	{
		JsonNode schema = ContractPackage.readJson(
			ContractPackage.root().resolve("schema/events/xp.snapshot.json"));
		List<String> skills = new ArrayList<>();
		schema.at("/properties/payload/properties/skills/required").forEach(n -> skills.add(n.asText()));
		return skills;
	}

	@Test
	public void everyContractSkillIsRead()
	{
		Map<String, XpSnapshot.SkillXp> skills = ClientGameView.skills(s -> 1000 + s.ordinal(), s -> 10);
		assertNotNull(skills);
		List<String> required = contractSkills();
		assertEquals(24, required.size());
		assertEquals("Same skills, in Skill order", required, new ArrayList<>(skills.keySet()));
		assertEquals(new XpSnapshot.SkillXp(1000 + Skill.SAILING.ordinal(), 10), skills.get("SAILING"));
	}

	@Test
	public void notLoadedWhileAnyLevelIsZero()
	{
		assertNull(ClientGameView.skills(s -> 0, s -> 0));
		assertNull(ClientGameView.skills(s -> 0, s -> s == Skill.SAILING ? 0 : 1));
	}
}
