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
	public static final String PINNED_VERSION = "1.18.0";

	/**
	 * Lowest server contract version the plugin sends events to. A server that
	 * reports an older version in {@code GET /api/v1/ingest/verify} gets a
	 * warning in the panel, and the queue is held back (7.3).
	 */
	public static final String REQUIRED_VERSION = "1.17.0";

	/**
	 * Lowest server contract version with the item icon endpoints (10.3). An
	 * older server still gets events; the plugin just skips icons.
	 */
	public static final String ICONS_VERSION = "1.18.0";

	private Contract()
	{
	}
}
