package com.fireside.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fireside.contract.ContractPackage;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.runelite.http.api.RuneLiteAPI;
import com.fireside.icons.PngEncoder;
import org.junit.Test;

/** Icon request bodies against the pinned schemas, and parsing of the response fixtures. */
public class IconContractTest
{
	private static BufferedImage image()
	{
		BufferedImage image = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
		for (int y = 2; y < 30; y++)
		{
			for (int x = 2; x < 34; x++)
			{
				image.setRGB(x, y, 0xff000000 | x << 16 | y << 8 | 0x40);
			}
		}
		return image;
	}

	private static int size(JsonNode node, String field)
	{
		return node.has(field) ? node.get(field).size() : 0;
	}

	private static void assertValid(String schema, String json)
	{
		Set<ValidationMessage> errors = ContractPackage.validate(ContractPackage.schema(schema), json);
		assertTrue(schema + ": " + errors + "\n" + json, errors.isEmpty());
	}

	@Test
	public void wantedRequestMatchesTheSchema()
	{
		List<Integer> ids = new ArrayList<>();
		for (int i = 0; i < IconClient.MAX_WANTED_IDS; i++)
		{
			ids.add(i * 3);
		}
		assertValid("schema/api/ingest-icons-wanted-request.json", IconClient.wantedBody(ids));
		assertValid("schema/api/ingest-icons-wanted-request.json", IconClient.wantedBody(Arrays.asList(4152, 995)));
	}

	@Test
	public void uploadRequestAndEveryIconMatchTheSchemas()
	{
		byte[] png = PngEncoder.encode(image());
		List<IconClient.Icon> icons = Arrays.asList(new IconClient.Icon(4151, png), new IconClient.Icon(4152, png),
			new IconClient.Icon(995, png));
		String body = IconClient.uploadBody(icons);
		assertValid("schema/api/ingest-icons-request.json", body);
		JsonSchema iconSchema = ContractPackage.schema("schema/api/ingest-icon.json");
		JsonNode parsed = ContractPackage.readJsonString(body);
		for (JsonNode icon : parsed.get("icons"))
		{
			Set<ValidationMessage> errors = ContractPackage.validate(iconSchema, icon.toString());
			assertTrue(errors.toString(), errors.isEmpty());
		}
		// The ID is sent as given (noted 4152 is not turned into 4151).
		assertEquals(4152, parsed.get("icons").get(1).get("itemId").asInt());
	}

	@Test
	public void jsonSizeIsAnUpperBoundOfTheIconInTheBody()
	{
		byte[] png = PngEncoder.encode(image());
		String longName = String.join("", Collections.nCopies(IconKey.MAX_NAME_LENGTH, "\u0001"));
		for (IconClient.Icon icon : Arrays.asList(new IconClient.Icon(1_234_567, png),
			new IconClient.Icon(IconKey.skill("RUNECRAFT"), png), new IconClient.Icon(IconKey.boss("Kree'Arra"), png),
			new IconClient.Icon(IconKey.boss(longName), png)))
		{
			String body = IconClient.uploadBody(Collections.singletonList(icon));
			assertTrue(icon.getKey().toString(), body.length() - "{\"icons\":[]}".length() <= icon.jsonSize());
		}
	}

	@Test
	public void anItemOnlyWantedRequestIsTheSameAsBeforeNamedIcons()
	{
		assertEquals("{\"itemIds\":[4152,995]}", IconClient.wantedBody(Arrays.asList(4152, 995)));
		assertEquals("{\"itemIds\":[4152,995]}",
			IconClient.wantedBodyFor(Arrays.asList(IconKey.item(4152), IconKey.item(995))));
		byte[] png = PngEncoder.encode(image());
		String upload = IconClient.uploadBody(Collections.singletonList(new IconClient.Icon(4152, png)));
		assertTrue(upload, upload.startsWith("{\"icons\":[{\"itemId\":4152,\"png\":\"iVBORw0KGgo"));
		assertFalse(upload.contains("kind"));
	}

	@Test
	public void wantedRequestWithSkillsAndBossesMatchesTheSchema()
	{
		List<IconKey> keys = Arrays.asList(IconKey.item(4151), IconKey.skill("OVERALL"), IconKey.skill("ATTACK"),
			IconKey.boss("Zulrah"), IconKey.boss("Kree'Arra"), IconKey.boss("Theatre of Blood: Hard Mode"));
		String body = IconClient.wantedBodyFor(keys);
		assertValid("schema/api/ingest-icons-wanted-request.json", body);
		JsonNode parsed = ContractPackage.readJsonString(body);
		assertEquals(1, parsed.get("itemIds").size());
		assertEquals("OVERALL", parsed.get("skills").get(0).asText());
		assertEquals("Kree'Arra", parsed.get("bosses").get(1).asText());

		// A list without elements is left out: the server rejects an empty one.
		String named = IconClient.wantedBodyFor(Arrays.asList(IconKey.skill("ATTACK")));
		assertValid("schema/api/ingest-icons-wanted-request.json", named);
		assertEquals("{\"skills\":[\"ATTACK\"]}", named);
	}

