/*
 * The Chambers of Xeric completion pattern is adapted from RuneLite's
 * Chat Commands plugin (ChatCommandsPlugin.java, RAIDS_PB_PATTERN and
 * RAIDS_DURATION_PATTERN):
 *
 * Copyright (c) 2017. l2-
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.fireside.raid;

import com.fireside.event.RaidSpecialLoot.Recipient;
import com.fireside.loot.KillCountParser;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.Value;
import net.runelite.client.util.Text;

/**
 * Raid game messages: the Chambers of Xeric completion (team size, time, PB)
 * and purple announcements. Never logs messages.
 * <p>
 * A purple message names a player. That name only goes into the
 * {@code isLocalPlayer} check and is then dropped: it is never returned,
 * stored or logged.
 */
public final class RaidMessageParser
{
	/** Team size text: "Solo", "3 players", "11-15 players", "24+ players". */
	private static final String TEAM_SIZES = "(?<team>Solo|(?<from>\\d+)(?:(?<plus>\\+)|-(?<to>\\d+))? players?)";
	private static final String TIME = "[0-9:]+(?:\\.[0-9]+)?";

	/** Matched with tags removed and {@code <br>} as a space. */
	private static final Pattern COX_COMPLETION = Pattern.compile("(?i)Congratulations - your raid is complete! ?"
		+ "Team size: " + TEAM_SIZES + " Duration: (?<time>" + TIME + ")"
		+ "(?:(?<pb> \\(new personal best\\))| Personal best: (?<old>\\S+))?");

	/** CoX: "Name - Item". */
	private static final Pattern COX_PURPLE = Pattern.compile("^(?<name>.+?) - (?<item>.+?)\\.?$");
	/**
	 * ToB, ToA and The Fractured Archive: "Name found something special: Item".
	 * The Fractured Archive also has "Name will find something special: Item";
	 * one drop may be announced with both wordings.
	 */
	private static final Pattern FOUND_SPECIAL = Pattern.compile(
		"^(?<name>.+?) (?:found|will find) something special: (?<item>.+?)\\.?$");

	/** Jagex display names are 1–12 characters. */
	private static final int MAX_NAME_LENGTH = 12;
	/** Highest team size the contract allows. */
	static final int MAX_TEAM_SIZE = 100;

	/** A team size as the game shows it: exact ({@code from == to}) or an interval. */
	@Value
	public static class TeamSize
	{
		int from;
		int to;

		public boolean isInterval()
		{
			return from != to;
		}

		public boolean contains(int size)
		{
			return size >= from && size <= to;
		}
	}

	@Value
	public static class CoxCompletion
	{
		TeamSize teamSize;
		KillCountParser.FightTime time;
	}

	@Value
	public static class PurpleMessage
	{
		Raids.Purple purple;
		Recipient recipient;
	}

	private RaidMessageParser()
	{
	}

	@Nullable
	public static CoxCompletion parseCoxCompletion(String message)
	{
		Matcher matcher = COX_COMPLETION.matcher(plain(message));
		if (!matcher.find())
		{
			return null;
		}
		TeamSize teamSize = teamSize(matcher);
		Double seconds = KillCountParser.toSeconds(matcher.group("time"));
		if (teamSize == null || seconds == null)
		{
			return null;
		}
		boolean newPersonalBest = matcher.group("pb") != null;
		Double personalBest = newPersonalBest ? seconds : personalBest(matcher.group("old"));
		return new CoxCompletion(teamSize, new KillCountParser.FightTime(seconds, newPersonalBest, personalBest));
	}

	/**
	 * A purple announcement, or null for anything else (pets, normal loot, other messages).
	 *
	 * @param isLocalPlayer whether a name from the message is the logged-in player
	 */
	@Nullable
	public static PurpleMessage parsePurple(String message, Predicate<String> isLocalPlayer)
	{
		String text = plain(message).trim();
		Matcher matcher = FOUND_SPECIAL.matcher(text);
		if (!matcher.matches())
		{
			matcher = COX_PURPLE.matcher(text);
			if (!matcher.matches())
			{
				return null;
			}
		}
		Raids.Purple purple = Raids.purple(matcher.group("item"));
		if (purple == null)
		{
			return null;
		}
		String name = matcher.group("name").trim();
		if (name.isEmpty() || name.length() > MAX_NAME_LENGTH)
		{
			return null;
		}
		return new PurpleMessage(purple, isLocalPlayer.test(name) ? Recipient.SELF : Recipient.TEAMMATE);
	}

	@Nullable
	private static TeamSize teamSize(Matcher matcher)
	{
		if (matcher.group("from") == null)
		{
			return new TeamSize(1, 1);
		}
		try
		{
			int from = Integer.parseInt(matcher.group("from"));
			int to = matcher.group("plus") != null ? MAX_TEAM_SIZE
				: matcher.group("to") != null ? Integer.parseInt(matcher.group("to")) : from;
			if (from < 1 || to < from || to > MAX_TEAM_SIZE)
			{
				return null;
			}
			return new TeamSize(from, to);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	@Nullable
	private static Double personalBest(@Nullable String value)
	{
		if (value == null)
		{
			return null;
		}
		// A trailing period ends the sentence.
		return KillCountParser.toSeconds(value.endsWith(".") ? value.substring(0, value.length() - 1) : value);
	}

	/** Tags removed, {@code <br>} as a space and non-breaking spaces (in names) as normal spaces. */
	private static String plain(String message)
	{
		return Text.removeTags(message.replace("<br>", " ")).replace(' ', ' ');
	}
}
