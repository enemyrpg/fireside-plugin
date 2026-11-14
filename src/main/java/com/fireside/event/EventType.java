package com.fireside.event;

/** Event types in the contract, with their wire names. */
public enum EventType
{
	SESSION_START("session.start"),
	SESSION_HEARTBEAT("session.heartbeat"),
	SESSION_AFK("session.afk"),
	SESSION_END("session.end"),
	XP_SNAPSHOT("xp.snapshot"),
	LOOT("loot"),
	KC("kc"),
	RAID_SPECIAL_LOOT("raid.special_loot"),
	PB_RECORDS("pb.records");

	private final String wireName;

	EventType(String wireName)
	{
		this.wireName = wireName;
	}

	public String wireName()
	{
		return wireName;
	}
}
