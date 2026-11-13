package com.osrstracker.event;

import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

/**
 * Where loot came from. Built only through the factory methods, so loot from
 * another player can never carry their name: {@link #player()} takes no
 * arguments and is sent as {@code {"type":"PLAYER","name":null}}.
 * Serialized by {@link EventJson}.
 */
@Getter
@ToString
@EqualsAndHashCode
public final class LootSource
{
	public enum Type
	{
		NPC,
		PLAYER,
		EVENT,
		CLUE,
		OTHER,
	}

	private final Type type;
	/** Null only for {@link Type#PLAYER}. */
	@Nullable
	private final String name;
	@Nullable
	private final Integer npcId;
	@Nullable
	private final Integer combatLevel;

	private LootSource(Type type, @Nullable String name, @Nullable Integer npcId, @Nullable Integer combatLevel)
	{
		this.type = type;
		this.name = name;
		this.npcId = npcId;
		this.combatLevel = combatLevel;
	}

	public static LootSource npc(@NonNull String name, @Nullable Integer npcId, @Nullable Integer combatLevel)
	{
		return new LootSource(Type.NPC, name, npcId, combatLevel);
	}

	/** Loot from another player. Their name is never sent. */
	public static LootSource player()
	{
		return new LootSource(Type.PLAYER, null, null, null);
	}

	/** Chests, raids and minigames. */
	public static LootSource event(@NonNull String name)
	{
		return new LootSource(Type.EVENT, name, null, null);
	}

	public static LootSource clue(@NonNull String name)
	{
		return new LootSource(Type.CLUE, name, null, null);
	}

	public static LootSource other(@NonNull String name)
	{
		return new LootSource(Type.OTHER, name, null, null);
	}
}
