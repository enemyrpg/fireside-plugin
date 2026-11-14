package com.fireside.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SemVerTest
{
	private static SemVer v(String text)
	{
		SemVer v = SemVer.parse(text);
		if (v == null)
		{
			throw new AssertionError("Not parsed: " + text);
		}
		return v;
	}

	@Test
	public void parses()
	{
		assertEquals("1.13.0", v("1.13.0").toString());
		assertEquals(2, v("2.0.1").getMajor());
		assertEquals(v("1.2.3"), v("1.2.3-beta.1"));
		assertEquals(v("1.2.3"), v("1.2.3+build.5"));
	}

	@Test
	public void rejectsInvalid()
	{
		assertNull(SemVer.parse(null));
		assertNull(SemVer.parse(""));
		assertNull(SemVer.parse("1.2"));
		assertNull(SemVer.parse("v1.2.3"));
		assertNull(SemVer.parse("1.2.3.4"));
		assertNull(SemVer.parse(" 1.2.3"));
		assertNull(SemVer.parse("99999999999.0.0"));
	}

	@Test
	public void comparesNumerically()
	{
		assertTrue(v("1.9.0").compareTo(v("1.13.0")) < 0);
		assertTrue(v("1.13.1").compareTo(v("1.13.0")) > 0);
		assertTrue(v("2.0.0").compareTo(v("1.99.99")) > 0);
		assertEquals(0, v("1.13.0").compareTo(v("1.13.0")));
	}
}
