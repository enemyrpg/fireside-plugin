package com.fireside;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.fireside.bank.BankSnapshots;
import com.fireside.bank.BankTracker;
import com.fireside.bank.ClientBankView;
import com.fireside.bank.RsProfileHashStore;
import com.fireside.event.EventJson;
import com.fireside.event.LootItem;
import com.fireside.event.LootSource;
import com.fireside.event.Event;
import com.fireside.event.UuidV7Generator;
import com.fireside.icons.ClientIconGameData;
import com.fireside.icons.ClientIconImages;
import com.fireside.icons.IconImporter;
import com.fireside.icons.IconUploader;
import com.fireside.loot.ClientItemLookup;
import com.fireside.loot.KillTracker;
import com.fireside.loot.LootMapper;
import com.fireside.net.IconClient;
import com.fireside.net.IconResult;
import com.fireside.net.IngestClient;
import com.fireside.net.SemVer;
import com.fireside.net.ServerUrl;
import com.fireside.net.VerifyClient;
import com.fireside.pet.ClientPetView;
import com.fireside.pet.PetTracker;
import com.fireside.queue.EventQueue;
import com.fireside.quest.ClientQuestView;
import com.fireside.quest.QuestTracker;
import com.fireside.raid.ClientRaidView;
import com.fireside.raid.ClientTfaRecordsView;
import com.fireside.raid.RaidTracker;
import com.fireside.raid.TfaRecordsReader;
import com.fireside.send.Backoff;
import com.fireside.send.ConnectionStatus;
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
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
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

@Slf4j
@PluginDescriptor(
	name = Branding.DISPLAY_NAME,
	internalName = Branding.INTERNAL_NAME,
	description = "Self-hosted tracker for your own accounts: sessions, screen time, XP, loot, KC, bank, quests and pets on your own dashboard.",
	tags = {"tracker", "sessions", "screen time", "loot", "bank", "pets", "self-hosted"}
)
public class FiresidePlugin extends Plugin
{
	private static final SemVer NAMED_ICONS_VERSION = SemVer.parse(Contract.NAMED_ICONS_VERSION);
	/** How often "Last contact: 12 s ago" is redrawn (no network calls). */
	private static final int CLOCK_TICK_MS = 1000;

	@Inject
	private FiresideConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private VerifyClient verifyClient;

	@Inject
	private IngestClient ingestClient;

	@Inject
	private IconClient iconClient;

	@Inject
	private ClientIconImages iconImages;

	@Inject
	private ClientIconGameData iconGameData;

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

	@Inject
	private ClientBankView bankView;

	@Inject
	private RsProfileHashStore bankHashes;

	@Inject
	private ClientQuestView questView;

	@Inject
	private ClientPetView petView;

	/**
	 * The plugin's own thread: all queue disk IO and sender state live on it.
	 * HTTP runs on OkHttp's pool; results come back to this thread.
	 */
	private ScheduledThreadPoolExecutor executor;
	private EventSender sender;
	/** Item icons: its own thread, so it never delays the event queue. */
	private ScheduledThreadPoolExecutor iconExecutor;
	private IconUploader icons;
	/** When the icons of the collection log, skills and bosses are checked (once per login). Synchronized. */
	private volatile IconImporter iconImporter;
	/** Sessions, heartbeats and AFK. Client thread, except at ClientShutdown. */
	private SessionTracker sessions;
	/** Kill counts and loot. Client thread. */
	private KillTracker kills;
	/** TFA's records table into {@code pb.records}. Client thread. */
	private TfaRecordsReader tfaRecords;
	private LootMapper lootMapper;
	/** Bank and potion storage snapshots. Reads on the client thread; synchronized. */
	private volatile BankTracker bank;
	/** Quest and diary snapshots. Reads on the client thread; synchronized. */
	private volatile QuestTracker quests;
	/** Pet drops. Client thread, except the flush at client shutdown; synchronized. */
	private volatile PetTracker pets;
	/** Panel line about a skipped bank snapshot; null when there is none. */
	private volatile String bankStatus;
	/** The last server contract the sender reported, for trackers created after it. */
	private volatile SemVer serverContract;
	/** Whether RuneLite's Loot Tracker (which posts {@code LootReceived}) is running. */
	private volatile boolean lootTrackerActive;
	/** True from the login screen (or a lost connection) until the next LOGGED_IN. Client thread. */
	private boolean awaitingLogin;

