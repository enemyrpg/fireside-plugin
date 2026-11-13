package com.osrstracker.loot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.runelite.api.ChatMessageType;
import org.junit.Test;

/**
 * Real game messages, as RuneLite receives them (with colour tags). Most are
 * taken from RuneLite's Chat Commands plugin tests.
 */
public class KillCountParserTest
{
	private static void assertKc(String message, String boss, int killCount)
	{
		KillCountParser.KillCount kc = KillCountParser.parseKillCount(message);
		assertEquals(message, new KillCountParser.KillCount(boss, killCount), kc);
	}

	private static void assertTime(String message, double seconds, boolean personalBest, Double personalBestSeconds)
	{
		KillCountParser.FightTime time = KillCountParser.parseFightTime(message);
		assertEquals(message, new KillCountParser.FightTime(seconds, personalBest, personalBestSeconds), time);
	}

	// Kill counts: at least ten bosses and activities

	@Test
	public void bossKillCounts()
	{
		assertKc("Your Zulrah kill count is: <col=ff0000>4</col>.", "Zulrah", 4);
		assertKc("Your Vorkath kill count is: <col=ff0000>412</col>.", "Vorkath", 412);
		assertKc("Your Corporeal Beast kill count is: <col=ff0000>4</col>.", "Corporeal Beast", 4);
		assertKc("Your Kree'arra kill count is: <col=ff0000>4</col>.", "Kree'arra", 4);
		assertKc("Your Grotesque Guardians kill count is: <col=ff0000>179</col>.", "Grotesque Guardians", 179);
		assertKc("Your TzTok-Jad kill count is: <col=ff0000>2</col>.", "TzTok-Jad", 2);
		assertKc("Your TzKal-Zuk kill count is: <col=ff0000>3</col>.", "TzKal-Zuk", 3);
		assertKc("Your Tempoross kill count is: <col=ff0000>55</col>.", "Tempoross", 55);
	}

	@Test
	public void thousandsSeparatorAndNoTrailingPeriod()
	{
		assertKc("Your Nightmare kill count is: <col=ff0000>1,130</col>", "Nightmare", 1130);
		assertKc("Your Nightmare kill count is: <col=ff0000>1130</col>", "Nightmare", 1130);
	}

	@Test
	public void colourTagsInTheNameAreRemoved()
	{
		assertKc("Your <col=a53fff>Corrupted Hunllef (Echo)</col> kill count is: <col=ff3045>31</col>",
			"Corrupted Hunllef (Echo)", 31);
		assertKc("Your <col=6800bf>Kalphite Queen (Echo)</col> kill count is:<col=e00a19>1</col>",
			"Kalphite Queen (Echo)", 1);
	}

	@Test
	public void activityCounts()
	{
		assertKc("Your Barrows chest count is: <col=ff0000>277</col>.", "Barrows chest", 277);
		assertKc("Your Gauntlet completion count is: <col=ff0000>124</col>.", "Gauntlet", 124);
		assertKc("Your Corrupted Gauntlet completion count is: <col=ff0000>4729</col>.", "Corrupted Gauntlet", 4729);
		assertKc("Your subdued Wintertodt count is: <col=ff0000>4</col>.", "Wintertodt", 4);
		assertKc("Your herbiboar harvest count is: <col=ff0000>4091</col>.", "herbiboar", 4091);
		assertKc("Your completion count for TzHaar-Ket-Rak's First Challenge is: <col=ff0000>3</col>.",
			"TzHaar-Ket-Rak's First Challenge", 3);
	}

	@Test
	public void raidCountsUseTheModeAsShown()
	{
		assertKc("Your completed Chambers of Xeric count is: <col=ff0000>51</col>.", "Chambers of Xeric", 51);
		assertKc("Your completed Chambers of Xeric Challenge Mode count is: <col=ff0000>13</col>.",
			"Chambers of Xeric Challenge Mode", 13);
		assertKc("Your completed Theatre of Blood: Entry Mode count is: <col=ff0000>73</col>.",
			"Theatre of Blood: Entry Mode", 73);
		assertKc("Your completed Tombs of Amascut: Expert Mode count is: <col=ff0000>1</col>.",
			"Tombs of Amascut: Expert Mode", 1);
		assertKc("Your completed Fractured Archive count is: <col=ff0000>3</col>.", "Fractured Archive", 3);
	}

