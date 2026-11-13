package com.osrstracker.event;

import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import lombok.NonNull;
import lombok.Value;

/** {@code loot}: from the Loot Tracker's {@code LootReceived}. */
@Value
public class Loot implements EventPayload
{
	@NonNull
	LootSource source;
	@NonNull
	List<LootItem> items;
	/** Only set when the plugin knows it. Left out of the JSON when null. */
	@Nullable
	Integer killCount;

	public Loot(@NonNull LootSource source, @NonNull List<LootItem> items, @Nullable Integer killCount)
	{
		if (items.isEmpty())
		{
			throw new IllegalArgumentException("loot without items");
		}
		this.source = source;
		this.items = Collections.unmodifiableList(items);
		this.killCount = killCount;
	}

	@Override
	public EventType type()
	{
		return EventType.LOOT;
	}
}