	// Panel state below is only touched on the Swing event dispatch thread.
	private FiresidePanel panel;
	private NavigationButton navigationButton;
	/** The sender's latest status. */
	private SenderStatus senderStatus = SenderStatus.STARTING_STATUS;
	/** Redraws the status line and "Last contact" every second. */
	private Timer clockTimer;

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
			EventSender.SEND_INTERVAL, status -> SwingUtilities.invokeLater(() -> onSenderStatus(status)));
		iconExecutor = new ScheduledThreadPoolExecutor(1, r ->
		{
			Thread thread = new Thread(r, Branding.DISPLAY_NAME + " icons");
			thread.setDaemon(true);
			return thread;
		});
		iconExecutor.setRemoveOnCancelPolicy(true);
		iconExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		icons = new IconUploader(iconExecutor, iconClient, iconImages,
			new Backoff(Backoff.DEFAULT_BASE, Backoff.DEFAULT_MAX, new SecureRandom()), Clock.systemUTC(),
			IconUploader.Timing.DEFAULT);
		icons.updateSettings(senderSettings(), config.uploadIcons());
		IconUploader contractIcons = icons;
		EventSender contactSink = sender;
		icons.setContactListener(kind ->
		{
			// 401 and 404 from the icon endpoints say nothing new: verify and ingest report those.
			if (kind == IconResult.Kind.OK || kind == IconResult.Kind.RATE_LIMITED || kind == IconResult.Kind.FAILED)
			{
				contactSink.onIconContact(kind == IconResult.Kind.OK, kind == IconResult.Kind.RATE_LIMITED);
			}
		});
		iconImporter = new IconImporter(iconGameData, contractIcons::importAll, this::iconImportAllowed,
			System::nanoTime);
		sender.setContractListener(version ->
		{
			serverContract = version;
			contractIcons.onServerContract(version);
			BankTracker bankTracker = bank;
			if (bankTracker != null)
			{
				bankTracker.onServerContract(version);
			}
			QuestTracker questTracker = quests;
			if (questTracker != null)
			{
				questTracker.onServerContract(version);
			}
			PetTracker petTracker = pets;
			if (petTracker != null)
			{
				petTracker.onServerContract(version);
			}
		});
		sender.start(senderSettings());

		EventSender queueSink = sender;
		IconUploader iconSink = icons;
		// Icons are collected only from events that are actually queued, with their exact item IDs.
		Consumer<Event> eventSink = event ->
		{
			queueSink.submit(event);
			iconSink.collect(event);
		};
		sessions = new SessionTracker(gameView, eventIds, Clock.systemUTC(), eventSink,
			() -> Duration.ofMinutes(config.afkThresholdMinutes()), config::sendXp, PluginVersion.VERSION);
		SessionTracker tracker = sessions;
		RaidTracker raids = new RaidTracker(tracker::emitInSession, raidView, itemLookup, config::sendRaidPurples,
			config::sendKillCounts);
		kills = new KillTracker(tracker::emitInSession, config::sendLoot, config::sendKillCounts, raids);
		tfaRecords = new TfaRecordsReader(tfaRecordsView, tracker::emitInSession, config::sendKillCounts);
		lootMapper = new LootMapper(itemLookup);
		bank = new BankTracker(bankView, new BankSnapshots(itemLookup), bankHashes, tracker::createInSession,
			eventSink, Clock.systemUTC(), () -> config.sendBank() && config.sendingEnabled(), status ->
		{
			bankStatus = status;
			SwingUtilities.invokeLater(this::refreshPanel);
		});
		// The sender may have verified the server before the tracker existed.
		bank.onServerContract(serverContract);
		quests = new QuestTracker(questView, bankHashes, tracker::createInSession, tracker::isActive, eventSink,
			Clock.systemUTC(), () -> config.sendQuests() && config.sendingEnabled(), System::nanoTime);
		quests.onServerContract(serverContract);
		pets = new PetTracker(petView, tracker::reserveInSession, eventSink,
			() -> config.sendPets() && config.sendingEnabled());
		pets.onServerContract(serverContract);
		lootTrackerActive = isLootTrackerActive();
		// Picks up a game that is already logged in (plugin turned on mid-game).
		clientThread.invoke(() ->
		{
			tracker.onGameState(client.getGameState());
			// Started at the login screen: the next login checks the connection.
			awaitingLogin = client.getGameState() != GameState.LOGGED_IN;
			tracker.setEnabled(config.sendingEnabled());
			IconImporter importer = iconImporter;
			if (importer != null && client.getGameState() == GameState.LOGGED_IN)
			{
				importer.onStartedLoggedIn();
			}
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
			if (sender != null)
			{
				onSenderStatus(sender.getStatus());
			}
			if (panel != null && clockTimer == null)
			{
				clockTimer = new Timer(CLOCK_TICK_MS, e -> tickPanel());
				clockTimer.start();
			}
		});
		log.debug("{} started", Branding.DISPLAY_NAME);
	}

	@Override
	protected void shutDown() throws Exception
	{
		BankTracker stoppingBank = bank;
		if (stoppingBank != null)
		{
			// Held snapshots belong to the session that is still running.
			stoppingBank.flush();
		}
		bank = null;
		QuestTracker stoppingQuests = quests;
		if (stoppingQuests != null)
		{
			stoppingQuests.flush();
		}
		quests = null;
		PetTracker stoppingPets = pets;
		if (stoppingPets != null)
		{
			// A held pet drop belongs to the session that is still running.
			stoppingPets.flush();
		}
		pets = null;
		serverContract = null;
		bankStatus = null;
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
		iconImporter.reset();
		iconImporter = null;
		icons.shutdown();
		iconExecutor.shutdown();
		icons = null;
		iconExecutor = null;

		clientToolbar.removeNavigation(navigationButton);
		SwingUtilities.invokeLater(() ->
		{
			if (clockTimer != null)
			{
				clockTimer.stop();
				clockTimer = null;
			}
			senderStatus = SenderStatus.STARTING_STATUS;
			panel = null;
		});
		navigationButton = null;
		log.debug("{} stopped", Branding.DISPLAY_NAME);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!Branding.CONFIG_GROUP.equals(event.getGroup())
			|| FiresideConfig.BANK_HASH_KEY.equals(event.getKey())
			|| FiresideConfig.POTION_STORAGE_HASH_KEY.equals(event.getKey())
			|| FiresideConfig.QUESTS_HASH_KEY.equals(event.getKey())
			|| FiresideConfig.DIARIES_HASH_KEY.equals(event.getKey()))
		{
			// The stored snapshot hashes are bookkeeping, not settings.
			return;
		}
		if (sender != null)
		{
			sender.updateSettings(senderSettings());
		}
		if (icons != null)
		{
			icons.updateSettings(senderSettings(), config.uploadIcons());
		}
		SessionTracker tracker = sessions;
		if (tracker != null && FiresideConfig.SENDING_ENABLED_KEY.equals(event.getKey()))
		{
			boolean enabled = config.sendingEnabled();
			clientThread.invoke(() -> tracker.setEnabled(enabled));
		}
		BankTracker bankTracker = bank;
		if (bankTracker != null)
		{
			bankTracker.onSettingsChanged();
		}
		QuestTracker questTracker = quests;
		if (questTracker != null)
		{
			questTracker.onSettingsChanged();
		}
		String key = event.getKey();
		boolean connectionChanged = FiresideConfig.SERVER_URL_KEY.equals(key)
			|| FiresideConfig.API_KEY_KEY.equals(key);
		IconImporter importer = iconImporter;
		if (importer != null && connectionChanged)
		{
			// Another server: check its icons once, without waiting for the next login.
			importer.onConnectionChanged();
		}
		SwingUtilities.invokeLater(this::refreshPanel);
	}

	/** Ends the session and makes sure every queued event is on disk. Never waits for the network. */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		BankTracker bankTracker = bank;
		if (bankTracker != null)
		{
			// Before session.end, and before the queue is flushed to disk.
			bankTracker.flush();
		}
		QuestTracker questTracker = quests;
		if (questTracker != null)
		{
			questTracker.flush();
		}
		PetTracker petTracker = pets;
		if (petTracker != null)
		{
			petTracker.flush();
		}
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
		if (state == GameState.LOGIN_SCREEN || state == GameState.LOGIN_SCREEN_AUTHENTICATOR
			|| state == GameState.CONNECTION_LOST)
		{
			awaitingLogin = true;
		}
		else if (state == GameState.LOGGED_IN && awaitingLogin)
		{
			// A real login, not a world hop or a region load (LOADING -> LOGGED_IN).
			awaitingLogin = false;
			if (sender != null)
			{
				sender.onLogin();
			}
		}
		IconImporter importer = iconImporter;
		if (importer != null)
		{
			importer.onGameState(state);
		}
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
		BankTracker bankTracker = bank;
		if (bankTracker != null && (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN
			|| state == GameState.LOGIN_SCREEN_AUTHENTICATOR))
		{
			// The held snapshots go in the session that is ending, before session.end.
			bankTracker.flush();
		}
		QuestTracker questTracker = quests;
		if (questTracker != null && (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN
			|| state == GameState.LOGIN_SCREEN_AUTHENTICATOR))
		{
			questTracker.flush();
			// The next login waits until its own quest data is loaded.
			questTracker.reset();
		}
		PetTracker petTracker = pets;
		if (petTracker != null && (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN
			|| state == GameState.LOGIN_SCREEN_AUTHENTICATOR))
		{
			// A held pet drop goes in the session that is ending, before session.end.
			petTracker.flush();
			petTracker.reset();
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
		BankTracker bankTracker = bank;
		if (bankTracker != null)
		{
			bankTracker.onTick();
		}
		QuestTracker questTracker = quests;
		if (questTracker != null)
		{
			questTracker.onTick();
		}
		PetTracker petTracker = pets;
		if (petTracker != null)
		{
			petTracker.onTick(client.getTickCount());
		}
		if (icons != null)
		{
			icons.onGameTick();
		}
		IconImporter importer = iconImporter;
		if (importer != null)
		{
			importer.onTick();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (sessions != null)
		{
			sessions.onStat(event.getSkill().name(), event.getXp(), event.getLevel());
		}
		PetTracker petTracker = pets;
		if (petTracker != null)
		{
			petTracker.onXp(event.getSkill().name(), event.getXp(), client.getTickCount());
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		QuestTracker questTracker = quests;
		if (questTracker != null)
		{
			questTracker.onVarChanged(event.getVarpId(), event.getVarbitId());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (kills != null)
		{
			kills.onChatMessage(event.getType(), event.getMessage(), client.getTickCount());
		}
		PetTracker petTracker = pets;
		if (petTracker != null)
		{
			petTracker.onChatMessage(event.getType(), event.getMessage(), client.getTickCount());
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
		BankTracker bankTracker = bank;
		if (bankTracker != null && event.getGroupId() == InterfaceID.BANKMAIN)
		{
			// Read after the close, outside the script that closed the bank.
			clientThread.invokeLater(bankTracker::onBankClosed);
		}
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
		LootSource sentAs = kills.onLoot(source, items, event.getAmount(), client.getTickCount());
		PetTracker petTracker = pets;
		if (petTracker != null)
		{
			// Only the source's name, for the source guess of a pet drop. Null for another player's loot.
			petTracker.onLoot((sentAs != null ? sentAs : source).getName(), client.getTickCount());
		}
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

	/**
	 * Whether the icons of the collection log, skills and bosses may be checked now: sending and
	 * "Upload item icons" are on, the server is set up, and its contract has those icons.
	 */
	private boolean iconImportAllowed()
	{
		SemVer contract = serverContract;
		if (contract == null || contract.compareTo(NAMED_ICONS_VERSION) < 0 || !config.uploadIcons())
		{
			return false;
		}
		SenderSettings settings = senderSettings();
		return settings.isEnabled() && settings.isConfigured();
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

	/** A new status from the sender, on the EDT. */
	private void onSenderStatus(SenderStatus status)
	{
		senderStatus = status;
		if (panel != null)
		{
			panel.renderQueue(QueueView.compute(status, ZoneId.systemDefault()));
		}
		refreshPanel();
	}

	/** "Test connection" button, on the EDT. The sender runs the request on its executor and OkHttp's threads. */
	private void testConnection()
	{
		if (sender != null)
		{
			sender.testConnection();
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
		Instant now = Instant.now();
		ConnectionStatus connection = senderStatus.getConnection();
		panel.render(statusView(now), url, config.sendingEnabled(), connection.getVerifyResponse(),
			StatusView.ago(connection.getLastContactAt(), now), connection.isManualCheck(),
			ServerUrl.parse(url) != null);
	}

	/** Every second, on the EDT: only the time-dependent text, without rebuilding the panel. */
	private void tickPanel()
	{
		if (panel == null)
		{
			return;
		}
		Instant now = Instant.now();
		panel.updateTimeText(statusView(now), StatusView.ago(senderStatus.getConnection().getLastContactAt(), now));
	}

	private StatusView statusView(Instant now)
	{
		return StatusView.compute(config.serverUrl(), config.apiKey(), config.sendingEnabled(), senderStatus, now,
			ZoneId.systemDefault(), config.sendLoot() && !lootTrackerActive, bankStatus);
	}

	@Provides
	FiresideConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FiresideConfig.class);
	}
}
