package com.fireside.icons;

import com.fireside.net.IconKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.SpriteID;

/**
 * Reads the {@link IconCatalog} from the game's own data, a little per game
 * tick so the client thread never hitches. One reader reads once.
 * <p>
 * <b>Items:</b> the collection log. The enum of tabs holds one struct per tab;
 * a tab struct has an enum of page structs; a page struct has an enum of item
 * IDs. A few IDs are shown as another item in the log; those are replaced
 * through the replacement enum, and the replacement is the ID that is used.
 * <p>
 * <b>Skills and bosses/raids:</b> the game's hiscore tables, which have the
 * name and the sprite of each. A skill's key is its name in
 * {@code UPPER_SNAKE_CASE}, as in {@code xp.snapshot}; a boss's key is the name
 * exactly as the game writes it (the server normalizes it). The total is not in
 * the skill table: its key is {@code OVERALL} and its image the total-level
 * sprite.
 * <p>
 * Anything the game data lacks or the server would reject is left out; the
 * dashboard then keeps the initials.
 */
public class IconCatalogReader
{
	// RuneLite's gameval package has no constants for the collection log's enums and struct params,
	// so they are named here (task 10.12; confirmed in game by the number of items read).
	/** Enum: the collection log's tabs, one struct per tab. */
	static final int ENUM_COLLECTION_TABS = 2102;
	/** Enum: item ID in the log's data to the item ID the log shows instead. */
	static final int ENUM_COLLECTION_REPLACEMENTS = 3721;
	/** Tab struct param: the enum of the tab's page structs. (682 is the tab's name.) */
	static final int PARAM_TAB_PAGES = 683;
	/** Page struct param: the enum of the page's item IDs. (689 is the page's name.) */
	static final int PARAM_PAGE_ITEMS = 690;

	/** The key of the total, which the skill table does not have. */
	static final String OVERALL = "OVERALL";
	static final int OVERALL_SPRITE = SpriteID.Staticons2.TOTAL;

	private final IconGameData data;
	private final LongSupplier nanoTime;

	/** Work left, one small read each. */
	private final Deque<Runnable> steps = new ArrayDeque<>();
	private Map<Integer, Integer> replacements = new LinkedHashMap<>();
	private final Set<Integer> itemIds = new LinkedHashSet<>();
	private final Map<IconKey, Integer> sprites = new LinkedHashMap<>();
	private int ticks;
	private long totalNanos;
	private long lastTickNanos;

	/**
	 * @param nanoTime {@code System::nanoTime}
	 */
	public IconCatalogReader(IconGameData data, LongSupplier nanoTime)
	{
		this.data = data;
		this.nanoTime = nanoTime;
		steps.add(this::readTabs);
		steps.add(this::readSkillRows);
		steps.add(this::readBossRows);
	}

	/**
	 * Reads for at most about {@code budgetNanos} (always at least one step).
	 * Returns the catalog when everything is read, otherwise null: call again on
	 * the next game tick.
	 */
	@Nullable
	public IconCatalog step(long budgetNanos)
	{
		long start = nanoTime.getAsLong();
		long used = 0;
		boolean first = true;
		while (!steps.isEmpty() && (first || used < budgetNanos))
		{
			first = false;
			steps.pollFirst().run();
			used = nanoTime.getAsLong() - start;
		}
		ticks++;
		lastTickNanos = used;
		totalNanos += used;
		return steps.isEmpty() ? IconCatalog.of(new ArrayList<>(itemIds), sprites) : null;
	}

	/** Game ticks used so far. */
	public int ticks()
	{
		return ticks;
	}

	/** Time used by the last {@link #step}. */
	public long lastTickNanos()
	{
		return lastTickNanos;
	}

	/** Time used by all steps so far. */
	public long totalNanos()
	{
		return totalNanos;
	}

	private void readTabs()
	{
		replacements = data.enumMap(ENUM_COLLECTION_REPLACEMENTS);
		// Pages go in front of the hiscore tables, in the log's order.
		int[] tabs = data.enumValues(ENUM_COLLECTION_TABS);
		for (int i = tabs.length - 1; i >= 0; i--)
		{
			int tab = tabs[i];
			steps.addFirst(() -> readTab(tab));
		}
	}

	private void readTab(int tabStruct)
	{
		int[] pages = data.enumValues(data.structParam(tabStruct, PARAM_TAB_PAGES));
		for (int i = pages.length - 1; i >= 0; i--)
		{
			int page = pages[i];
			steps.addFirst(() -> readPage(page));
		}
	}

	private void readPage(int pageStruct)
	{
		for (int itemId : data.enumValues(data.structParam(pageStruct, PARAM_PAGE_ITEMS)))
		{
			Integer replacement = replacements.get(itemId);
			int id = replacement != null ? replacement : itemId;
			if (id >= 0)
			{
				itemIds.add(id);
			}
		}
	}

	private void readSkillRows()
	{
		sprites.put(IconKey.skill(OVERALL), OVERALL_SPRITE);
		for (int row : data.tableRows(DBTableID.HiscoresSkillInfo.ID))
		{
			steps.add(() ->
			{
				String name = data.rowString(row, DBTableID.HiscoresSkillInfo.COL_SKILLNAME);
				int sprite = data.rowInt(row, DBTableID.HiscoresSkillInfo.COL_SKILLICON);
				String key = name == null ? null : name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
				if (sprite >= 0 && IconKey.isSkillName(key))
				{
					sprites.putIfAbsent(IconKey.skill(key), sprite);
				}
			});
		}
	}

	private void readBossRows()
	{
		for (int row : data.tableRows(DBTableID.HiscoresBossesInfo.ID))
		{
			steps.add(() ->
			{
				String name = data.rowString(row, DBTableID.HiscoresBossesInfo.COL_BOSSNAME);
				int sprite = data.rowInt(row, DBTableID.HiscoresBossesInfo.COL_BOSSICON);
				String key = name == null ? null : name.trim();
				if (sprite >= 0 && IconKey.isBossName(key))
				{
					sprites.putIfAbsent(IconKey.boss(key), sprite);
				}
			});
		}
	}
}
