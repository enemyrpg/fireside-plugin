package com.osrstracker;

/**
 * Version of the event/API contract this plugin is built against.
 * <p>
 * The pinned contract package in {@code src/test/resources/contract/} must have
 * the same version (checked by a test). Update both with
 * {@code ./scripts/update-contract.sh <version>}.
 */
public final class Contract
{
	/**
	 * Lowest server contract version the plugin works with. A server that
	 * reports an older version in {@code GET /api/v1/ingest/verify} gets a
	 * warning in the panel, and the queue is held back (7.3).
	 */
	public static final String REQUIRED_VERSION = "1.13.0";

	private Contract()
	{
	}
}
