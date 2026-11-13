package com.osrstracker;

import com.google.inject.Provides;
import com.osrstracker.net.ServerUrl;
import com.osrstracker.net.VerifyClient;
import com.osrstracker.net.VerifyResult;
import com.osrstracker.ui.OsrsTrackerPanel;
import com.osrstracker.ui.PanelIcon;
import com.osrstracker.ui.StatusView;
import java.time.Instant;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.LinkBrowser;
import okhttp3.Call;

@Slf4j
@PluginDescriptor(
	name = Branding.DISPLAY_NAME,
	internalName = Branding.INTERNAL_NAME,
	description = "Sends XP, loot, kill counts and session data to your self-hosted tracker server",
	tags = {"tracker", "xp", "loot", "sessions", "self-hosted"}
)
public class OsrsTrackerPlugin extends Plugin
{
	@Inject
	private OsrsTrackerConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private VerifyClient verifyClient;

	// Panel state below is only touched on the Swing event dispatch thread.
	private OsrsTrackerPanel panel;
	private NavigationButton navigationButton;
	private Call pendingTest;
	/** Bumped when a test starts or the URL/key changes, so stale results are dropped. */
	private int testGeneration;
	private boolean testing;
	private VerifyResult lastResult;
	private Instant lastTestedAt;

	@Override
	protected void startUp() throws Exception
	{
		panel = new OsrsTrackerPanel(this::testConnection, this::openDashboard);
		navigationButton = NavigationButton.builder()
			.tooltip(Branding.DISPLAY_NAME)
			.icon(PanelIcon.create())
			.priority(10)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		SwingUtilities.invokeLater(this::refreshPanel);
		log.debug("{} started", Branding.DISPLAY_NAME);
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navigationButton);
		SwingUtilities.invokeLater(() ->
		{
			resetTest();
			lastResult = null;
			lastTestedAt = null;
			panel = null;
		});
		navigationButton = null;
		log.debug("{} stopped", Branding.DISPLAY_NAME);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!Branding.CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}
		String key = event.getKey();
		boolean connectionChanged = OsrsTrackerConfig.SERVER_URL_KEY.equals(key)
			|| OsrsTrackerConfig.API_KEY_KEY.equals(key);
		SwingUtilities.invokeLater(() ->
		{
			if (connectionChanged)
			{
				// An old result says nothing about the new URL or key.
				resetTest();
				lastResult = null;
				lastTestedAt = null;
			}
			refreshPanel();
		});
	}

	/** "Test connection" button, on the EDT. The request itself runs on OkHttp's threads. */
	private void testConnection()
	{
		resetTest();
		int generation = testGeneration;
		testing = true;
		refreshPanel();
		pendingTest = verifyClient.verify(config.serverUrl(), config.apiKey(),
			result -> SwingUtilities.invokeLater(() -> onTestFinished(generation, result)));
	}

	private void onTestFinished(int generation, VerifyResult result)
	{
		if (panel == null || generation != testGeneration)
		{
			return;
		}
		log.debug("Connection test result: {}", result.getOutcome());
		testing = false;
		pendingTest = null;
		lastResult = result;
		lastTestedAt = Instant.now();
		refreshPanel();
	}

	private void resetTest()
	{
		testGeneration++;
		testing = false;
		if (pendingTest != null)
		{
			pendingTest.cancel();
			pendingTest = null;
		}
	}

	private void openDashboard()
	{
		ServerUrl url = ServerUrl.parse(config.serverUrl());
		if (url != null)
		{
			LinkBrowser.browse(url.dashboardUrl().toString());
		}
	}

	private void refreshPanel()
	{
		if (panel == null)
		{
			return;
		}
		String url = config.serverUrl();
		StatusView view = StatusView.compute(url, config.apiKey(), testing, lastResult);
		panel.render(view, url, config.sendingEnabled(),
			lastResult != null ? lastResult.getResponse() : null,
			lastTestedAt, testing, ServerUrl.parse(url) != null);
	}

	@Provides
	OsrsTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsTrackerConfig.class);
	}
}
