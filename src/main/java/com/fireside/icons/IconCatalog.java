package com.fireside.icons;

import com.fireside.net.IconKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;

/**
 * Every icon the dashboard can show without the account having seen the thing
 * (10.12): all collection log items, all skills and all bosses/raids on the
 * hiscores. Read from the game's own data by {@link IconCatalogReader}.
 */
@Value
public class IconCatalog
{
	/** Collection log item IDs, in the log's order, without duplicates. */
	List<Integer> itemIds;
	/** Skill and boss keys to the game sprite that is their icon, in the game's order. */
	Map<IconKey, Integer> sprites;

	/** Every key: items first, then skills and bosses. */
	public List<IconKey> keys()
	{
		List<IconKey> keys = new ArrayList<>(itemIds.size() + sprites.size());
		for (int itemId : itemIds)
		{
			keys.add(IconKey.item(itemId));
		}
		keys.addAll(sprites.keySet());
		return keys;
	}

	/** How many keys there are of {@code kind}. */
	public int count(IconKey.Kind kind)
	{
		if (kind == IconKey.Kind.ITEM)
		{
			return itemIds.size();
		}
		int count = 0;
		for (IconKey key : sprites.keySet())
		{
			if (key.getKind() == kind)
			{
				count++;
			}
		}
		return count;
	}

	public static IconCatalog of(List<Integer> itemIds, Map<IconKey, Integer> sprites)
	{
		return new IconCatalog(Collections.unmodifiableList(new ArrayList<>(itemIds)),
			Collections.unmodifiableMap(new LinkedHashMap<>(sprites)));
	}
}