	@Test
	public void agilityLapsAndTicketsAreNotKillCounts()
	{
		assertNull(KillCountParser.parseKillCount(
			"Your Prifddinas Agility Course lap count is: @mes_hl_red@2</col>."));
		assertNull(KillCountParser.parseKillCount(
			"Your Shayzien Advanced Agility Course lap count is: @mes_hl_red@2</col>."));
		assertNull(KillCountParser.parseKillCount("Your Agility Arena Total Ticket count is: <col=ff0000>206</col>."));
	}

	@Test
	public void otherMessagesAreIgnored()
	{
		assertNull(KillCountParser.parseKillCount("Welcome to Old School RuneScape."));
		assertNull(KillCountParser.parseKillCount("Fight duration: <col=ff0000>2:06</col>. Personal best: 1:19."));
		// Neither a "pre" nor a "post" word: Chat Commands does not treat this as a count either.
		assertNull(KillCountParser.parseKillCount("Your reward is: <col=ff0000>5</col>"));
		assertNull(KillCountParser.parseKillCount("Your Zulrah kill count is: <col=ff0000>0</col>."));
		assertNull(KillCountParser.parseKillCount("Your Zulrah kill count is: <col=ff0000>99999999999</col>."));
	}

	// Fight times

	@Test
	public void slowerKillSendsTheOlderPersonalBest()
	{
		assertTime("Fight duration: <col=ff0000>1:57</col>. Personal best: 1:03", 117, false, 63.0);
		assertTime("Fight duration: <col=ff0000>2:06</col>. Personal best: 1:19.", 126, false, 79.0);
		assertTime("Fight duration: <col=ff0000>0:59</col>. Personal best: 0:55", 59, false, 55.0);
		assertTime("Subdued in <col=ef1020>7:40</col>. Personal best: 5:38.", 460, false, 338.0);
	}

	@Test
	public void personalBestInColourTags()
	{
		assertTime("Challenge duration: <col=ff0000>1:10</col>. Personal best: <col=ff0000>0:59</col>", 70, false,
			59.0);
		assertTime("Duration: <col=ff0000>1:10</col>. Personal best: </col><col=ff0000>0:59.40</col>", 70, false,
			59.4);
	}

	@Test
	public void newPersonalBestIsThisKillsTime()
	{
		assertTime("Fight duration: <col=ff0000>1:36</col> (new personal best)", 96, true, 96.0);
		assertTime("Fight duration: <col=ff0000>1:36.40</col> (new personal best)", 96.4, true, 96.4);
		assertTime("Subdued in <col=ef1020>5:20.60</col> (new personal best).", 320.6, true, 320.6);
		assertTime("Challenge duration: <col=ff0000>1:46.40</col> (new personal best)", 106.4, true, 106.4);
		assertTime("Lap duration: @mes_hl_red@1:01.20</col> (new personal best).", 61.2, true, 61.2);
	}

	@Test
	public void preciseTimingWithDecimals()
	{
		assertTime("Fight duration: <col=ff0000>2:06.40</col>. Personal best: 1:19.20.", 126.4, false, 79.2);
		assertTime("Fight duration: <col=ff0000>2:41.40</col>. Personal best: 2:14.20", 161.4, false, 134.2);
		assertTime("Challenge duration: <col=ff0000>10:24.20</col>. Personal best: 7:52.40.", 624.2, false, 472.4);
		// PB set before precise timing existed: whole seconds.
		assertTime("Fight duration: <col=ff0000>1:57.60</col>. Personal best: 1:03", 117.6, false, 63.0);
	}

