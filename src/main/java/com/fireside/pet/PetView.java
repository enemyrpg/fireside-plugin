package com.fireside.pet;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * What {@link PetTracker} reads from the game. {@link ClientPetView} is the
 * real one; tests use a fake. Client thread only.
 */
public interface PetView
{
	/** Not Leagues, Deadman or a beta world. */
	boolean standardWorld();

	/**
	 * The game's own list of pets: the items on the collection log's "All
	 * Pets" page (never metamorph variants), item ID to name. Empty if it
	 * cannot be read.
	 */
	Map<Integer, String> pets();

	/** How many of each item the inventory holds, item ID to quantity. */
	Map<Integer, Integer> inventory();

	/** The name of the player's own follower, or null without one. */
	@Nullable
	String followerName();
}
