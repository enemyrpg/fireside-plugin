package com.fireside;

/**
 * Versions of the event/API contract this plugin is built against.
 * <p>
 * The pinned contract package in {@code src/test/resources/contract/} must have
 * version {@link #PINNED_VERSION} (checked by a test). Update both with
 * {@code ./scripts/update-contract.sh <version>}.
 */
public final class Contract
{
	/** Version of the pinned contract package the tests validate against. */
	public static final String PINNED_VERSION = "1.41.0";

	/**
	 * Lowest server contract version the plugin sends events to. A server that
	 * reports an older version in {@code GET /api/v1/ingest/verify} gets a
	 * warning in the panel, and the queue is held back.
	 */
	public static final String REQUIRED_VERSION = "1.17.0";

	/**
	 * Lowest server contract version with the item icon endpoints. An
	 * older server still gets events; the plugin just skips icons.
	 */
	public static final String ICONS_VERSION = "1.18.0";

	/**
	 * Lowest server contract version with {@code bank.snapshot} and
	 * {@code potion_storage.snapshot}. An older server gets no
	 * bank snapshots, and the stored bank hashes are cleared so the bank is
	 * sent again once the server is upgraded.
	 */
	public static final String BANK_VERSION = "1.19.0";

	/**
	 * Lowest server contract version with {@code quests.snapshot} and
	 * {@code diaries.snapshot}. An older server gets neither, and the
	 * stored hashes are cleared so both are sent once the server is upgraded.
	 */
	public static final String QUESTS_VERSION = "1.21.0";

	/**
	 * Lowest server contract version with {@code pet.drop}. An older
	 * server gets no pet drops.
	 */
	public static final String PETS_VERSION = "1.27.0";

	/**
	 * Lowest server contract version with icons for skills and bosses/raids.
	 * An older server gets no icon import (collection log, skills and bosses);
	 * item icons from events still work from {@link #ICONS_VERSION}.
	 */
	public static final String NAMED_ICONS_VERSION = "1.29.0";

	private Contract()
	{
	}
}
