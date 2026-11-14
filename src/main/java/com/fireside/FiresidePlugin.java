package com.fireside;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.fireside.event.EventJson;
import com.fireside.event.LootItem;
import com.fireside.event.LootSource;
import com.fireside.event.UuidV7Generator;
import com.fireside.loot.ClientItemLookup;
import com.fireside.loot.KillTracker;
import com.fireside.loot.LootMapper;
import com.fireside.net.IngestClient;
import com.fireside.net.ServerUrl;
import com.fireside.net.VerifyClient;
import com.fireside.net.VerifyResult;
import com.fireside.queue.EventQueue;
import com.fireside.raid.ClientRaidView;
import com.fireside.raid.ClientTfaRecordsView;
import com.fireside.raid.RaidTracker;
import com.fireside.raid.TfaRecordsReader;
import com.fireside.send.Backoff;
import com.fireside.send.EventSender;
import com.fireside.send.SenderSettings;
import com.fireside.send.SenderStatus;
import com.fireside.session.ClientGameView;
import com.fireside.session.SessionTracker;
import com.fireside.ui.FiresidePanel;
import com.fireside.ui.PanelIcon;
import com.fireside.ui.QueueView;
import com.fireside.ui.StatusView;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.LinkBrowser;
import okhttp3.Call;

@Slf4j
@PluginDescriptor(
	name = Branding.DISPLAY_NAME,
	internalName = Branding.INTERNAL_NAME,
	description = "Self-hosted tracker for your own accounts: sessions, screen time, XP, loot and KC on your own dashboard.",
	tags = {"tracker", "sessions", "screen time", "loot", "self-hosted"}
)
public class FiresidePlugin extends Plugin
{
	@Inject
	private FiresideConfig config;

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

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientGameView gameView;

	@Inject
	private UuidV7Generator eventIds;

	@Inject
	private ClientItemLookup itemLookup;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ClientRaidView raidView;

	@Inject
	private ClientTfaRecordsView tfaRecordsView;

	/**
	 * The plugin's own thread: all queue disk IO and sender state live on it.
	 * HTTP runs on OkHttp's pool; results come back to this thread.
	 */
	private ScheduledThreadPoolExecutor executor;
	private EventSender sender;
	/** Sessions, heartbeats and AFK. Client thread, except at ClientShutdown. */
	private SessionTracker sessions;
	/** Kill counts and loot. Client thread. */
	private KillTracker kills;
	/** TFA's records table into {@code pb.records}. Client thread. */
	private TfaRecordsReader tfaRecords;
	private LootMapper lootMapper;
	/** Whether RuneLite's Loot Tracker (which posts {@code LootReceived}) is running. */
	private volatile boolean lootTrackerActive;

	// Panel state below is only touched on the Swing event dispatch thread.
	private FiresidePanel panel;
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
		// .runelite/plugin-data/<internal name>, from Plugin#getPluginDirectory().
		Filepath dataDirectory = getPluginDirectory();
		executor = new ScheduledThreadPoolExecutor(1, r ->
		{
			Thread thread = new Thread(r, Branding.DISPLAY_NAME + " events");
			thread.setDaemon(true);
			return thread;
		});
		executor.setRemoveOnCancelPolicy(true);
		Filepath queueDir = dataDirectory.joinSegment(Branding.QUEUE_DIR_NAME);
		EventQueue queue = new EventQueue(queueDir, gson, Clock.systemUTC());
		sender = new EventSender(executor, queue, eventJson, ingestClient, verifyClient,
			new Backoff(Backoff.DEFAULT_BASE, Backoff.DEFAULT_MAX, new SecureRandom()), Clock.systemUTC(),
			EventSender.SEND_INTERVAL, status -> SwingUtilities.invokeLater(() -> renderQueue(status)));
		sender.start(senderSettings());

		EventSender eventSink = sender;
		sessions = new SessionTracker(gameView, eventIds, Clock.systemUTC(), eventSink::submit,
			() -> Duration.ofMinutes(config.afkThresholdMinutes()), config::sendXp, PluginVersion.VERSION);
		SessionTracker tracker = sessions;
		RaidTracker raids = new RaidTracker(tracker::emitInSession, raidView, itemLookup, config::sendRaidPurples,
			config::sendKillCounts);
		kills = new KillTracker(tracker::emitInSession, config::sendLoot, config::sendKillCounts, raids);
		tfaRecords = new TfaRecordsReader(tfaRecordsView, tracker::emitInSession, config::sendKillCounts);
		lootMapper = new LootMapper(itemLookup);
		lootTrackerActive = isLootTrackerActive();
		// Picks up a game that is already logged in (plugin turned on mid-game).
		clientThread.invoke(() ->
		{
			tracker.onGameState(client.getGameState());
			tracker.setEnabled(config.sendingEnabled());
		});

