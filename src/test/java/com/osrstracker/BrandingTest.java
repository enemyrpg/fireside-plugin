package com.osrstracker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.regex.Pattern;
import org.junit.Test;

public class BrandingTest
{
	private static final Pattern KEBAB_CASE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
	private static final Pattern SAFE_SEGMENT = Pattern.compile("[a-z0-9_-]+");

	@Test
	public void displayNameIsNotBlank()
	{
		assertFalse(Branding.DISPLAY_NAME.trim().isEmpty());
	}

	@Test
	public void internalNameIsKebabCase()
	{
		assertTrue(KEBAB_CASE.matcher(Branding.INTERNAL_NAME).matches());
	}

	@Test
	public void configGroupIsKebabCase()
	{
		assertTrue(KEBAB_CASE.matcher(Branding.CONFIG_GROUP).matches());
	}

	@Test
	public void queueDirNameIsSafePathSegment()
	{
		assertTrue(SAFE_SEGMENT.matcher(Branding.QUEUE_DIR_NAME).matches());
	}
}
