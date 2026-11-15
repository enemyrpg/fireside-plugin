package com.fireside.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.fireside.Contract;
import com.fireside.net.SemVer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Checks the pinned contract package and that the JSON Schema validator
 * classifies every fixture as the package says (valid / invalid /
 * invalid-semantic). This proves the validator handles the schemas before
 * 7.3 validates the plugin's own events with it.
 */
public class PinnedContractTest
{
	private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

	@Test
	public void manifestMatchesPinnedVersion()
	{
		JsonNode manifest = ContractPackage.readJson(ContractPackage.root().resolve("manifest.json"));
		assertEquals("Pinned contract and Contract.PINNED_VERSION differ",
			Contract.PINNED_VERSION, manifest.get("contractVersion").asText());
		assertEquals(DIALECT, manifest.get("jsonSchemaDialect").asText());
	}

	@Test
	public void requiredVersionsAreNotNewerThanThePinnedContract()
	{
		SemVer pinned = SemVer.parse(Contract.PINNED_VERSION);
		assertTrue("REQUIRED_VERSION is newer than the pinned contract",
			SemVer.parse(Contract.REQUIRED_VERSION).compareTo(pinned) <= 0);
		assertTrue("ICONS_VERSION is newer than the pinned contract",
			SemVer.parse(Contract.ICONS_VERSION).compareTo(pinned) <= 0);
		// Icons must never be a reason to hold events: an older server still gets them.
		assertTrue(SemVer.parse(Contract.REQUIRED_VERSION).compareTo(SemVer.parse(Contract.ICONS_VERSION)) <= 0);
	}

	@Test
	public void everySchemaInManifestExists()
	{
		JsonNode manifest = ContractPackage.readJson(ContractPackage.root().resolve("manifest.json"));
		assertTrue(manifest.get("schemas").size() > 0);
		for (JsonNode schema : manifest.get("schemas"))
		{
			assertTrue("Missing " + schema.asText(), Files.isRegularFile(ContractPackage.root().resolve(schema.asText())));
		}
	}

	@Test
	public void eventFixturesValidateAsExpected()
	{
		List<String> failures = new ArrayList<>();
		checkFixtures(ContractPackage.schema("schema/event.json"), "fixtures/events", failures);
		assertTrue(String.join("\n", failures), failures.isEmpty());
	}

	@Test
	public void apiFixturesValidateAsExpected()
	{
		List<String> failures = new ArrayList<>();
		List<Path> apis = ContractPackage.subdirectories("fixtures/api");
		assertFalse(apis.isEmpty());
		for (Path api : apis)
		{
			String name = api.getFileName().toString();
			checkFixtures(ContractPackage.schema("schema/api/" + name + ".json"), "fixtures/api/" + name, failures);
		}
		assertTrue(String.join("\n", failures), failures.isEmpty());
	}

	private static void checkFixtures(JsonSchema schema, String dir, List<String> failures)
	{
		List<Path> valid = ContractPackage.fixtures(dir + "/valid");
		assertFalse("No valid fixtures in " + dir, valid.isEmpty());
		for (Path file : valid)
		{
			expect(schema, file, true, failures);
		}
		for (Path file : ContractPackage.fixtures(dir + "/invalid"))
		{
			expect(schema, file, false, failures);
		}
		// Rejected by the server only; JSON Schema cannot express these rules.
		for (Path file : ContractPackage.fixtures(dir + "/invalid-semantic"))
		{
			expect(schema, file, true, failures);
		}
	}

	private static void expect(JsonSchema schema, Path file, boolean shouldPass, List<String> failures)
	{
		boolean passed = ContractPackage.validate(schema, ContractPackage.readString(file)).isEmpty();
		if (passed != shouldPass)
		{
			failures.add(ContractPackage.root().relativize(file) + (shouldPass ? " was rejected" : " was accepted"));
		}
	}
}
