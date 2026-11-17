package com.fireside.raid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fireside.event.RaidSpecialLoot.Recipient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.Test;

/** Message strings from RuneLite's ChatCommandsPluginTest and Raid Data Tracker's tests. */
public class RaidMessageParserTest
{
	static final String COX_NEW_PB_24 = "<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: "
		+ "<col=ff0000>24+ players</col> Duration:</col> <col=ff0000>37:04.20</col> (new personal best)</col>>";
	static final String COX_OLD_PB_11_15 = "<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: "
		+ "<col=ff0000>11-15 players</col> Duration:</col> <col=ff0000>23:25.40</col> Personal best: </col>"
		+ "<col=ff0000>20:19.20</col>";
	static final String COX_OLD_PB_3 = "<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: "
		+ "<col=ff0000>3 players</col> Duration:</col> <col=ff0000>41:10</col> Personal best: </col>"
		+ "<col=ff0000>40:03</col>";

	static String cox(String teamSize, String duration, String pb)
	{
		return "<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: <col=ff0000>" + teamSize
			+ "</col> Duration:</col> <col=ff0000>" + duration + "</col>"
			+ (pb == null ? " (new personal best)</col>" : " Personal best: </col><col=ff0000>" + pb + "</col>");
	}

	private final List<String> namesSeen = new ArrayList<>();

	private Predicate<String> me(String myName)
	{
		return name ->
		{
			namesSeen.add(name);
			return name.equalsIgnoreCase(myName);
		};
	}

	@Test
	public void coxNewPersonalBestWithOpenInterval()
	{
		RaidMessageParser.CoxCompletion done = RaidMessageParser.parseCoxCompletion(COX_NEW_PB_24);
		assertEquals(new RaidMessageParser.TeamSize(24, 100), done.getTeamSize());
		assertEquals(37 * 60 + 4.2, done.getTime().getSeconds(), 0);
		assertTrue(done.getTime().isPersonalBest());
		assertEquals(Double.valueOf(37 * 60 + 4.2), done.getTime().getPersonalBestSeconds());
	}

	@Test
	public void coxOldPersonalBestWithInterval()
	{
		RaidMessageParser.CoxCompletion done = RaidMessageParser.parseCoxCompletion(COX_OLD_PB_11_15);
		assertEquals(new RaidMessageParser.TeamSize(11, 15), done.getTeamSize());
		assertTrue(done.getTeamSize().isInterval());
		assertEquals(23 * 60 + 25.4, done.getTime().getSeconds(), 0);
		assertFalse(done.getTime().isPersonalBest());
		assertEquals(Double.valueOf(20 * 60 + 19.2), done.getTime().getPersonalBestSeconds());
	}

	@Test
	public void coxExactSizes()
	{
		RaidMessageParser.CoxCompletion three = RaidMessageParser.parseCoxCompletion(COX_OLD_PB_3);
		assertEquals(new RaidMessageParser.TeamSize(3, 3), three.getTeamSize());
		assertFalse(three.getTeamSize().isInterval());
		assertEquals(41 * 60 + 10.0, three.getTime().getSeconds(), 0);
		assertEquals(Double.valueOf(40 * 60 + 3.0), three.getTime().getPersonalBestSeconds());

		assertEquals(new RaidMessageParser.TeamSize(1, 1),
			RaidMessageParser.parseCoxCompletion(cox("Solo", "1:40:26", "31:12")).getTeamSize());
		assertEquals(new RaidMessageParser.TeamSize(16, 23),
			RaidMessageParser.parseCoxCompletion(cox("16-23 players", "30:00", null)).getTeamSize());
		assertEquals(new RaidMessageParser.TeamSize(10, 10),
			RaidMessageParser.parseCoxCompletion(cox("10 players", "30:00", null)).getTeamSize());
	}

	@Test
	public void coxHoursAndUnknownPbFormat()
	{
		RaidMessageParser.CoxCompletion solo = RaidMessageParser.parseCoxCompletion(cox("Solo", "1:40:26", "31:12"));
		assertEquals(3600 + 40 * 60 + 26.0, solo.getTime().getSeconds(), 0);
		RaidMessageParser.CoxCompletion odd = RaidMessageParser.parseCoxCompletion(cox("Solo", "40:26", "soon"));
		assertEquals(40 * 60 + 26.0, odd.getTime().getSeconds(), 0);
		assertNull("Unknown PB format: no PB rather than a wrong one", odd.getTime().getPersonalBestSeconds());
	}

	@Test
	public void notCoxCompletion()
	{
		assertNull(RaidMessageParser.parseCoxCompletion("Your completed Chambers of Xeric count is: <col=ff0000>51</col>."));
		assertNull(RaidMessageParser.parseCoxCompletion("Fight duration: <col=ff0000>1:36</col> (new personal best)"));
	}

