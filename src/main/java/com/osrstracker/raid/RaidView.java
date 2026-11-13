package com.osrstracker.raid;

/**
 * What {@link RaidTracker} reads from the game. {@link ClientRaidView} is the
 * real one; tests use a fake. Client thread only.
 */
public interface RaidView
{
	/** Chambers of Xeric party size ({@code RAIDS_CLIENT_PARTYSIZE}); 0 if unknown. */
	int coxPartySize();

	/** Theatre of Blood party members (filled {@code TOB_CLIENT_P0..P4} slots); 0 if not in a party. */
	int tobPartySize();

	/** Tombs of Amascut party members (filled {@code TOA_CLIENT_P0..P7} slots); 0 if not in a party. */
	int toaPartySize();

	/**
	 * Whether a name from a game message is the logged-in player. The name is
	 * only compared, never kept or logged.
	 */
	boolean isLocalPlayer(String name);
}
