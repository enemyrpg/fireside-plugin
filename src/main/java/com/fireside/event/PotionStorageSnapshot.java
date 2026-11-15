package com.fireside.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.NonNull;
import lombok.Value;

/**
 * {@code potion_storage.snapshot} (contract 1.19.0): doses per potion type.
 * {@code itemId} is the full variant (usually 4-dose) and the prices are for
 * that whole item; the server values it as ⌊price × doses ÷ itemDoses⌋. Each
 * {@code itemId} appears once.
 */
@Value
public class PotionStorageSnapshot implements EventPayload
{
	@Value
	public static class Potion
	{
		int itemId;
		/** Name without the dose, e.g. "Super combat potion". */
		@NonNull
		String name;
		/** At least 1. */
		int doses;
		/** Doses in {@code itemId}, 1–4. */
		int itemDoses;
		int gePrice;
		int haPrice;
	}

	@NonNull
	List<Potion> potions;
	@NonNull
	String contentHash;

	public PotionStorageSnapshot(@NonNull List<Potion> potions, @NonNull String contentHash)
	{
		this.potions = Collections.unmodifiableList(new ArrayList<>(potions));
		this.contentHash = contentHash;
	}

	@Override
	public EventType type()
	{
		return EventType.POTION_STORAGE_SNAPSHOT;
	}
}
