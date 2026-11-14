package com.fireside.event;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.NonNull;
import lombok.Value;

/**
 * {@code xp.snapshot}: all skills, keyed by RuneLite's {@code Skill} enum name
 * (e.g. {@code ATTACK}, {@code SAILING}).
 */
@Value
public class XpSnapshot implements EventPayload
{
	public enum Reason
	{
		@SerializedName("login")
		LOGIN,
		@SerializedName("logout")
		LOGOUT,
		@SerializedName("interval")
		INTERVAL,
	}

	@Value
	public static class SkillXp
	{
		int xp;
		int level;
	}

	@NonNull
	Reason reason;
	@NonNull
	Map<String, SkillXp> skills;

	public XpSnapshot(@NonNull Reason reason, @NonNull Map<String, SkillXp> skills)
	{
		this.reason = reason;
		// Keeps the caller's order (RuneLite's Skill order) and can't change later.
		this.skills = Collections.unmodifiableMap(new LinkedHashMap<>(skills));
	}

	@Override
	public EventType type()
	{
		return EventType.XP_SNAPSHOT;
	}
}
