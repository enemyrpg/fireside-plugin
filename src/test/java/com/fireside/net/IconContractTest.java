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
		IconClient.Icon icon = new IconClient.Icon(1_234_567, png);
		String body = IconClient.uploadBody(Collections.singletonList(icon));
		assertTrue(body.length() - "{\"icons\":[]}".length() <= icon.jsonSize());
	}

	@Test
	public void wantedResponseFixturesParse()
	{
		for (Path file : ContractPackage.fixtures("fixtures/api/ingest-icons-wanted-response/valid"))
		{
			String json = ContractPackage.readString(file);
			IconResult result = IconResult.fromWanted(200, null, json, RuneLiteAPI.GSON);
			assertEquals(file.toString(), IconResult.Kind.OK, result.getKind());
			assertEquals(ContractPackage.readJson(file).get("missing").size(), result.getMissing().size());
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
			assertEquals(expected.get("stored").size() + expected.get("unchanged").size(), result.getAccepted().size());
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
