package com.fireside.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Access to the pinned contract package in {@code src/test/resources/contract/}
 * and JSON Schema (draft 2020-12) validation against it.
 */
public final class ContractPackage
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
	private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder()
		.formatAssertionsEnabled(true)
		.build();

	private ContractPackage()
	{
	}

	public static Path root()
	{
		URL manifest = ContractPackage.class.getResource("/contract/manifest.json");
		if (manifest == null)
		{
			throw new IllegalStateException("Pinned contract not found; run ./scripts/update-contract.sh <version>");
		}
		try
		{
			return Paths.get(manifest.toURI()).getParent();
		}
		catch (URISyntaxException e)
		{
			throw new IllegalStateException(e);
		}
	}

	public static JsonNode readJson(Path file)
	{
		try
		{
			return MAPPER.readTree(file.toFile());
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	public static String readString(Path file)
	{
		try
		{
			return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/** Loads a schema by its path in the package, e.g. {@code schema/event.json}. */
	public static JsonSchema schema(String relativePath)
	{
		try (InputStream in = Files.newInputStream(root().resolve(relativePath)))
		{
			return FACTORY.getSchema(in, CONFIG);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	public static Set<ValidationMessage> validate(JsonSchema schema, String json)
	{
		try
		{
			return schema.validate(MAPPER.readTree(json));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/** JSON files in a fixture folder, e.g. {@code fixtures/api/ingest-verify-response/valid}, sorted. */
	public static List<Path> fixtures(String relativeDir)
	{
		Path dir = root().resolve(relativeDir);
		if (!Files.isDirectory(dir))
		{
			return Collections.emptyList();
		}
		try (Stream<Path> files = Files.list(dir))
		{
			return files.filter(p -> p.toString().endsWith(".json")).sorted().collect(Collectors.toList());
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/** Sub-folders of a folder, sorted by name. */
	public static List<Path> subdirectories(String relativeDir)
	{
		try (Stream<Path> files = Files.list(root().resolve(relativeDir)))
		{
			return files.filter(Files::isDirectory).sorted().collect(Collectors.toList());
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}
}
