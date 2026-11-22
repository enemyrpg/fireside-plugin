package com.fireside.send;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SenderSettingsTest
{
	@Test
	public void toStringNeverContainsTheKey()
	{
		String s = new SenderSettings(true, "http://127.0.0.1:3100", "fs_secret_value").toString();
		assertFalse(s, s.contains("fs_secret_value"));
	}

	@Test
	public void configuredNeedsAValidUrlAndKey()
	{
		assertTrue(new SenderSettings(true, " http://127.0.0.1:3100 ", " fs_key ").isConfigured());
		assertFalse(new SenderSettings(true, "", "fs_key").isConfigured());
		assertFalse(new SenderSettings(true, "not a url", "fs_key").isConfigured());
		assertFalse(new SenderSettings(true, "http://127.0.0.1:3100", "").isConfigured());
		assertFalse(new SenderSettings(true, "http://127.0.0.1:3100", "ost key").isConfigured());
		assertFalse(new SenderSettings(true, null, null).isConfigured());
	}

	@Test
	public void sameConnectionIgnoresTheOnOffSwitchAndWhitespace()
	{
		SenderSettings a = new SenderSettings(true, "http://127.0.0.1:3100", "fs_key");
		assertTrue(a.sameConnection(new SenderSettings(false, "http://127.0.0.1:3100 ", "fs_key")));
		assertFalse(a.sameConnection(new SenderSettings(true, "http://127.0.0.1:3001", "fs_key")));
		assertFalse(a.sameConnection(new SenderSettings(true, "http://127.0.0.1:3100", "fs_key2")));
	}
}
