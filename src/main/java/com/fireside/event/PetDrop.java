package com.fireside.event;

import javax.annotation.Nullable;
import lombok.NonNull;
import lombok.Value;

/**
 * {@code pet.drop}: the game showed one of its pet messages. Holds which
 * message it was, never the chat text. Both guesses are left out of the JSON
 * when null.
 */
@Value
public class PetDrop implements EventPayload
{
	/** Longest pet or source name the contract accepts. */
	public static final int MAX_NAME_LENGTH = 100;

	/** The known pet messages. */
	public enum Message
	{
		/** A new pet that now follows the player. */
		FOLLOWED,
		/** A new pet, placed in the inventory. */
		BACKPACK,
		/** A pet the account already has. */
		DUPLICATE,
	}

	public enum SourceType
	{
		/** A boss, NPC, raid or minigame, named as in {@code loot} and {@code kc}. */
		ACTIVITY,
		/** A skill, by its RuneLite {@code Skill} name. */
		SKILL,
	}

	/** The pet's item in the collection log ("All Pets"), never a metamorph variant. */
	@Value
	public static class GuessedPet
	{
		int itemId;
		@NonNull
		String name;

		public GuessedPet(int itemId, @NonNull String name)
		{
			if (itemId <= 0 || name.isEmpty())
			{
				throw new IllegalArgumentException("pet needs an item ID and a name");
			}
			this.itemId = itemId;
			this.name = cap(name);
		}
	}

	@Value
	public static class GuessedSource
	{
		@NonNull
		SourceType type;
		@NonNull
		String name;

		private GuessedSource(SourceType type, String name)
		{
			if (name.isEmpty())
			{
				throw new IllegalArgumentException("source needs a name");
			}
			this.type = type;
			this.name = name;
		}

		public static GuessedSource activity(@NonNull String name)
		{
			return new GuessedSource(SourceType.ACTIVITY, cap(name));
		}

		/** @param skill RuneLite's {@code Skill} enum name, e.g. {@code WOODCUTTING} */
		public static GuessedSource skill(@NonNull String skill)
		{
			return new GuessedSource(SourceType.SKILL, skill);
		}
	}

	@NonNull
	Message message;
	@Nullable
	GuessedPet guessedPet;
	@Nullable
	GuessedSource guessedSource;

	@Override
	public EventType type()
	{
		return EventType.PET_DROP;
	}

	private static String cap(String name)
	{
		return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
	}
}