	@Test
	public void uploadRequestWithSkillAndBossIconsMatchesTheSchemas()
	{
		byte[] png = PngEncoder.encode(image());
		String body = IconClient.uploadBody(Arrays.asList(new IconClient.Icon(4151, png),
			new IconClient.Icon(IconKey.skill("OVERALL"), png), new IconClient.Icon(IconKey.boss("Kree'Arra"), png)));
		assertValid("schema/api/ingest-icons-request.json", body);
		JsonSchema iconSchema = ContractPackage.schema("schema/api/ingest-icon.json");
		JsonNode icons = ContractPackage.readJsonString(body).get("icons");
		for (JsonNode icon : icons)
		{
			Set<ValidationMessage> errors = ContractPackage.validate(iconSchema, icon.toString());
			assertTrue(errors.toString(), errors.isEmpty());
		}
		assertEquals("SKILL", icons.get(1).get("kind").asText());
		assertEquals("OVERALL", icons.get(1).get("key").asText());
		assertFalse(icons.get(1).has("itemId"));
		assertEquals("BOSS", icons.get(2).get("kind").asText());
		assertEquals("Kree'Arra", icons.get(2).get("key").asText());
	}

	@Test
	public void keysTheSchemaWouldRejectAreRecognized()
	{
		assertTrue(IconKey.isSkillName("ATTACK"));
		assertTrue(IconKey.isSkillName("TWO_WORDS2"));
		assertFalse(IconKey.isSkillName("Attack"));
		assertFalse(IconKey.isSkillName(""));
		assertFalse(IconKey.isSkillName("_X"));
		assertFalse(IconKey.isSkillName(null));
		assertTrue(IconKey.isBossName("Kree'Arra"));
		assertTrue(IconKey.isBossName("X"));
		assertFalse(IconKey.isBossName(" Zulrah"));
		assertFalse(IconKey.isBossName("Zulrah "));
		assertFalse(IconKey.isBossName(""));
		assertFalse(IconKey.isBossName(String.join("", Collections.nCopies(101, "x"))));
	}

	@Test
	public void namedKeysInTheAnswersAreParsed()
	{
		IconResult wanted = IconResult.fromWanted(200, null, ContractPackage.readString(
			ContractPackage.root().resolve("fixtures/api/ingest-icons-wanted-response/valid/with-skills-and-bosses.json")),
			RuneLiteAPI.GSON);
		assertEquals(Arrays.asList(IconKey.skill("SAILING"), IconKey.boss("Kree'Arra"),
			IconKey.boss("Obor chests opened")), wanted.getMissing());

		IconResult upload = IconResult.fromUpload(200, null, ContractPackage.readString(
			ContractPackage.root().resolve("fixtures/api/ingest-icons-response/valid/with-named.json")), RuneLiteAPI.GSON);
		assertEquals(Arrays.asList(IconKey.item(4151), IconKey.skill("ATTACK"), IconKey.boss("Zulrah")),
			upload.getAccepted());
		// One rejection names its key, the other could not be read by the server.
		assertEquals(Collections.singletonList(IconKey.boss("Zulrah")), upload.getRejected());
		assertEquals(Arrays.asList(3, 4), upload.getRejectedIndexes());
	}

	@Test
	public void wantedResponseFixturesParse()
	{
		for (Path file : ContractPackage.fixtures("fixtures/api/ingest-icons-wanted-response/valid"))
		{
			String json = ContractPackage.readString(file);
			IconResult result = IconResult.fromWanted(200, null, json, RuneLiteAPI.GSON);
			assertEquals(file.toString(), IconResult.Kind.OK, result.getKind());
			JsonNode expected = ContractPackage.readJson(file);
			assertEquals(size(expected, "missing") + size(expected, "missingSkills") + size(expected, "missingBosses"),
				result.getMissing().size());
		}
	}

	@Test
	public void uploadResponseFixturesParse()
	{
		for (Path file : ContractPackage.fixtures("fixtures/api/ingest-icons-response/valid"))
		{
			JsonNode expected = ContractPackage.readJson(file);
			IconResult result = IconResult.fromUpload(200, null, ContractPackage.readString(file), RuneLiteAPI.GSON);
			assertEquals(file.toString(), IconResult.Kind.OK, result.getKind());
			assertEquals(size(expected, "stored") + size(expected, "unchanged") + size(expected, "storedNamed")
				+ size(expected, "unchangedNamed"), result.getAccepted().size());
			assertEquals(expected.get("rejected").size(), result.getRejectedIndexes().size());
		}
	}

	@Test
	public void statusCodes()
	{
		assertEquals(IconResult.Kind.NOT_FOUND, IconResult.fromWanted(404, null, null, RuneLiteAPI.GSON).getKind());
		assertEquals(IconResult.Kind.UNAUTHORIZED, IconResult.fromUpload(401, null, null, RuneLiteAPI.GSON).getKind());
		IconResult limited = IconResult.fromUpload(429, "7", null, RuneLiteAPI.GSON);
		assertEquals(IconResult.Kind.RATE_LIMITED, limited.getKind());
		assertEquals(7, limited.getRetryAfter().getSeconds());
		assertEquals(IconResult.Kind.FAILED, IconResult.fromWanted(503, null, null, RuneLiteAPI.GSON).getKind());
		assertEquals(IconResult.Kind.FAILED, IconResult.fromWanted(200, null, "{\"nope\":1}", RuneLiteAPI.GSON).getKind());
		assertFalse(IconResult.fromWanted(200, null, "not json", RuneLiteAPI.GSON).getKind() == IconResult.Kind.OK);
	}
}