	@Test
	public void timesOverAnHour()
	{
		// Minutes above 59 (m:ss).
		assertTime("Duration: <col=ff0000>104:31.20</col> (new personal best)", 6271.2, true, 6271.2);
		assertTime("Duration: <col=ff0000>172:18</col>. Personal best: 134:52", 10338, false, 8092.0);
		// Hours (h:mm:ss).
		assertTime("Duration: <col=ff0000>1:02:03.20</col>. Personal best: 1:00:41.40", 3723.2, false, 3641.4);
		assertTime("Duration: <col=ff0000>1:02:03</col>. Personal best: 58:12.", 3723, false, 3492.0);
	}

	@Test
	public void unknownPersonalBestFormatIsLeftOut()
	{
		assertTime("Fight duration: <col=ff0000>1:57</col>. Personal best: 1:75", 117, false, null);
		assertTime("Fight duration: <col=ff0000>1:57</col>. Personal best: 63", 117, false, null);
		assertTime("Fight duration: <col=ff0000>1:57</col>. Personal best: 1:3", 117, false, null);
		assertTime("Fight duration: <col=ff0000>1:57</col>. Personal best: 1:03.123", 117, false, null);
		assertTime("Fight duration: <col=ff0000>1:57</col>. Personal best: 1:02:03:04", 117, false, null);
		assertTime("Fight duration: <col=ff0000>1:57</col>. Personal best: ", 117, false, null);
		assertTime("Fight duration: <col=ff0000>1:57</col>. Personal best: 0:00", 117, false, null);
	}

	@Test
	public void unknownFightTimeFormatIsNotSent()
	{
		assertNull(KillCountParser.parseFightTime("Fight duration: <col=ff0000>117</col>. Personal best: 1:03"));
		assertNull(KillCountParser.parseFightTime("Fight duration: <col=ff0000>1:75</col> (new personal best)"));
		assertNull(KillCountParser.parseFightTime("Fight duration: <col=ff0000>0:00</col>. Personal best: 1:03"));
	}

	@Test
	public void messagesWithoutPersonalBestGiveNoFightTime()
	{
		assertNull(KillCountParser.parseFightTime("Your Zulrah kill count is: <col=ff0000>4</col>."));
		// A duration with neither "Personal best" nor "(new personal best)", e.g. a ToB wave.
		assertNull(KillCountParser.parseFightTime("Duration: <col=ff0000>2:42.60</col><br>"));
		assertNull(KillCountParser.parseFightTime(
			"Theatre of Blood total completion time: <col=ff0000>24:40.20</col>. Personal best: 20:45.00"));
	}

	@Test
	public void timeStrings()
	{
		assertEquals(Double.valueOf(59), KillCountParser.toSeconds("0:59"));
		assertEquals(Double.valueOf(126.4), KillCountParser.toSeconds("2:06.40"));
		assertEquals(Double.valueOf(126.4), KillCountParser.toSeconds("2:06.4"));
		assertEquals(Double.valueOf(6271.2), KillCountParser.toSeconds("104:31.20"));
		assertEquals(Double.valueOf(3723.2), KillCountParser.toSeconds("1:02:03.20"));
		assertEquals(Double.valueOf(3723), KillCountParser.toSeconds("1:02:03"));
		for (String bad : new String[]{"", "59", "1:5", "1:60", "1:75", "1:05.", "1:05.123", "1:60:00", "1:2:03",
			"1:02:03:04", "0:00", "0:00.00", ":05", "1:05.2.3", "99999999999:00"})
		{
			assertNull(bad, KillCountParser.toSeconds(bad));
		}
	}

	@Test
	public void onlyGameMessagesAreRead()
	{
		assertTrue(KillCountParser.isGameMessage(ChatMessageType.GAMEMESSAGE));
		assertTrue(KillCountParser.isGameMessage(ChatMessageType.SPAM));
		assertTrue(KillCountParser.isGameMessage(ChatMessageType.FRIENDSCHATNOTIFICATION));
		assertFalse(KillCountParser.isGameMessage(ChatMessageType.PUBLICCHAT));
		assertFalse(KillCountParser.isGameMessage(ChatMessageType.PRIVATECHAT));
		assertFalse(KillCountParser.isGameMessage(ChatMessageType.CLAN_CHAT));
		assertFalse(KillCountParser.isGameMessage(ChatMessageType.TRADE));
	}
}