	@Test
	public void coxPurpleForATeammateWithNonBreakingSpace()
	{
		RaidMessageParser.PurpleMessage purple = RaidMessageParser.parsePurple(
			"K1NG DK - Kodai insignia", me("Canvasba"));
		assertEquals(21043, purple.getPurple().getItemId());
		assertEquals(Recipient.TEAMMATE, purple.getRecipient());
		assertEquals("Name with a normal space", "K1NG DK", namesSeen.get(0));
	}

	@Test
	public void coxPurpleForMeWithColourTags()
	{
		RaidMessageParser.PurpleMessage purple = RaidMessageParser.parsePurple(
			"<col=ef20ff>Canvasba - Twisted bow</col>", me("Canvasba"));
		assertEquals(20997, purple.getPurple().getItemId());
		assertEquals(Recipient.SELF, purple.getRecipient());
	}

	@Test
	public void tobAndToaPurples()
	{
		RaidMessageParser.PurpleMessage hilt = RaidMessageParser.parsePurple(
			"Canvasba found something special: Avernic defender hilt", me("Canvasba"));
		assertEquals(22477, hilt.getPurple().getItemId());
		assertEquals(Raids.Group.THEATRE_OF_BLOOD, hilt.getPurple().getGroup());
		assertEquals(Recipient.SELF, hilt.getRecipient());

		RaidMessageParser.PurpleMessage lightbearer = RaidMessageParser.parsePurple(
			"<col=ff0000>Bob Teammate found something special: Lightbearer</col>", me("Canvasba"));
		assertEquals(25975, lightbearer.getPurple().getItemId());
		assertEquals(Recipient.TEAMMATE, lightbearer.getRecipient());
	}

	@Test
	public void fracturedArchivePurpleInTheToaFormat()
	{
		RaidMessageParser.PurpleMessage purple = RaidMessageParser.parsePurple(
			"Canvasba found something special: Rondache", me("Canvasba"));
		assertEquals(Raids.Group.FRACTURED_ARCHIVE, purple.getPurple().getGroup());
	}

	/** The two wordings seen in game on 16 and 17 November 2026, with their split colour tags. */
	@Test
	public void fracturedArchiveWillFindWordingForATeammate()
	{
		RaidMessageParser.PurpleMessage purple = RaidMessageParser.parsePurple(
			"<col=ff0000>K1NG DK</col> will find something special: <col=ff0000>Rondache</col>", me("Canvasba"));
		assertEquals(Raids.Group.FRACTURED_ARCHIVE, purple.getPurple().getGroup());
		assertEquals("Rondache", purple.getPurple().getName());
		assertEquals(Recipient.TEAMMATE, purple.getRecipient());
		assertEquals("Only the name goes to the check", "K1NG DK", namesSeen.get(0));
	}

	@Test
	public void fracturedArchiveFoundWordingWithSplitColourTags()
	{
		RaidMessageParser.PurpleMessage mine = RaidMessageParser.parsePurple(
			"<col=ef1020>Canvasba</col> found something special: <col=ef1020>Rondache</col>", me("Canvasba"));
		assertEquals(Raids.Group.FRACTURED_ARCHIVE, mine.getPurple().getGroup());
		assertEquals(Recipient.SELF, mine.getRecipient());

		RaidMessageParser.PurpleMessage tome = RaidMessageParser.parsePurple(
			"<col=ef1020>K1NG DK</col> found something special: <col=ef1020>Zorya's tome (uncharged)</col>",
			me("Canvasba"));
		assertEquals("Zorya's tome (uncharged)", tome.getPurple().getName());
		assertEquals(Recipient.TEAMMATE, tome.getRecipient());
	}

	@Test
	public void recipientComesFromTheNameNotTheWording()
	{
		RaidMessageParser.PurpleMessage purple = RaidMessageParser.parsePurple(
			"<col=ff0000>Canvasba</col> will find something special: <col=ff0000>Rondache</col>", me("Canvasba"));
		assertEquals(Recipient.SELF, purple.getRecipient());
	}

	@Test
	public void notPurples()
	{
		Predicate<String> me = me("Canvasba");
		assertNull("Pet", RaidMessageParser.parsePurple("Canvasba found something special: Lil' Zik", me));
		assertNull("Pet", RaidMessageParser.parsePurple("Canvasba found something special: Tumeken's guardian", me));
		assertNull("Pet in the will find wording", RaidMessageParser.parsePurple(
			"<col=ff0000>K1NG DK</col> will find something special: <col=ff0000>Lil' Zik</col>", me));
		assertNull(RaidMessageParser.parsePurple(COX_OLD_PB_3, me));
		assertNull(RaidMessageParser.parsePurple("<col=ef20ff>Special loot:</col>", me));
		assertNull(RaidMessageParser.parsePurple("Twisted Kit recipients: Canvasba", me));
		assertNull("Not a purple", RaidMessageParser.parsePurple("Canvasba - Dragon bones", me));
		assertNull("Longer than a name", RaidMessageParser.parsePurple(
			"This is not a player name - Twisted bow", me));
		assertNull(RaidMessageParser.parsePurple(" - Twisted bow", me));
	}
}
