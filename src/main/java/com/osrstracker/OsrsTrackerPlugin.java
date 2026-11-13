package com.osrstracker;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.osrstracker.event.EventJson;
import com.osrstracker.net.IngestClient;
import com.osrstracker.net.ServerUrl;
import com.osrstracker.net.VerifyClient;
import com.osrstracker.net.VerifyResult;
import com.osrstracker.queue.EventQueue;
import com.osrstracker.send.Backoff;
import com.osrstracker.send.EventSender;
import com.osrstracker.send.SenderSettings;
import com.osrstracker.send.SenderStatus;
import com.osrstracker.ui.OsrsTrackerPanel;
import com.osrstracker.ui.PanelIcon;
import com.osrstracker.ui.QueueView;
import com.osrstracker.ui.StatusView;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
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

	@Inject
	private IngestClient ingestClient;

	@Inject
	private EventJson eventJson;

	@Inject
	private Gson gson;

	/**
	 * The plugin's own thread: all queue disk IO and sender state live on it.
	 * HTTP runs on OkHttp's pool; results come back to this thread.
	 */
	private ScheduledThreadPoolExecutor executor;
	private EventSender sender;
	/** {@code .runelite/plugin-data/<internal name>}, from {@link Plugin#getPluginDirectory()}. */
	private Filepath dataDirectory;
	/** Only touched on the client thread. */
	private GameState lastGameState;

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
		// Only resolves the folder (and creates plugin-data/ if missing); the queue is opened on the executor.
		dataDirectory = getPluginDirectory();
		executor = new ScheduledThreadPoolExecutor(1, r ->
		{
			Thread thread = new Thread(r, Branding.DISPLAY_NAME + " events");
			thread.setDaemon(true);
			return thread;
		});
		executor.setRemoveOnCancelPolicy(true);
		EventQueue queue = new EventQueue(dataDirectory.joinSegment(Branding.QUEUE_DIR_NAME), gson,
			Clock.systemUTC());
		sender = new EventSender(executor, queue, eventJson, ingestClient, verifyClient,
			new Backoff(Backoff.DEFAULT_BASE, Backoff.DEFAULT_MAX, new SecureRandom()), Clock.systemUTC(),
			EventSender.SEND_INTERVAL, status -> SwingUtilities.invokeLater(() -> renderQueue(status)));
		sender.start(senderSettings());

		panel = new OsrsTrackerPanel(this::testConnection, this::openDashboard, this::sendNow, this::openFolder);
		navigationButton = NavigationButton.builder()
			.tooltip(Branding.DISPLAY_NAME)
			.icon(PanelIcon.create())
			.priority(10)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		SwingUtilities.invokeLater(() ->
		{
			refreshPanel();
			if (sender != null)
			{
				renderQueue(sender.getStatus());
			}
		});
		log.debug("{} started", Branding.DISPLAY_NAME);
	}

	@Override
	protected void shutDown() throws Exception
	{
		// Never waits: queued writes finish on the executor, the request in flight is cancelled.
		sender.shutdown();
		executor.shutdown();
		sender = null;
		executor = null;
		dataDirectory = null;
		lastGameState = null;

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
		if (sender != null)
		{
			sender.updateSettings(senderSettings());
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

	/** Makes sure every queued event is on disk before the client exits. Never waits for the network. */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		if (sender != null)
		{
			event.waitFor(sender.flush());
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN && lastGameState == GameState.LOGGED_IN && sender != null)
		{
			sender.onLogout();
		}
		lastGameState = state;
	}

	private SenderSettings senderSettings()
	{
		return new SenderSettings(config.sendingEnabled(), config.serverUrl(), config.apiKey());
	}

	/** "Send now" button, on the EDT. */
	private void sendNow()
	{
		if (sender != null)
		{
			sender.sendNow();
		}
	}

	/** "Open folder" button, on the EDT. Creates the folder on the executor, then opens it like RuneLite's screenshot folder. */
	private void openFolder()
	{
		Filepath dir = dataDirectory;
		if (dir == null || executor == null)
		{
			return;
		}
		try
		{
			executor.execute(() ->
			{
				try
				{
					dir.createDirectories();
				}
				catch (IOException e)
				{
					log.warn("Could not create the plugin data folder", e);
					return;
				}
				LinkBrowser.open(dir.toString());
			});
		}
		catch (RejectedExecutionException e)
		{
			log.debug("Plugin is stopping; not opening the folder");
		}
	}

	private void renderQueue(SenderStatus status)
	{
		if (panel != null)
		{
			panel.renderQueue(QueueView.compute(status, ZoneId.systemDefault()));
		}
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
		if (sender != null)
		{
			// Success resumes stopped sending and updates the contract check.
			sender.onConnectionTested(result);
		}
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
