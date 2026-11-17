package com.fireside.icons;

import com.fireside.net.IconKey;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;

/**
 * Decides when the icons of the whole collection log, all skills and all
 * bosses/raids are checked against the server (10.12): once per login, and once
 * more if the server URL or key changes while logged in. A world hop is not a
 * login.
 * <p>
 * When a check is due and allowed, the {@link IconCatalog} is read from the game
 * data over a few game ticks ({@link #TICK_BUDGET_NANOS} per tick) and handed to
 * the sink, which asks the server what it lacks. A server that already has
 * everything gets a few {@code wanted} requests and no uploads.
 * <p>
 * A check that is not allowed yet (no server, "Upload item icons" off, the
 * server's contract unknown or too old) stays due for the rest of the login, so
 * it runs as soon as it is allowed.
 */
@Slf4j
public class IconImporter
{
	/** The catalog read uses at most about this much per game tick. */
	static final long TICK_BUDGET_NANOS = 2_000_000;
	/** Game ticks from login to the read, so it stays clear of the login work. */
	static final int LOGIN_DELAY_TICKS = 5;

	private final IconGameData data;
	private final Consumer<IconCatalog> sink;
	private final BooleanSupplier allowed;
	private final LongSupplier nanoTime;

	private boolean loggingIn;
	private boolean loggedIn;
	private boolean due;
	private int waitTicks;
	@Nullable
	private IconCatalogReader reader;

	/**
	 * @param sink     gets the catalog when it is read (on the client thread; must not block)
	 * @param allowed  whether icons may be imported now; cheap, called every game tick while a check is due
	 * @param nanoTime {@code System::nanoTime}
	 */
	public IconImporter(IconGameData data, Consumer<IconCatalog> sink, BooleanSupplier allowed, LongSupplier nanoTime)
	{
		this.data = data;
		this.sink = sink;
		this.allowed = allowed;
		this.nanoTime = nanoTime;
	}

	public synchronized void onGameState(GameState state)
	{
		switch (state)
		{
			case LOGGING_IN:
				loggingIn = true;
				break;
			case LOGGED_IN:
				// After HOPPING and LOADING this is the same login.
				if (loggingIn)
				{
					loggingIn = false;
					due = true;
					waitTicks = LOGIN_DELAY_TICKS;
				}
				loggedIn = true;
				break;
			case LOGIN_SCREEN:
			case LOGIN_SCREEN_AUTHENTICATOR:
				loggedIn = false;
				due = false;
				reader = null;
				break;
			default:
				break;
		}
	}

	/** The plugin was turned on in a game that is already logged in: that counts as the login. */
	public synchronized void onStartedLoggedIn()
	{
		loggedIn = true;
		due = true;
		waitTicks = LOGIN_DELAY_TICKS;
	}

	/** The server URL or key changed: the new server is checked once, without a new login. May be called from any thread. */
	public synchronized void onConnectionChanged()
	{
		if (loggedIn)
		{
			reader = null;
			due = true;
		}
	}

	/** Every game tick, on the client thread. */
	public synchronized void onTick()
	{
		if (reader == null)
		{
			if (!due)
			{
				return;
			}
			if (waitTicks > 0)
			{
				waitTicks--;
				return;
			}
			if (!allowed.getAsBoolean())
			{
				return;
			}
			due = false;
			reader = new IconCatalogReader(data, nanoTime);
		}
		IconCatalog catalog;
		try
		{
			catalog = reader.step(TICK_BUDGET_NANOS);
		}
		catch (RuntimeException e)
		{
			// Never tried again this login: game data we do not understand will not change.
			log.debug("Could not read the icon catalog: {}", e.getClass().getSimpleName());
			reader = null;
			return;
		}
		log.debug("Icon catalog: tick {} took {} us", reader.ticks(), reader.lastTickNanos() / 1000);
		if (catalog != null)
		{
			log.debug("Icon catalog read in {} tick(s), {} us: {} item(s), {} skill(s), {} boss(es)", reader.ticks(),
				reader.totalNanos() / 1000, catalog.count(IconKey.Kind.ITEM),
				catalog.count(IconKey.Kind.SKILL), catalog.count(IconKey.Kind.BOSS));
			reader = null;
			sink.accept(catalog);
		}
	}

	/** Forgets everything (plugin shutdown). */
	public synchronized void reset()
	{
		loggingIn = false;
		loggedIn = false;
		due = false;
		reader = null;
	}
}
