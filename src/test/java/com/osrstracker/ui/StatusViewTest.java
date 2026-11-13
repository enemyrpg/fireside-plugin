package com.osrstracker.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Collections;
import org.junit.Test;

public class StatusViewTest
{
	private static final String KEY = "ost_key";

	@Test
	public void notConfiguredWithoutUrlOrKey()
	{
		assertEquals("Not configured", StatusView.compute("", KEY, false, null).getTitle());
		assertEquals("Not configured", StatusView.compute("http://127.0.0.1:3000", " ", false, null).getTitle());
		assertEquals("Not configured", StatusView.compute(null, null, false, null).getTitle());
	}

	@Test
	public void invalidUrl()
	{
		StatusView view = StatusView.compute("localhost:3000", KEY, false, null);
		assertEquals(StatusView.Level.ERROR, view.getLevel());
		assertEquals("Invalid server URL", view.getTitle());
	}

	@Test
	public void notTestedYet()
	{
		StatusView view = StatusView.compute("http://127.0.0.1:3000", KEY, false, null);
		assertEquals(StatusView.Level.NEUTRAL, view.getLevel());
		assertEquals("Not tested", view.getTitle());
		assertTrue(view.getWarnings().isEmpty());
	}

	@Test
	public void insecureUrlWarnsWithoutTesting()
	{
		StatusView view = StatusView.compute("http://192.168.1.2:3000", KEY, false, null);
		assertEquals(StatusView.Level.WARNING, view.getLevel());
		assertEquals(1, view.getWarnings().size());
		assertEquals(StatusView.INSECURE_WARNING, view.getWarnings().get(0));
	}

	@Test
	public void lootTrackerOffWarnsInEveryState()
	{
		StatusView notConfigured = StatusView.compute("", KEY, false, null, true);
		assertEquals(Collections.singletonList(StatusView.LOOT_TRACKER_OFF_WARNING), notConfigured.getWarnings());

		StatusView notTested = StatusView.compute("http://127.0.0.1:3000", KEY, false, null, true);
		assertEquals(StatusView.Level.WARNING, notTested.getLevel());
		assertEquals(Collections.singletonList(StatusView.LOOT_TRACKER_OFF_WARNING), notTested.getWarnings());

		StatusView invalid = StatusView.compute("localhost:3000", KEY, false, null, true);
		assertEquals(StatusView.Level.ERROR, invalid.getLevel());
		assertTrue(invalid.getWarnings().contains(StatusView.LOOT_TRACKER_OFF_WARNING));
	}

	@Test
	public void noLootTrackerWarningWhenItIsOn()
	{
		assertTrue(StatusView.compute("http://127.0.0.1:3000", KEY, false, null, false).getWarnings().isEmpty());
		assertTrue(StatusView.compute("http://127.0.0.1:3000", KEY, false, null).getWarnings().isEmpty());
	}

	@Test
	public void testing()
	{
		StatusView view = StatusView.compute("http://127.0.0.1:3000", KEY, true, null);
		assertEquals(StatusView.Level.BUSY, view.getLevel());
	}

	@Test
	public void panelIconIsSixteenPixelsAndDrawn()
	{
		BufferedImage icon = PanelIcon.create();
		assertEquals(PanelIcon.SIZE, icon.getWidth());
		assertEquals(PanelIcon.SIZE, icon.getHeight());
		assertTrue("Icon is empty", (icon.getRGB(12, 13) >>> 24) != 0);
	}
}
