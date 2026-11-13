package com.osrstracker.event;

import javax.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/**
 * {@code raid.special_loot}: a raid purple for you or a teammate. There is no
 * field for a name: the recipient is only {@link Recipient#SELF} or
 * {@link Recipient#TEAMMATE}. Null optional fields are left out of the JSON.
 */
@Value
@Builder
public class RaidSpecialLoot implements EventPayload
{
	public enum Recipient
	{
		SELF,
		TEAMMATE,
	}

	/** An item without {@code haPrice}. */
	@Value
	public static class Item
	{
		int itemId;
		@NonNull
		String name;
		int quantity;
		int gePrice;
	}

	@NonNull
	String raid;
	@NonNull
	Item item;
	@NonNull
	Recipient recipient;
	@Nullable
	Integer teamSize;
	@Nullable
	Integer killCount;

	@Override
	public EventType type()
	{
		return EventType.RAID_SPECIAL_LOOT;
	}
}