		panel = new FiresidePanel(this::testConnection, this::openDashboard, this::sendNow,
			queueDir.toString());
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
		// Queues an open AFK period (cached values only, safe off the client thread) before the
		// sender stops. No session.end: the server closes the session at its last heartbeat.
		sessions.stop();
		sessions = null;
		kills = null;
		tfaRecords = null;
		lootMapper = null;
		// Never waits: queued writes finish on the executor, the request in flight is cancelled.
		sender.shutdown();
		executor.shutdown();
		sender = null;
		executor = null;

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
		SessionTracker tracker = sessions;
		if (tracker != null && FiresideConfig.SENDING_ENABLED_KEY.equals(event.getKey()))
		{
			boolean enabled = config.sendingEnabled();
			clientThread.invoke(() -> tracker.setEnabled(enabled));
		}
		String key = event.getKey();
		boolean connectionChanged = FiresideConfig.SERVER_URL_KEY.equals(key)
			|| FiresideConfig.API_KEY_KEY.equals(key);
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

	/** Ends the session and makes sure every queued event is on disk. Never waits for the network. */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		if (sessions != null)
		{
			sessions.onClientShutdown();
		}
		if (sender != null)
		{
			event.waitFor(sender.flush());
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (kills != null && (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN
			|| state == GameState.LOGIN_SCREEN_AUTHENTICATOR))
		{
			// A kill count from the last tick still belongs to the session that is ending.
			kills.flush();
			kills.reset();
		}
		if (tfaRecords != null && (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN
			|| state == GameState.LOGIN_SCREEN_AUTHENTICATOR))
		{
			tfaRecords.reset();
		}
		if (sessions != null && sessions.onGameState(event.getGameState()) && sender != null)
		{
			// session.end is queued; send it (and the rest) now.
			sender.onLogout();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (kills != null)
		{
			kills.onTick(client.getTickCount());
		}
		if (tfaRecords != null)
		{
			tfaRecords.onTick();
		}
		if (sessions != null)
		{
			sessions.onTick();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (sessions != null)
		{
			sessions.onStat(event.getSkill().name(), event.getXp(), event.getLevel());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (kills != null)
		{
			kills.onChatMessage(event.getType(), event.getMessage(), client.getTickCount());
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (tfaRecords != null && event.getGroupId() == InterfaceID.TFA_STATS)
		{
			tfaRecords.onTableOpened();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (tfaRecords != null && event.getGroupId() == InterfaceID.TFA_STATS)
		{
			tfaRecords.onTableClosed();
		}
	}

	/** From RuneLite's Loot Tracker; only posted while that plugin is on. */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (kills == null || lootMapper == null)
		{
			return;
		}
		// For PLAYER loot the name is another player's: dropped here, never sent or logged.
		LootSource source = LootMapper.source(event.getType(), event.getName(), event.getCombatLevel(),
			event.getMetadata());
		if (source == null)
		{
			return;
		}
		List<LootItem> items = lootMapper.items(event.getItems());
		kills.onLoot(source, items, event.getAmount(), client.getTickCount());
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (event.getPlugin() instanceof LootTrackerPlugin)
		{
			lootTrackerActive = event.isLoaded();
			SwingUtilities.invokeLater(this::refreshPanel);
		}
	}

	private boolean isLootTrackerActive()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof LootTrackerPlugin)
			{
				return pluginManager.isPluginActive(plugin);
			}
		}
		return false;
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
		StatusView view = StatusView.compute(url, config.apiKey(), testing, lastResult,
			config.sendLoot() && !lootTrackerActive);
		panel.render(view, url, config.sendingEnabled(),
			lastResult != null ? lastResult.getResponse() : null,
			lastTestedAt, testing, ServerUrl.parse(url) != null);
	}

	@Provides
	FiresideConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FiresideConfig.class);
	}
}
