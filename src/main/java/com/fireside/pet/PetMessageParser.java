package com.fireside.pet;

import com.fireside.event.PetDrop;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.runelite.api.ChatMessageType;
import net.runelite.client.util.Text;

/**
 * Recognises the game's pet messages and the collection log's "new item"
 * line. The pet messages are the three RuneLite's Screenshot plugin knows
 * ({@code PET_MESSAGES}, RuneLite 1.13.6).
 * <p>
 * Only reads game messages, and only tells which message it was: the text is
 * never kept, sent or logged. The patterns match at the start of the
 * message, so a broadcast about another player's pet never counts.
 */
public final class PetMessageParser
{
	private static final String FOLLOWED = "You have a funny feeling like you're being followed";
	private static final String BACKPACK = "You feel something weird sneaking into your backpack";
	private static final String DUPLICATE = "You have a funny feeling like you would have been followed";

	/** As in RuneLite's Chat Commands plugin ({@code COLLECTION_LOG_ITEM_PATTERN}). */
	private static final Pattern COLLECTION_LOG_ITEM = Pattern.compile(
		"^New item added to your collection log: (.+)$");

	private PetMessageParser()
	{
	}

	/** Messages from the game itself: a player can never type these. */
	public static boolean isGameMessage(ChatMessageType type)
	{
		return type == ChatMessageType.GAMEMESSAGE || type == ChatMessageType.SPAM;
	}

	/** Which pet message this is, or null for anything else. */
	@Nullable
	public static PetDrop.Message parsePetMessage(String message)
	{
		String text = Text.removeTags(message);
		if (text.startsWith(FOLLOWED))
		{
			return PetDrop.Message.FOLLOWED;
		}
		if (text.startsWith(BACKPACK))
		{
			return PetDrop.Message.BACKPACK;
		}
		if (text.startsWith(DUPLICATE))
		{
			return PetDrop.Message.DUPLICATE;
		}
		return null;
	}

	/** The item name in the collection log's "new item" line, or null for anything else. */
	@Nullable
	public static String parseCollectionLogItem(String message)
	{
		Matcher matcher = COLLECTION_LOG_ITEM.matcher(Text.removeTags(message));
		if (!matcher.find())
		{
			return null;
		}
		String name = matcher.group(1).trim();
		return name.isEmpty() ? null : name;
	}
}
