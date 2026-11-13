/*
 * The kill count and duration patterns are adapted from
 * RuneLite's Chat Commands plugin (ChatCommandsPlugin.java):
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
package com.osrstracker.loot;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.Value;
import net.runelite.api.ChatMessageType;
import net.runelite.client.util.Text;

/**
 * Parses kill count and fight time game messages. Generic: any
 * "Your … count is: N" message counts, so new bosses need no code change.
 * The boss name is sent as shown in chat (tags removed); the server
 * normalizes it. Agility laps and Agility Arena tickets are left out for now.
 * <p>
 * Only reads game messages (never player chat), and never logs them.
 */
public final class KillCountParser
{
	/** Chat Commands' KILLCOUNT_PATTERN. */
	private static final Pattern KILL_COUNT = Pattern.compile("Your (?<pre>completion count for |subdued |completed )?"
		+ "(?:<col=[0-9a-f]{6}>)?(?<boss>.+?)(?:</col>)? "
		+ "(?<post>(?:(?:kill|harvest|lap|completion|success|Total Ticket) )?(?:count )?)"
		+ "is: ?(?:<col=[0-9a-f]{6}>|@.+?@)(?<kc>[0-9,]+)</col>");

	/** The start of Chat Commands' KILL_DURATION_PATTERN and NEW_PB_PATTERN, capturing this fight's time. */
	private static final Pattern FIGHT_TIME = Pattern.compile("(?i)(?:(?:Fight |Lap |Challenge |Corrupted challenge )?"
		+ "duration:|Subdued in|(?<!total )completion time:|The Fractured Archive: Total completion time:) "
		+ "(?:<col=[0-9a-f]{6}>|@.+?@)(?<time>[0-9:]+(?:\\.[0-9]+)?)</col>"
		+ "(?:(?<pb> \\(new personal best\\))|\\. Personal best: )");

	@Value
	public static class KillCount
	{
		String boss;
		int killCount;
	}

	/** Time formats the game uses: {@code m:ss} (minutes may exceed 59) or {@code h:mm:ss}, with precise-timing decimals. */
	private static final Pattern TIME = Pattern.compile(
		"(?:(?<h>[0-9]+):(?<hm>[0-5][0-9])|(?<m>[0-9]+)):(?<s>[0-5][0-9])(?:\\.(?<frac>[0-9]{1,2}))?");

	/** The start of the PB value after "Personal best: " (colour tags removed). */
	private static final Pattern PB_VALUE = Pattern.compile("^[0-9:.]+");

	@Value
	public static class FightTime
	{
		double seconds;
		boolean personalBest;
		/** The PB after this kill: the shown PB, or {@link #seconds} when this kill was a new PB. Null if unknown. */
		@Nullable
		Double personalBestSeconds;
	}

	private KillCountParser()
	{
	}

	/** Game messages only: another player can never type a kill count message. */
	public static boolean isGameMessage(ChatMessageType type)
	{
		return type == ChatMessageType.GAMEMESSAGE
			|| type == ChatMessageType.SPAM
			|| type == ChatMessageType.FRIENDSCHATNOTIFICATION;
	}

	@Nullable
	public static KillCount parseKillCount(String message)
	{
		Matcher matcher = KILL_COUNT.matcher(message);
		if (!matcher.find())
		{
			return null;
		}
		String pre = matcher.group("pre");
		String post = matcher.group("post");
		if (isEmpty(pre) && isEmpty(post))
		{
			return null;
		}
		if (post.startsWith("lap ") || post.startsWith("Total Ticket "))
		{
			return null;
		}
		String boss = Text.removeTags(matcher.group("boss")).trim();
		int killCount;
		try
		{
			killCount = Integer.parseInt(matcher.group("kc").replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
		if (boss.isEmpty() || killCount <= 0)
		{
			return null;
		}
		return new KillCount(boss, killCount);
	}

	@Nullable
	public static FightTime parseFightTime(String message)
	{
		Matcher matcher = FIGHT_TIME.matcher(message);
		if (!matcher.find())
		{
			return null;
		}
		Double seconds = toSeconds(matcher.group("time"));
		if (seconds == null)
		{
			return null;
		}
		boolean newPersonalBest = matcher.group("pb") != null;
		Double personalBestSeconds = newPersonalBest
			? seconds
			: parsePersonalBest(message.substring(matcher.end()));
		return new FightTime(seconds, newPersonalBest, personalBestSeconds);
	}

	/** The PB value at the start of {@code rest} (the text after "Personal best: "), or null if its format is unknown. */
	@Nullable
	private static Double parsePersonalBest(String rest)
	{
		Matcher matcher = PB_VALUE.matcher(Text.removeTags(rest));
		if (!matcher.find())
		{
			return null;
		}
		String value = matcher.group();
		// A trailing period ends the sentence, e.g. "Personal best: 1:19.20."
		if (value.endsWith("."))
		{
			value = value.substring(0, value.length() - 1);
		}
		return toSeconds(value);
	}

	/**
	 * Seconds for a game time ({@code m:ss}, {@code h:mm:ss}, each with optional
	 * one or two decimals), or null if the format is unknown or the time is not
	 * positive. Never guesses: a wrong value is worse than none.
	 */
	@Nullable
	public static Double toSeconds(String time)
	{
		Matcher matcher = TIME.matcher(time);
		if (!matcher.matches())
		{
			return null;
		}
		long minutes;
		try
		{
			minutes = matcher.group("h") != null
				? Long.parseLong(matcher.group("h")) * 60 + Integer.parseInt(matcher.group("hm"))
				: Long.parseLong(matcher.group("m"));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
		if (minutes > 1_000_000)
		{
			return null;
		}
		String frac = matcher.group("frac");
		int hundredths = frac == null ? 0 : Integer.parseInt(frac.length() == 1 ? frac + "0" : frac);
		long total = (minutes * 60 + Integer.parseInt(matcher.group("s"))) * 100 + hundredths;
		if (total <= 0)
		{
			return null;
		}
		// Exact hundredths, so no float noise like 126.39999.
		return total / 100.0;
	}

	private static boolean isEmpty(@Nullable String s)
	{
		return s == null || s.isEmpty();
	}
}
