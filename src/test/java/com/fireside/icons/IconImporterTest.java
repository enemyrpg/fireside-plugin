package com.fireside.icons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.GameState;
import org.junit.Before;
import org.junit.Test;

/** When the icon catalog is read and handed over: once per login. */
public class IconImporterTest
{
	private final FakeIconGameData data = new FakeIconGameData();
	private final List<IconCatalog> imported = new ArrayList<>();
	private boolean allowed = true;
	private IconImporter importer;

	@Before
	public void setUp()
	{
		data.tab(new int[]{100, 101}, new int[]{200});
		data.skill("Attack", 197);
		data.boss("Zulrah", 4001);
		importer = new IconImporter(data, imported::add, () -> allowed, System::nanoTime);
	}

	private void ticks(int count)
	{
		for (int i = 0; i < count; i++)
		{
			importer.onTick();
		}
	}

	private void login()
	{
		importer.onGameState(GameState.LOGGING_IN);
		importer.onGameState(GameState.LOGGED_IN);
	}

	@Test
	public void readsTheCatalogOnceAfterLogin()
	{
		ticks(20);
		assertTrue("nothing before login", imported.isEmpty());

		login();
		ticks(IconImporter.LOGIN_DELAY_TICKS);
		assertTrue("too early", imported.isEmpty());
		ticks(50);
		assertEquals(1, imported.size());
		assertEquals(3, imported.get(0).getItemIds().size());
		assertEquals(3, imported.get(0).getSprites().size());

		ticks(200);
		assertEquals("once per login", 1, imported.size());
	}

	@Test
	public void aWorldHopIsNotALogin()
	{
		login();
		ticks(50);
		importer.onGameState(GameState.HOPPING);
		importer.onGameState(GameState.LOADING);
		importer.onGameState(GameState.LOGGED_IN);
		ticks(50);
		importer.onGameState(GameState.LOADING);
		importer.onGameState(GameState.LOGGED_IN);
		ticks(50);
		assertEquals(1, imported.size());
	}

	@Test
	public void everyLoginChecksAgain()
	{
		login();
		ticks(50);
		importer.onGameState(GameState.LOGIN_SCREEN);
		ticks(50);
		assertEquals(1, imported.size());
		login();
		ticks(50);
		assertEquals(2, imported.size());
	}

	@Test
	public void waitsUntilItIsAllowedAndThenRunsInTheSameLogin()
	{
		allowed = false;
		login();
		ticks(100);
		assertTrue(imported.isEmpty());
		assertEquals("game data read while not allowed", 0, data.reads);

		// E.g. the server was verified with a new enough contract, or icons were turned on.
		allowed = true;
		ticks(50);
		assertEquals(1, imported.size());
	}

	@Test
	public void aCheckThatWasNeverAllowedIsDroppedAtLogout()
	{
		allowed = false;
		login();
		ticks(10);
		importer.onGameState(GameState.LOGIN_SCREEN);
		allowed = true;
		ticks(50);
		assertTrue(imported.isEmpty());
	}

	@Test
	public void aNewConnectionIsCheckedOnceWithoutANewLogin()
	{
		login();
		ticks(50);
		importer.onConnectionChanged();
		ticks(50);
		assertEquals(2, imported.size());
		ticks(50);
		assertEquals(2, imported.size());
	}

	@Test
	public void aNewConnectionAtTheLoginScreenWaitsForTheLogin()
	{
		importer.onConnectionChanged();
		ticks(50);
		assertTrue(imported.isEmpty());
		login();
		ticks(50);
		assertEquals(1, imported.size());
	}

	@Test
	public void aPluginTurnedOnWhileLoggedInChecksOnce()
	{
		importer.onStartedLoggedIn();
		ticks(50);
		assertEquals(1, imported.size());
	}

	@Test
	public void theReadIsSpreadOverTicksWhenTheClockSaysItIsSlow()
	{
		long[] now = {0};
		importer = new IconImporter(data, imported::add, () -> allowed, () -> now[0] += 1_500_000);
		login();
		ticks(IconImporter.LOGIN_DELAY_TICKS + 1);
		assertTrue("read in one tick", imported.isEmpty());
		ticks(50);
		assertEquals(1, imported.size());
	}
}
