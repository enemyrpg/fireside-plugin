package com.fireside.net;

import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.Value;

/**
 * What an icon belongs to: an item (its ID exactly as in the events), or a
 * skill or boss/raid by name (10.12). The server's key for the icon.
 */
@Value
public class IconKey
{
	public enum Kind
	{
		ITEM,
		SKILL,
		BOSS,
	}

	/** The server's limit for skill and boss names. */
	public static final int MAX_NAME_LENGTH = 100;

	private static final Pattern SKILL_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

	Kind kind;
	/** The item ID; -1 for a skill or boss. */
	int itemId;
	/** The skill or boss name as it is sent; null for an item. */
	@Nullable
	String name;

	public static IconKey item(int itemId)
	{
		return new IconKey(Kind.ITEM, itemId, null);
	}

	/** {@code name} is the skill as in {@code xp.snapshot}: {@code ATTACK}, {@code OVERALL}. */
	public static IconKey skill(String name)
	{
		return new IconKey(Kind.SKILL, -1, name);
	}

	/** {@code name} is the boss or raid as the game writes it; the server normalizes it. */
	public static IconKey boss(String name)
	{
		return new IconKey(Kind.BOSS, -1, name);
	}

	/** The key for a {@code kind}/{@code key} pair from the server, or null if it is not one we know. */
	@Nullable
	public static IconKey named(@Nullable String kind, @Nullable String name)
	{
		if (kind == null || name == null)
		{
			return null;
		}
		switch (kind)
		{
			case "SKILL":
				return skill(name);
			case "BOSS":
				return boss(name);
			default:
				return null;
		}
	}

	/** Whether the server's schema accepts {@code name} as a skill name ({@code UPPER_SNAKE_CASE}). */
	public static boolean isSkillName(@Nullable String name)
	{
		return name != null && name.length() <= MAX_NAME_LENGTH && SKILL_NAME.matcher(name).matches();
	}

	/** Whether the server's schema accepts {@code name} as a boss name (no space first or last, one line). */
	public static boolean isBossName(@Nullable String name)
	{
		return name != null && !name.isEmpty() && name.length() <= MAX_NAME_LENGTH
			&& !Character.isWhitespace(name.charAt(0)) && !Character.isWhitespace(name.charAt(name.length() - 1))
			&& name.indexOf('\n') < 0 && name.indexOf('\r') < 0;
	}
}
