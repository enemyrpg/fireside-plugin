package com.fireside.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.fireside.contract.ContractPackage;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Duration;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

public class IngestResultTest
{
	private final Gson gson = RuneLiteAPI.GSON;

	private IngestResult of(int code)
	{
		return IngestResult.fromResponse(code, null, null, null, gson);
	}

	@Test
	public void classifiesEveryStatus()
	{
		assertEquals(IngestResult.Kind.BAD_REQUEST, of(400).getKind());
		assertEquals(IngestResult.Kind.UNAUTHORIZED, of(401).getKind());
		assertEquals(IngestResult.Kind.NOT_FOUND, of(404).getKind());
		assertEquals(IngestResult.Kind.REDIRECT, of(301).getKind());
		assertEquals(IngestResult.Kind.REDIRECT, of(308).getKind());
		assertEquals(IngestResult.Kind.TOO_LARGE, of(413).getKind());
		assertEquals(IngestResult.Kind.TEMPORARY, of(429).getKind());
		assertEquals(IngestResult.Kind.TEMPORARY, of(500).getKind());
		assertEquals(IngestResult.Kind.TEMPORARY, of(503).getKind());
		assertEquals(IngestResult.Kind.TEMPORARY, of(403).getKind());
		// 200 without a valid body is not a confirmation.
		assertEquals(IngestResult.Kind.TEMPORARY, of(200).getKind());
		assertEquals(IngestResult.Kind.TEMPORARY,
			IngestResult.fromResponse(200, null, null, "{\"accepted\":[]}", gson).getKind());
	}

	@Test
	public void retryAfterIsReadFor429And5xx()
	{
		assertEquals(Duration.ofSeconds(17),
			IngestResult.fromResponse(429, "17", null, null, gson).getRetryAfter());
		assertEquals(Duration.ofSeconds(5),
			IngestResult.fromResponse(503, "5", null, null, gson).getRetryAfter());
		assertNull(IngestResult.fromResponse(429, "Wed, 21 Oct 2015 07:28:00 GMT", null, null, gson)
			.getRetryAfter());
		assertNull(IngestResult.fromResponse(429, null, null, null, gson).getRetryAfter());
	}

	@Test
	public void badRequestKeepsTheServerMessage()
	{
		IngestResult r = IngestResult.fromResponse(400, null, null,
			"{\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"Invalid body\","
				+ "\"details\":[{\"path\":\"events\",\"message\":\"Too big\"},{\"path\":\"\",\"message\":\"x\"}]}}",
			gson);
		assertEquals("Invalid body; events: Too big; x", r.getMessage());
		assertEquals("Bad request (HTTP 400)", of(400).getMessage());
		assertEquals("Bad request (HTTP 400)",
			IngestResult.fromResponse(400, null, null, "<html>", gson).getMessage());
	}

	@Test
	public void networkErrorsAreTemporary()
	{
		IngestResult r = IngestResult.fromException(new ConnectException("refused"),
			ServerUrl.parse("http://127.0.0.1:3000"));
		assertEquals(IngestResult.Kind.TEMPORARY, r.getKind());
		assertNull(r.getHttpStatus());
		assertTrue(r.getMessage(), r.getMessage().contains("connection refused"));
		assertEquals(IngestResult.Kind.TEMPORARY, IngestResult.fromException(new IOException(),
			ServerUrl.parse("http://127.0.0.1:3000")).getKind());
	}

	@Test
	public void contractResponseFixturesParse()
	{
		for (Path fixture : ContractPackage.fixtures("fixtures/api/ingest-response/valid"))
		{
			IngestResult r = IngestResult.fromResponse(200, null, null, ContractPackage.readString(fixture), gson);
			assertEquals(fixture.toString(), IngestResult.Kind.OK, r.getKind());
			assertNotNull(r.getResponse());
		}
		IngestResponse mixed = IngestResponse.parse(gson, ContractPackage.readString(
			ContractPackage.root().resolve("fixtures/api/ingest-response/valid/mixed.json")));
		assertEquals(1, mixed.getAccepted().size());
		assertEquals(1, mixed.getDuplicates().size());
		assertEquals(2, mixed.getRejected().size());
		assertNull(mixed.getRejected().get(1).getEventId());
		assertEquals(3, mixed.getRejected().get(1).getIndex());
	}

	@Test
	public void invalidResponsesAreRejected()
	{
		for (String body : new String[]{"{}", "[]", "null", "not json",
			"{\"accepted\":[null],\"duplicates\":[],\"rejected\":[]}",
			"{\"accepted\":[],\"duplicates\":[],\"rejected\":[{\"eventId\":null,\"error\":\"x\"}]}",
			"{\"accepted\":[],\"duplicates\":[],\"rejected\":[{\"index\":-1,\"error\":\"x\"}]}"})
		{
			assertNull(body, IngestResponse.parse(gson, body));
		}
	}
}
