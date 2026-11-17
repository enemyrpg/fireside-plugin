package com.fireside.icons;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;

/** {@link IconGameData} from the game client. Only reads the game cache; client thread only. */
@Singleton
public class ClientIconGameData implements IconGameData
{
	private static final int[] NONE = new int[0];

	private final Client client;

	@Inject
	ClientIconGameData(Client client)
	{
		this.client = client;
	}

	@Override
	public int[] enumValues(int enumId)
	{
		EnumComposition e = enumId < 0 ? null : client.getEnum(enumId);
		int[] values = e == null ? null : e.getIntVals();
		return values == null ? NONE : values;
	}

	@Override
	public Map<Integer, Integer> enumMap(int enumId)
	{
		EnumComposition e = enumId < 0 ? null : client.getEnum(enumId);
		int[] keys = e == null ? null : e.getKeys();
		int[] values = e == null ? null : e.getIntVals();
		if (keys == null || values == null)
		{
			return Collections.emptyMap();
		}
		Map<Integer, Integer> map = new LinkedHashMap<>();
		for (int i = 0; i < keys.length && i < values.length; i++)
		{
			map.put(keys[i], values[i]);
		}
		return map;
	}

	@Override
	public int structParam(int structId, int paramId)
	{
		StructComposition struct = structId < 0 ? null : client.getStructComposition(structId);
		return struct == null ? -1 : struct.getIntValue(paramId);
	}

	@Override
	public List<Integer> tableRows(int tableId)
	{
		List<Integer> rows = client.getDBTableRows(tableId);
		return rows == null ? Collections.emptyList() : rows;
	}

	@Nullable
	@Override
	public String rowString(int rowId, int column)
	{
		Object value = field(rowId, column);
		return value instanceof String ? (String) value : null;
	}

	@Override
	public int rowInt(int rowId, int column)
	{
		Object value = field(rowId, column);
		return value instanceof Integer ? (Integer) value : -1;
	}

	@Nullable
	private Object field(int rowId, int column)
	{
		Object[] field = client.getDBTableField(rowId, column, 0);
		return field == null || field.length == 0 ? null : field[0];
	}
}
