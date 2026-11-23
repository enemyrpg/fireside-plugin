package com.fireside.pet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fireside.event.PetDrop;
import net.runelite.api.ChatMessageType;
import org.junit.Test;

public class PetMessageParserTest
{
	/** The three messages in RuneLite 1.13.6's {@code ScreenshotPlugin.PET_MESSAGES}. */
	private static final String FOLLOWED = "You have a funny feeling like you're being followed";
	private static final String BACKPACK = "You feel something weird sneaking into your backpack";
	private static final String DUPLICATE = "You have a funny feeling like you would have been followed";

	@Test
	public void recognisesTheThreePetMessages()
	{
		assertEquals(PetDrop.Message.FOLLOWED, PetMessageParser.parsePetMessage(FOLLOWED));
		assertEquals(PetDrop.Message.BACKPACK, PetMessageParser.parsePetMessage(BACKPACK));
		assertEquals(PetDrop.Message.DUPLICATE, PetMessageParser.parsePetMessage(DUPLICATE));
	}

	@Test
	public void ignoresColourTagsAndWhatFollows()
	{
		assertEquals(PetDrop.Message.FOLLOWED,
			PetMessageParser.parsePetMessage("<col=ef1020>" + FOLLOWED + ".</col>"));
		assertEquals(PetDrop.Message.BACKPACK, PetMessageParser.parsePetMessage(BACKPACK + "."));
		assertEquals(PetDrop.Message.DUPLICATE, PetMessageParser.parsePetMessage(DUPLICATE + "..."));
	}

	@Test
	public void onlyAtTheStartOfTheMessage()
	{
		// A broadcast about another player, and a player quoting the message.
		assertNull(PetMessageParser.parsePetMessage("Test Alt has a funny feeling like he's being followed: "
			+ "Vorki at 412 kills."));
		assertNull(PetMessageParser.parsePetMessage("Test Alt: " + FOLLOWED));
		assertNull(PetMessageParser.parsePetMessage("lol " + BACKPACK));
		assertNull(PetMessageParser.parsePetMessage("Your Vorkath kill count is: <col=ff0000>412</col>."));
		assertNull(PetMessageParser.parsePetMessage(""));
	}

	@Test
	public void onlyGameMessages()
	{
		assertTrue(PetMessageParser.isGameMessage(ChatMessageType.GAMEMESSAGE));
		assertTrue(PetMessageParser.isGameMessage(ChatMessageType.SPAM));
		assertFalse(PetMessageParser.isGameMessage(ChatMessageType.PUBLICCHAT));
		assertFalse(PetMessageParser.isGameMessage(ChatMessageType.PRIVATECHAT));
		assertFalse(PetMessageParser.isGameMessage(ChatMessageType.FRIENDSCHAT));
		assertFalse(PetMessageParser.isGameMessage(ChatMessageType.CLAN_CHAT));
		assertFalse(PetMessageParser.isGameMessage(ChatMessageType.CLAN_MESSAGE));
	}

	@Test
	public void readsTheCollectionLogLine()
	{
		assertEquals("Vorki", PetMessageParser.parseCollectionLogItem("New item added to your collection log: Vorki"));
		assertEquals("Pet snakeling", PetMessageParser.parseCollectionLogItem(
			"New item added to your collection log: <col=ef1020>Pet snakeling</col>"));
		assertNull(PetMessageParser.parseCollectionLogItem("New item added to your collection log: "));
		assertNull(PetMessageParser.parseCollectionLogItem("x New item added to your collection log: Vorki"));
		assertNull(PetMessageParser.parseCollectionLogItem(FOLLOWED));
	}
}
