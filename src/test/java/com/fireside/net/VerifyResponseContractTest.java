package com.fireside.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.Gson;
import com.networknt.schema.JsonSchema;
import com.fireside.contract.ContractPackage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

/** The plugin's parser agrees with the pinned schema on the verify fixtures. */
public class VerifyResponseContractTest
{
	private static final String FIXTURES = "fixtures/api/ingest-verify-response";
	private static final Gson GSON = new Gson();
	private static final JsonSchema SCHEMA = ContractPackage.schema("schema/api/ingest-verify-response.json");

	@Test
	public void validFixturesAreParsed()
	{
		List<Path> files = ContractPackage.fixtures(FIXTURES + "/valid");
		assertFalse(files.isEmpty());
		for (Path file : files)
		{
			String json = ContractPackage.readString(file);
			assertTrue(file + " fails the schema", ContractPackage.validate(SCHEMA, json).isEmpty());

			VerifyResponse parsed = VerifyResponse.parse(GSON, json);
			assertNotNull(file + " was not parsed", parsed);
			JsonNode expected = ContractPackage.readJson(file);
			assertEquals(expected.get("keyName").asText(), parsed.getKeyName());
			assertEquals(expected.get("serverVersion").asText(), parsed.getServerVersion());
			assertEquals(SemVer.parse(expected.get("contractVersion").asText()), parsed.getContractVersion());
			assertEquals(Instant.parse(expected.get("serverTime").asText()), parsed.getServerTime());
		}
	}

	@Test
	public void invalidFixturesAreRejected()
	{
		List<Path> files = ContractPackage.fixtures(FIXTURES + "/invalid");
		assertFalse(files.isEmpty());
		for (Path file : files)
		{
			String json = ContractPackage.readString(file);
			assertFalse(file + " passes the schema", ContractPackage.validate(SCHEMA, json).isEmpty());
			assertNull(file + " was parsed", VerifyResponse.parse(GSON, json));
		}
	}

	@Test
	public void extraFieldsAreIgnored()
	{
		String json = "{\"ok\":true,\"keyName\":\"k\",\"serverVersion\":\"0.2.0\",\"contractVersion\":\"1.14.0\","
			+ "\"serverTime\":\"2026-11-13T18:04:12.512Z\",\"somethingNew\":1}";
		assertNotNull(VerifyResponse.parse(GSON, json));
	}

	@Test
	public void nonObjectBodiesAreRejected()
	{
		assertNull(VerifyResponse.parse(GSON, null));
		assertNull(VerifyResponse.parse(GSON, ""));
		assertNull(VerifyResponse.parse(GSON, "[]"));
		assertNull(VerifyResponse.parse(GSON, "\"ok\""));
		assertNull(VerifyResponse.parse(GSON, "<html>hello</html>"));
		assertNull(VerifyResponse.parse(GSON, "{\"ok\":true,\"keyName\":{}}"));
	}
}
