package com.osrstracker.event;

/**
 * Account types in the contract, in the order of the {@code IRONMAN} varbit
 * (0–6). Other values are rejected by the server.
 */
public enum AccountType
{
	NORMAL,
	IRONMAN,
	ULTIMATE_IRONMAN,
	HARDCORE_IRONMAN,
	GROUP_IRONMAN,
	HARDCORE_GROUP_IRONMAN,
	UNRANKED_GROUP_IRONMAN,
}
