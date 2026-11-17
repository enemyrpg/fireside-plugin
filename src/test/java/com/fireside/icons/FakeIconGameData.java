package com.fireside.icons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.DBTableID;

/** Game data made up by the test: a small collection log and two small hiscore tables. */
final class FakeIconGameData implements IconGameData
{
	final Map<Integer, int[]> enums = new HashMap<>();
	final Map<Integer, Map<Integer, Integer>> enumMaps = new HashMap<>();
	final Map<Long, Integer> structParams = new HashMap<>();
	final Map<Integer, List<Integer>> tables = new HashMap<>();
	final Map<Long, Object> fields = new HashMap<>();
	int reads;

	private int nextId = 50_000;

	/** Adds a collection log tab with the given pages (each an array of item IDs). */
	void tab(int[]... pages)
	{
		int tabStruct = nextId++;
		int pagesEnum = nextId++;
		int[] pageStructs = new int[pages.length];
		for (int i = 0; i < pages.length; i++)
		{
			pageStructs[i] = nextId++;
			int itemsEnum = nextId++;
			enums.put(itemsEnum, pages[i]);
			structParams.put(key(pageStructs[i], IconCatalogReader.PARAM_PAGE_ITEMS), itemsEnum);
		}
		enums.put(pagesEnum, pageStructs);
		structParams.put(key(tabStruct, IconCatalogReader.PARAM_TAB_PAGES), pagesEnum);
		int[] tabs = enums.getOrDefault(IconCatalogReader.ENUM_COLLECTION_TABS, new int[0]);
		int[] more = java.util.Arrays.copyOf(tabs, tabs.length + 1);
		more[tabs.length] = tabStruct;
		enums.put(IconCatalogReader.ENUM_COLLECTION_TABS, more);
	}

	void replace(int itemId, int shownAs)
	{
		enumMaps.computeIfAbsent(IconCatalogReader.ENUM_COLLECTION_REPLACEMENTS, k -> new LinkedHashMap<>())
			.put(itemId, shownAs);
	}

	void skill(Object name, Object sprite)
	{
		row(DBTableID.HiscoresSkillInfo.ID, DBTableID.HiscoresSkillInfo.COL_SKILLNAME, name,
			DBTableID.HiscoresSkillInfo.COL_SKILLICON, sprite);
	}

	void boss(Object name, Object sprite)
	{
		row(DBTableID.HiscoresBossesInfo.ID, DBTableID.HiscoresBossesInfo.COL_BOSSNAME, name,
			DBTableID.HiscoresBossesInfo.COL_BOSSICON, sprite);
	}

	private void row(int table, int nameColumn, Object name, int spriteColumn, Object sprite)
	{
		int row = nextId++;
		tables.computeIfAbsent(table, k -> new ArrayList<>()).add(row);
		if (name != null)
		{
			fields.put(key(row, nameColumn), name);
		}
		if (sprite != null)
		{
			fields.put(key(row, spriteColumn), sprite);
		}
	}

	private static long key(int a, int b)
	{
		return (long) a << 32 | b;
	}

	@Override
	public int[] enumValues(int enumId)
	{
		reads++;
		return enums.getOrDefault(enumId, new int[0]);
	}

	@Override
	public Map<Integer, Integer> enumMap(int enumId)
	{
		reads++;
		return enumMaps.getOrDefault(enumId, Collections.emptyMap());
	}

	@Override
	public int structParam(int structId, int paramId)
	{
		return structParams.getOrDefault(key(structId, paramId), -1);
	}

	@Override
	public List<Integer> tableRows(int tableId)
	{
		reads++;
		return tables.getOrDefault(tableId, Collections.emptyList());
	}

	@Override
	public String rowString(int rowId, int column)
	{
		reads++;
		Object value = fields.get(key(rowId, column));
		return value instanceof String ? (String) value : null;
	}

	@Override
	public int rowInt(int rowId, int column)
	{
		Object value = fields.get(key(rowId, column));
		return value instanceof Integer ? (Integer) value : -1;
	}
}
