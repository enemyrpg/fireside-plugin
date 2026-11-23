package com.fireside.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ServerUrlTest
{
	private static ServerUrl url(String raw)
	{
		ServerUrl url = ServerUrl.parse(raw);
		if (url == null)
		{
			throw new AssertionError("Not parsed: " + raw);
		}
		return url;
	}

	@Test
	public void buildsVerifyUrl()
	{
		assertEquals("http://127.0.0.1:3100/api/v1/ingest/verify", url("http://127.0.0.1:3100").verifyUrl().toString());
		assertEquals("http://127.0.0.1:3100/api/v1/ingest/verify", url("http://127.0.0.1:3100/").verifyUrl().toString());
		assertEquals("http://127.0.0.1:3100/api/v1/ingest/verify", url("  http://127.0.0.1:3100//  ").verifyUrl().toString());
	}

	@Test
	public void keepsPathPrefix()
	{
		ServerUrl u = url("https://example.com/tracker");
		assertEquals("https://example.com/tracker/api/v1/ingest/verify", u.verifyUrl().toString());
		assertEquals("https://example.com/tracker/", u.dashboardUrl().toString());
		assertEquals("https://example.com/tracker/api/v1/ingest/verify", url("https://example.com/tracker/").verifyUrl().toString());
	}

	@Test
	public void dashboardIsBaseUrl()
	{
		assertEquals("http://localhost:3100/", url("http://localhost:3100").dashboardUrl().toString());
	}

	@Test
	public void rejectsInvalidUrls()
	{
		assertNull(ServerUrl.parse(null));
		assertNull(ServerUrl.parse(""));
		assertNull(ServerUrl.parse("   "));
		assertNull(ServerUrl.parse("127.0.0.1:3100"));
		assertNull(ServerUrl.parse("ftp://example.com"));
		assertNull(ServerUrl.parse("http://"));
		assertNull(ServerUrl.parse("not a url"));
		assertNull(ServerUrl.parse("http://user:pass@example.com"));
		assertNull(ServerUrl.parse("http://example.com/?a=b"));
		assertNull(ServerUrl.parse("http://example.com/#x"));
	}

	@Test
	public void loopbackHttpIsNotInsecure()
	{
		assertFalse(url("http://localhost:3100").isInsecure());
		assertFalse(url("http://LOCALHOST:3100").isInsecure());
		assertFalse(url("http://tracker.localhost").isInsecure());
		assertFalse(url("http://127.0.0.1:3100").isInsecure());
		assertFalse(url("http://127.0.0.5").isInsecure());
		assertFalse(url("http://[::1]:3100").isInsecure());
	}

	@Test
	public void remoteHttpIsInsecure()
	{
		assertTrue(url("http://192.168.1.2:3100").isInsecure());
		assertTrue(url("http://10.0.0.5").isInsecure());
		assertTrue(url("http://tracker.example.com").isInsecure());
		assertTrue(url("http://localhost.example.com").isInsecure());
		assertTrue(url("http://[fe80::1]").isInsecure());
	}

	@Test
	public void httpsIsNeverInsecure()
	{
		assertFalse(url("https://tracker.example.com").isInsecure());
		assertFalse(url("https://192.168.1.2:3100").isInsecure());
	}
}
