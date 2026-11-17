package com.fireside.icons;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The game data the icon catalog is read from: enums, structs and database
 * tables from the game cache. {@link ClientIconGameData} is the real one;
 * tests use a fake. Client thread only.
 */
public interface IconGameData
{
	/** The values of an integer enum, in order; empty if there is no such enum. */
	int[] enumValues(int enumId);

	/** An integer enum as key to value; empty if there is no such enum. */
	Map<Integer, Integer> enumMap(int enumId);

	/** An integer parameter of a struct, or -1 if the struct or the parameter is missing. */
	int structParam(int structId, int paramId);

	/** The rows of a database table; empty if there is no such table. */
	List<Integer> tableRows(int tableId);

	/** A string column of a row, or null. */
	@Nullable
	String rowString(int rowId, int column);

	/** An integer column of a row (a sprite ID for a graphic column), or -1. */
	int rowInt(int rowId, int column);
}
